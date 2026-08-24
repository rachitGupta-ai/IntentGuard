package com.intentguard.blastradius;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import com.intentguard.domain.CommandEvent;
import com.intentguard.domain.CorrectiveAction;

/**
 * Evaluates the blast-radius and protected-target guardrails for a single Command_Event (Req 3).
 *
 * <p>The guard contributes to the {@code GuardrailDecisionEngine} chain by producing a
 * {@link BlastRadiusResult} that carries, in order of increasing severity:
 * <ul>
 *   <li>a raised Corrective_Action floor (at least {@code ASK}) when the event reads/writes a
 *       protected path, targets a protected host/resource, or performs a mass operation whose
 *       estimated {@link BlastRadius} exceeds {@link GuardrailConfig#massOperationLimit()}
 *       (Req 3.2, 3.4, 3.5);</li>
 *   <li>a short-circuit {@code BLOCK} signal ({@link BlastRadiusResult#blockOnAccessHit()}) when a
 *       matched {@link ProtectedTarget} is configured block-on-access (Req 3.3);</li>
 *   <li>a raised Divergence_Score floor equal to
 *       {@link GuardrailConfig#destructiveOperationFloor()} when the command matches a configured
 *       destructive-verb pattern (Req 3.6);</li>
 *   <li>the fail-safe {@code ASK} floor with {@code indeterminate=true} when the event's impact or
 *       protected-target access cannot be determined (Req 3.8).</li>
 * </ul>
 *
 * <p>Every trigger records its identifier (matched {@link ProtectedTarget#id()}, a mass-operation
 * marker, a destructive-verb marker, or an indeterminate marker) in
 * {@link BlastRadiusResult#triggeredGuardrailIds()} for later audit and explanation (Req 3.7).
 *
 * <p>All matching is deterministic: the same event and configuration always yield the same result.
 */
@Component
public class BlastRadiusGuard {

    /** Trigger id recorded when a mass-operation-limit breach raises the floor (Req 3.5). */
    public static final String MASS_OPERATION_TRIGGER_ID = "mass-operation-limit";

    /** Trigger id recorded when a destructive-verb pattern raises the score floor (Req 3.6). */
    public static final String DESTRUCTIVE_VERB_TRIGGER_ID = "destructive-verb";

    /** Trigger id recorded when the blast radius / access cannot be determined (Req 3.8). */
    public static final String INDETERMINATE_TRIGGER_ID = "blast-radius-indeterminate";

