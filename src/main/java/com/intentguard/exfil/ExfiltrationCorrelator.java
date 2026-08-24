package com.intentguard.exfil;

import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.intentguard.domain.AgentRiskMarkers;
import com.intentguard.domain.CommandEvent;
import com.intentguard.domain.CorrectiveAction;

/**
 * Data-exfiltration guardrails (STRETCH, Req 6), feature-flagged behind
 * {@code intentguard.guardrails.exfiltration.enabled}.
 *
 * <p>For each Command_Event this guard contributes, in order of increasing severity, to the
 * guardrail chain via an {@link ExfiltrationContribution}:
 * <ul>
 *   <li><b>Unapproved egress</b> (Req 6.1): when the event opens an outbound connection to a
 *       destination that is not on the configured approved-destination list, the Corrective_Action
 *       floor is raised to at least {@code ASK} and the destination is recorded.</li>
 *   <li><b>Secret+egress correlation</b> (Req 6.2, 6.3): when an earlier Command_Event in the same
 *       {@code Intent_Session} accessed a configured credential/secret file and, within the
 *       configured correlation window, this event opens an outbound connection, a
 *       correlated-exfiltration alert is raised and the floor is raised to at least {@code ASK}.
 *       Per-session recent secret access is tracked using an injectable {@link Clock}.</li>
 *   <li><b>Canary access</b> (Req 6.4, 6.5): when the event accesses a configured
 *       {@link CanaryToken}, the guard signals a short-circuit {@code BLOCK} and a high-risk
 *       alert.</li>
 * </ul>
 *
 * <p>The guard is self-contained: it composes with the chain only through the returned
 * contribution and does not touch the shared guardrail context, decision engine, or pipeline
 * provider. Matching is deterministic; the only mutable state is the per-session secret-access
 * timeline, which is keyed by session id.
 */
@Component
@ConditionalOnProperty(name = "intentguard.guardrails.exfiltration.enabled", havingValue = "true")
public class ExfiltrationCorrelator {

    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    /**
     * Environment-context keys, checked in order, that may carry the explicit outbound destination
     * of a Command_Event.
     */
    private static final List<String> DESTINATION_ENV_KEYS =
            List.of("destination", "egress_destination", "egressDestination", "remote", "host");

    /** Placeholder recorded when egress is detected but no destination could be determined. */
    static final String UNKNOWN_DESTINATION = "unknown";

    private final Clock clock;

    /** Most recent secret-access instant (epoch millis) per Intent_Session id. */
    private final Map<String, Long> lastSecretAccessMs = new ConcurrentHashMap<>();

    /** Spring constructor: uses the system UTC clock. */
    public ExfiltrationCorrelator() {
        this(Clock.systemUTC());
    }

