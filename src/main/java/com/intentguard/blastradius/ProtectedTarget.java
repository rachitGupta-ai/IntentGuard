package com.intentguard.blastradius;

import java.util.Objects;

/**
 * A configured sensitive path, host, or resource that the {@code BlastRadiusGuard} watches
 * (Req 3.1, 3.2, 3.3, 3.4).
 *
 * <p>When a Command_Event reads from, writes to, or otherwise accesses a protected target, the
 * guard raises the Corrective_Action floor to at least {@code ASK}. When {@link #blockOnAccess()}
 * is {@code true}, any access instead short-circuits to a {@code BLOCK} (Req 3.3), which is how
 * canary paths and other never-touch resources are modeled.
 *
 * @param id           a stable, non-blank identifier recorded in the Audit_History and named in the
 *                     Explanation when this target triggers a decision (Req 3.7)
 * @param kind         whether this target is a path, host, or resource
 * @param matcher      the pattern (glob or resource selector) identifying the target, for example
 *                     {@code ~/.ssh/**} or {@code db:prod-*}
 * @param blockOnAccess when {@code true}, any access forces a {@code BLOCK} rather than a floor
 */
public record ProtectedTarget(String id, TargetKind kind, String matcher, boolean blockOnAccess) {

    public ProtectedTarget {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("ProtectedTarget id must be non-blank");
        }
        Objects.requireNonNull(kind, "ProtectedTarget kind must not be null");
        if (matcher == null || matcher.isBlank()) {
            throw new IllegalArgumentException("ProtectedTarget matcher must be non-blank: " + id);
        }
    }
}
