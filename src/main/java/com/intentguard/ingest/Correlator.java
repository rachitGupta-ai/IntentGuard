package com.intentguard.ingest;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.springframework.stereotype.Component;

import com.intentguard.domain.CommandEvent;
import com.intentguard.domain.SignalSource;

/**
 * Correlates Shell_Hook records with Audit_Feed events (Req 2.3).
 *
 * <p>The blocking gate (Shell_Hook) and the detection feed (auditd) observe the same underlying
 * command from two sides. A hook record and an audit event describe the same command when they
 * share the same user identity <em>and</em> their timestamps fall within a configured correlation
 * window. The core predicate is a clean, deterministic pure function:
 *
 * <pre>correlated iff sameUser &amp;&amp; |t_hook - t_audit| &lt;= window</pre>
 *
 * <p>An Audit_Feed event with <em>no</em> matching hook record is flagged as a hook bypass: it
 * reached the post-execution feed without ever passing through the pre-execution gate
 * ({@code sourceOnly=AUDIT}). This is the signal that a non-interactive shell or an agent that did
 * not route through the hook executed a command the gate never had a chance to block.
 */
@Component
public class Correlator {

    /**
     * The correlation predicate (Req 2.3): a Shell_Hook record and an Audit_Feed event are
     * correlated if and only if they share the same user identity and their timestamps differ by
     * no more than {@code windowMs}.
     *
     * @param hookUserId  the user identity of the hook record
     * @param hookTs      the hook record timestamp (UTC epoch millis)
     * @param auditUserId the user identity of the audit event
     * @param auditTs     the audit event timestamp (UTC epoch millis)
     * @param windowMs    the inclusive correlation window in milliseconds (must be &gt;= 0)
     * @return {@code true} iff the two records correlate
     */
    public boolean correlates(
            String hookUserId, long hookTs, String auditUserId, long auditTs, long windowMs) {
        return Objects.equals(hookUserId, auditUserId) && Math.abs(hookTs - auditTs) <= windowMs;
    }

    /** Convenience overload operating directly on two {@link CommandEvent}s. */
    public boolean correlates(CommandEvent hookEvent, CommandEvent auditEvent, long windowMs) {
        Objects.requireNonNull(hookEvent, "hookEvent must not be null");
        Objects.requireNonNull(auditEvent, "auditEvent must not be null");
        return correlates(
                hookEvent.userId(),
                hookEvent.timestamp(),
                auditEvent.userId(),
                auditEvent.timestamp(),
                windowMs);
    }

    /**
     * Correlate a batch of Shell_Hook events with a batch of Audit_Feed events.
     *
     * <p>Each audit event is matched to the closest-in-time still-unmatched hook event for the same
     * user within the window. A matched pair yields a {@link CommandEvent} marked
     * {@link SignalSource#CORRELATED} (derived from the richer hook event). Any audit event without
     * a match is reported as a hook bypass, and any hook event without a match is reported
     * separately.
     *
     * @param hookEvents  normalized events from the Shell_Hook (never {@code null})
     * @param auditEvents normalized events from the Audit_Feed (never {@code null})
     * @param windowMs    the inclusive correlation window in milliseconds
     * @return the correlation outcome
     */
    public CorrelationResult correlate(
            List<CommandEvent> hookEvents, List<CommandEvent> auditEvents, long windowMs) {
        Objects.requireNonNull(hookEvents, "hookEvents must not be null");
        Objects.requireNonNull(auditEvents, "auditEvents must not be null");

        List<CommandEvent> availableHooks = new ArrayList<>(hookEvents);
        List<CorrelatedPair> correlated = new ArrayList<>();
        List<CommandEvent> hookBypasses = new ArrayList<>();

        for (CommandEvent audit : auditEvents) {
            CommandEvent bestHook = null;
            long bestDelta = Long.MAX_VALUE;
            for (CommandEvent hook : availableHooks) {
                if (!correlates(hook, audit, windowMs)) {
                    continue;
                }
                long delta = Math.abs(hook.timestamp() - audit.timestamp());
                if (delta < bestDelta) {
                    bestDelta = delta;
                    bestHook = hook;
                }
            }
            if (bestHook != null) {
                availableHooks.remove(bestHook);
                correlated.add(new CorrelatedPair(bestHook, audit, markCorrelated(bestHook)));
            } else {
                // No matching hook record: this audit-only event bypassed the blocking gate.
                hookBypasses.add(audit);
            }
        }

        return new CorrelationResult(correlated, hookBypasses, List.copyOf(availableHooks));
    }

    /**
     * Promote a hook event to {@link SignalSource#CORRELATED} once it has been matched to an
     * Audit_Feed event.
     */
    public CommandEvent markCorrelated(CommandEvent hookEvent) {
        Objects.requireNonNull(hookEvent, "hookEvent must not be null");
        if (hookEvent.signalSource() == SignalSource.CORRELATED) {
            return hookEvent;
        }
        return new CommandEvent(
                hookEvent.eventId(),
                hookEvent.actor(),
                hookEvent.sessionId(),
                hookEvent.commandText(),
                hookEvent.cwd(),
                hookEvent.repo(),
                hookEvent.envContext(),
                hookEvent.timestamp(),
                hookEvent.inputOrigin(),
                SignalSource.CORRELATED,
                hookEvent.intentSource(),
                hookEvent.agentRiskMarkers());
    }
}
