package com.intentguard.exfil;

/**
 * A planted secret that has no legitimate use and whose access indicates compromise (Req 6.4, 6.5).
 *
 * <p>When a Command_Event accesses a configured canary token, the {@link ExfiltrationCorrelator}
 * short-circuits to a {@code BLOCK} Corrective_Action and raises a high-risk alert.
 *
 * @param id      a stable, non-blank identifier recorded in the Audit_History and named in the
 *                Explanation when this token triggers a decision
 * @param matcher the glob pattern (over command tokens and the working directory) identifying the
 *                canary, for example {@code /opt/creds/aws.canary}
 */
public record CanaryToken(String id, String matcher) {

    public CanaryToken {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("CanaryToken id must be non-blank");
        }
        if (matcher == null || matcher.isBlank()) {
            throw new IllegalArgumentException("CanaryToken matcher must be non-blank: " + id);
        }
    }
}