    /** Test/DI constructor allowing an injected {@link Clock} for deterministic correlation. */
    public ExfiltrationCorrelator(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    /**
     * Evaluates the exfiltration guardrails for one Command_Event against the supplied
     * configuration, updating the per-session secret-access timeline as a side effect.
     *
     * @param event  the Command_Event under evaluation, must not be {@code null}
     * @param config the active exfiltration configuration, must not be {@code null}
     * @return the guardrail-facing {@link ExfiltrationContribution}
     */
    public ExfiltrationContribution evaluate(CommandEvent event, ExfiltrationConfig config) {
        Objects.requireNonNull(event, "event must not be null");
        Objects.requireNonNull(config, "config must not be null");

        long now = clock.millis();
        List<String> candidates = candidates(event);

        CorrectiveAction floor = CorrectiveAction.ALLOW;
        boolean correlatedAlert = false;
        boolean canaryHit = false;
        boolean highRiskAlert = false;
        Set<String> triggered = new LinkedHashSet<>();
        List<String> recordedDestinations = new ArrayList<>();

        // 1. Canary access is the highest-severity signal: short-circuit BLOCK + high-risk alert
        //    (Req 6.4, 6.5).
        for (CanaryToken canary : config.canaryTokens()) {
            if (matchesAny(canary.matcher(), candidates)) {
                canaryHit = true;
                highRiskAlert = true;
                floor = floor.raiseTo(CorrectiveAction.BLOCK);
                triggered.add(canary.id());
            }
        }

        boolean opensEgress = opensOutbound(event);

        // 2. Secret+egress correlation within the session and window (Req 6.2, 6.3). Evaluate before
        //    recording this event's own secret access so a single event does not self-correlate.
        if (opensEgress && event.sessionId() != null) {
            Long lastSecret = lastSecretAccessMs.get(event.sessionId());
            if (lastSecret != null && (now - lastSecret) <= config.correlationWindowMs()) {
                correlatedAlert = true;
                floor = floor.raiseTo(CorrectiveAction.ASK);
                triggered.add(ExfiltrationContribution.CORRELATED_EXFIL_TRIGGER_ID);
            }
        }

        // 3. Unapproved egress raises the floor to ASK and records the destination (Req 6.1).
        if (opensEgress) {
            List<String> destinations = destinationsFor(event);
            boolean anyUnapproved = false;
            for (String destination : destinations) {
                if (!config.isApprovedDestination(destination)) {
                    anyUnapproved = true;
                    recordedDestinations.add(destination);
                }
            }
            if (anyUnapproved) {
                floor = floor.raiseTo(CorrectiveAction.ASK);
                triggered.add(ExfiltrationContribution.UNAPPROVED_EGRESS_TRIGGER_ID);
            }
        }

        // 4. Record this event's secret access for future correlation (Req 6.2).
        if (accessesSecret(event, config) && event.sessionId() != null) {
            // Events are processed in observation order, so the latest access is the most recent.
            lastSecretAccessMs.put(event.sessionId(), now);
        }

        return new ExfiltrationContribution(
                floor,
                correlatedAlert,
                canaryHit,
                highRiskAlert,
                new ArrayList<>(triggered),
                recordedDestinations);
    }

    /** Clears the tracked secret-access timeline (test / lifecycle helper). */
    public void reset() {
        lastSecretAccessMs.clear();
    }

    // --- signal detection -----------------------------------------------------------------------

    private static boolean opensOutbound(CommandEvent event) {
        AgentRiskMarkers markers = event.agentRiskMarkers();
        return markers != null && markers.opensOutboundConnection();
    }

    private static boolean accessesSecret(CommandEvent event, ExfiltrationConfig config) {
        AgentRiskMarkers markers = event.agentRiskMarkers();
        if (markers != null && markers.accessesSecret()) {
            return true;
        }
        List<String> candidates = candidates(event);
        for (String matcher : config.secretFileMatchers()) {
            if (matchesAny(matcher, candidates)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Resolves the outbound destination(s) for an egress event: any explicit
     * environment-context destination first, otherwise host-like command tokens, otherwise the
     * {@link #UNKNOWN_DESTINATION} placeholder so unapproved egress is still recorded.
     */
    private static List<String> destinationsFor(CommandEvent event) {
        List<String> destinations = new ArrayList<>();
        Map<String, String> env = event.envContext();
        for (String key : DESTINATION_ENV_KEYS) {
            String value = env.get(key);
            if (value != null && !value.isBlank()) {
                destinations.add(value.trim());
            }
        }
        if (destinations.isEmpty()) {
            for (String token : tokenize(event.commandText())) {
                String host = extractHost(token);
                if (host != null && !host.isBlank()) {
                    destinations.add(host);
                }
            }
        }
        if (destinations.isEmpty()) {
            destinations.add(UNKNOWN_DESTINATION);
        }
        return destinations;
    }

    /** Candidate strings (working directory + command tokens) tested against path/canary globs. */
    private static List<String> candidates(CommandEvent event) {
        List<String> candidates = new ArrayList<>();
        if (event.cwd() != null && !event.cwd().isBlank()) {
            candidates.add(event.cwd());
        }
        candidates.addAll(tokenize(event.commandText()));
        return candidates;
    }

    private static boolean matchesAny(String matcher, List<String> candidates) {
        for (String candidate : candidates) {
            if (Globs.matches(matcher, candidate)) {
                return true;
            }
        }
        return false;
    }

    /** Extracts a host from a {@code scheme://host/...} or {@code user@host} token, or null. */
    private static String extractHost(String token) {
        int scheme = token.indexOf("://");
        if (scheme >= 0) {
            String rest = token.substring(scheme + 3);
            int slash = rest.indexOf('/');
            String authority = slash >= 0 ? rest.substring(0, slash) : rest;
            int at = authority.indexOf('@');
            return at >= 0 ? authority.substring(at + 1) : authority;
        }
        int at = token.indexOf('@');
        if (at >= 0) {
            return token.substring(at + 1);
        }
        return null;
    }

    private static List<String> tokenize(String text) {
        List<String> tokens = new ArrayList<>();
        if (text == null) {
            return tokens;
        }
        for (String raw : WHITESPACE.split(text.trim())) {
            if (raw.isEmpty()) {
                continue;
            }
            tokens.add(stripQuotes(raw));
        }
        return tokens;
    }

    private static String stripQuotes(String token) {
        if (token.length() >= 2) {
            char first = token.charAt(0);
            char last = token.charAt(token.length() - 1);
            if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
                return token.substring(1, token.length() - 1);
            }
        }
        return token;
    }
}