    /**
     * Estimated affected-item count used for a recognized mass operation (recursive delete,
     * wildcard/glob expansion, or an unbounded bulk SQL statement). It is deliberately larger than
     * any configurable {@link GuardrailConfig#massOperationLimit()} so such operations always breach
     * the limit (Req 3.5).
     */
    static final int MASS_OPERATION_AFFECTED_COUNT = Integer.MAX_VALUE;

    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    private static final Pattern SQL_WHERE = Pattern.compile("\\bwhere\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern SQL_BULK_DELETE =
            Pattern.compile("\\bdelete\\s+from\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern SQL_BULK_UPDATE =
            Pattern.compile("\\bupdate\\b.*\\bset\\b", Pattern.CASE_INSENSITIVE);

    /**
     * Evaluates the protected-target, mass-operation, and destructive-verb guardrails for one
     * Command_Event.
     *
     * <p>Fail-safe: if the {@link BlastRadius} or protected-target access cannot be determined the
     * result raises the floor to at least {@code ASK} and flags {@code indeterminate} (Req 3.8).
     *
     * @param event the Command_Event under evaluation, must not be {@code null}
     * @param cfg   the active guardrail configuration, must not be {@code null}
     * @return the guardrail-facing {@link BlastRadiusResult}
     */
    public BlastRadiusResult evaluate(CommandEvent event, GuardrailConfig cfg) {
        Objects.requireNonNull(event, "event must not be null");
        Objects.requireNonNull(cfg, "cfg must not be null");

        CorrectiveAction floor = CorrectiveAction.ALLOW;
        boolean blockOnAccessHit = false;
        boolean indeterminate = false;
        Set<String> triggered = new LinkedHashSet<>();

        // 1. Protected targets: raise the floor to ASK on access, or short-circuit BLOCK when the
        //    matched target is block-on-access (Req 3.2, 3.3, 3.4).
        for (ProtectedTarget target : cfg.protectedTargets()) {
            Boolean access = accessesTarget(event, target);
            if (access == null) {
                // Cannot determine whether the event accesses this protected target: fail safe.
                indeterminate = true;
                floor = floor.raiseTo(CorrectiveAction.ASK);
                triggered.add(INDETERMINATE_TRIGGER_ID);
            } else if (access) {
                triggered.add(target.id());
                if (target.blockOnAccess()) {
                    blockOnAccessHit = true;
                } else {
                    floor = floor.raiseTo(CorrectiveAction.ASK);
                }
            }
        }

        // 2. Mass-operation limit / indeterminate blast radius (Req 3.5, 3.8).
        BlastRadius radius = estimate(event);
        if (radius.indeterminate()) {
            indeterminate = true;
            floor = floor.raiseTo(CorrectiveAction.ASK);
            triggered.add(INDETERMINATE_TRIGGER_ID);
        } else if (radius.affectedCount() > cfg.massOperationLimit()) {
            floor = floor.raiseTo(CorrectiveAction.ASK);
            triggered.add(MASS_OPERATION_TRIGGER_ID);
        }

        // 3. Destructive-verb detection raises the Divergence_Score floor (Req 3.6).
        OptionalDouble scoreFloor = OptionalDouble.empty();
        if (matchesDestructiveVerb(event, cfg)) {
            scoreFloor = OptionalDouble.of(cfg.destructiveOperationFloor());
            triggered.add(DESTRUCTIVE_VERB_TRIGGER_ID);
        }

        return new BlastRadiusResult(
                floor, blockOnAccessHit, scoreFloor, indeterminate, new ArrayList<>(triggered));
    }

    /**
     * Estimates the impact of a single Command_Event from its command text using deterministic
     * heuristics (Req 3.5):
     * <ul>
     *   <li>an unbounded bulk SQL statement ({@code UPDATE ... SET} / {@code DELETE FROM} with no
     *       {@code WHERE} clause) affects an unbounded number of rows;</li>
     *   <li>a recursive flag ({@code -r}, {@code -R}, {@code --recursive}) affects an unbounded
     *       subtree;</li>
     *   <li>a wildcard / glob operand ({@code *}, {@code ?}, {@code [}, {@code ]}) affects an
     *       unbounded match set;</li>
     *   <li>any other recognizable command is treated as a single, bounded operation;</li>
     *   <li>a blank / unparseable command yields {@link BlastRadius#unknown()} so the guard fails
     *       safe (Req 3.8).</li>
     * </ul>
     *
     * @param event the Command_Event under evaluation, must not be {@code null}
     * @return the estimated {@link BlastRadius}, or {@link BlastRadius#unknown()} when it cannot be
     *         determined
     */
    public BlastRadius estimate(CommandEvent event) {
        Objects.requireNonNull(event, "event must not be null");
        String text = event.commandText().trim();
        if (text.isEmpty()) {
            return BlastRadius.unknown();
        }

        // Bulk SQL without a WHERE clause affects an unbounded row set (checked before flag/glob
        // heuristics since SQL does not use shell-style flags).
        if (isBulkSqlWithoutWhere(text)) {
            return new BlastRadius(MASS_OPERATION_AFFECTED_COUNT, false);
        }

        List<String> tokens = tokenize(text);
        if (hasRecursiveFlag(tokens)) {
            return new BlastRadius(MASS_OPERATION_AFFECTED_COUNT, false);
        }
        if (hasWildcard(text)) {
            return new BlastRadius(MASS_OPERATION_AFFECTED_COUNT, false);
        }

        // A recognizable, non-mass command is treated as a single bounded operation.
        return new BlastRadius(1, false);
    }

    // --- protected-target matching --------------------------------------------------------------

    /**
     * Determines whether the event accesses the given protected target.
     *
     * @return {@code Boolean.TRUE} / {@code Boolean.FALSE} when access could be determined, or
     *         {@code null} when it could not (drives the fail-safe path, Req 3.8)
     */
    private Boolean accessesTarget(CommandEvent event, ProtectedTarget target) {
        Pattern matcher;
        try {
            matcher = globToRegex(target.matcher());
        } catch (RuntimeException ex) {
            // A malformed matcher means we cannot determine access: fail safe.
            return null;
        }
        for (String candidate : candidatesFor(event, target.kind())) {
            if (matcher.matcher(candidate).matches()) {
                return Boolean.TRUE;
            }
        }
        return Boolean.FALSE;
    }

    /** Builds the deterministic set of candidate strings a target matcher is tested against. */
    private List<String> candidatesFor(CommandEvent event, TargetKind kind) {
        List<String> tokens = tokenize(event.commandText());
        List<String> candidates = new ArrayList<>();
        switch (kind) {
            case PATH -> {
                if (event.cwd() != null && !event.cwd().isBlank()) {
                    candidates.add(event.cwd());
                }
                candidates.addAll(tokens);
            }
            case HOST -> {
                for (String token : tokens) {
                    candidates.add(token);
                    String host = extractHost(token);
                    if (host != null) {
                        candidates.add(host);
                    }
                }
            }
            case RESOURCE -> candidates.addAll(tokens);
        }
        return candidates;
    }

    /** Extracts a host from a {@code user@host} or {@code scheme://host/...} token, or null. */
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

    // --- destructive-verb matching --------------------------------------------------------------

    private static boolean matchesDestructiveVerb(CommandEvent event, GuardrailConfig cfg) {
        List<String> patterns = cfg.destructiveVerbPatterns();
        if (patterns.isEmpty()) {
            return false;
        }
        String normalized = normalizeWhitespace(event.commandText()).toLowerCase(Locale.ROOT);
        for (String pattern : patterns) {
            String normalizedPattern = normalizeWhitespace(pattern).toLowerCase(Locale.ROOT);
            if (!normalizedPattern.isEmpty() && normalized.contains(normalizedPattern)) {
                return true;
            }
        }
        return false;
    }

    // --- blast-radius heuristics ----------------------------------------------------------------

    private static boolean isBulkSqlWithoutWhere(String text) {
        if (SQL_WHERE.matcher(text).find()) {
            return false;
        }
        return SQL_BULK_DELETE.matcher(text).find() || SQL_BULK_UPDATE.matcher(text).find();
    }

    private static boolean hasRecursiveFlag(List<String> tokens) {
        for (String token : tokens) {
            if (token.equals("--recursive")) {
                return true;
            }
            if (token.length() > 1 && token.charAt(0) == '-' && token.charAt(1) != '-') {
                String flags = token.substring(1);
                if (flags.indexOf('r') >= 0 || flags.indexOf('R') >= 0) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean hasWildcard(String text) {
        return text.indexOf('*') >= 0
                || text.indexOf('?') >= 0
                || text.indexOf('[') >= 0
                || text.indexOf(']') >= 0;
    }

    // --- shared helpers -------------------------------------------------------------------------

    private static List<String> tokenize(String text) {
        List<String> tokens = new ArrayList<>();
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

    private static String normalizeWhitespace(String text) {
        return WHITESPACE.matcher(text.trim()).replaceAll(" ");
    }

    /**
     * Compiles a glob matcher into an anchored regular expression. Supports {@code **} (any run,
     * including path separators), {@code *} (any run except {@code /}), {@code ?} (any single
     * character except {@code /}), and {@code [...]} character classes (with a leading {@code !}
     * treated as negation). All other characters are matched literally.
     */
    static Pattern globToRegex(String glob) {
        StringBuilder sb = new StringBuilder("^");
        int i = 0;
        int n = glob.length();
        while (i < n) {
            char c = glob.charAt(i);
            switch (c) {
                case '*' -> {
                    if (i + 1 < n && glob.charAt(i + 1) == '*') {
                        sb.append(".*");
                        i++;
                    } else {
                        sb.append("[^/]*");
                    }
                }
                case '?' -> sb.append("[^/]");
                case '[' -> {
                    int close = glob.indexOf(']', i + 1);
                    if (close < 0) {
                        // Unterminated class: treat '[' literally.
                        sb.append("\\[");
                    } else {
                        sb.append('[');
                        int start = i + 1;
                        if (start < close && glob.charAt(start) == '!') {
                            sb.append('^');
                            start++;
                        }
                        sb.append(glob, start, close);
                        sb.append(']');
                        i = close;
                    }
                }
                default -> {
                    if ("\\.^$+{}()|".indexOf(c) >= 0) {
                        sb.append('\\');
                    }
                    sb.append(c);
                }
            }
            i++;
        }
        sb.append('$');
        return Pattern.compile(sb.toString());
    }
}
