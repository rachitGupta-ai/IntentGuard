package com.intentguard.exfil;

import java.util.List;
import java.util.Objects;

/**
 * Configuration for the data-exfiltration guardrails (Req 6). All matchers are glob patterns
 * evaluated deterministically over the Command_Event's tokens, working directory, and declared
 * egress destination.
 *
 * @param approvedDestinations glob patterns for outbound destinations that are permitted without a
 *                             raised floor (Req 6.1); a destination matching none of these is
 *                             treated as unapproved egress
 * @param secretFileMatchers   glob patterns identifying credential/secret files whose access is
 *                             tracked per session for secret-then-egress correlation (Req 6.2)
 * @param canaryTokens         planted canary tokens whose access forces a {@code BLOCK} (Req 6.4)
 * @param correlationWindowMs  the window, in milliseconds, within which a secret access followed by
 *                             an outbound connection in the same session raises a correlated-exfil
 *                             alert (Req 6.2, 6.3); must be non-negative
 */
public record ExfiltrationConfig(
        List<String> approvedDestinations,
        List<String> secretFileMatchers,
        List<CanaryToken> canaryTokens,
        long correlationWindowMs) {

    public ExfiltrationConfig {
        approvedDestinations = approvedDestinations == null ? List.of() : List.copyOf(approvedDestinations);
        secretFileMatchers = secretFileMatchers == null ? List.of() : List.copyOf(secretFileMatchers);
        canaryTokens = canaryTokens == null ? List.of() : List.copyOf(canaryTokens);
        if (correlationWindowMs < 0) {
            throw new IllegalArgumentException("correlationWindowMs must be non-negative");
        }
    }

    /**
     * A conservative default configuration: no approved destinations (so any egress is unapproved),
     * no secret matchers or canaries, and a five-minute correlation window.
     */
    public static ExfiltrationConfig defaults() {
        return new ExfiltrationConfig(List.of(), List.of(), List.of(), 300_000L);
    }

    /** Returns {@code true} when {@code destination} matches an approved-destination glob. */
    public boolean isApprovedDestination(String destination) {
        Objects.requireNonNull(destination, "destination must not be null");
        for (String approved : approvedDestinations) {
            if (Globs.matches(approved, destination)) {
                return true;
            }
        }
        return false;
    }
}
