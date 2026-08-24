package com.intentguard.domain;

/**
 * Which signal source produced a {@code CommandEvent}.
 *
 * <ul>
 *   <li>{@code HOOK} - the pre-execution Shell_Hook (the real blocking gate).</li>
 *   <li>{@code AUDIT} - the post-execution Audit_Feed (auditd), detection only; an audit-only
 *       event indicates a bypass of the blocking gate.</li>
 *   <li>{@code CORRELATED} - a hook record and an audit event correlated to the same command by
 *       user identity and timestamp proximity (Req 2.3).</li>
 * </ul>
 */
public enum SignalSource {
    HOOK,
    AUDIT,
    CORRELATED
}
