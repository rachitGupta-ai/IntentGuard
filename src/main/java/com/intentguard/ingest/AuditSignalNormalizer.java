package com.intentguard.ingest;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

import org.springframework.stereotype.Component;

import com.intentguard.domain.Actor;
import com.intentguard.domain.AgentRiskMarkers;
import com.intentguard.domain.CommandEvent;
import com.intentguard.domain.InputOrigin;
import com.intentguard.domain.IntentSource;
import com.intentguard.domain.RawAuditEvent;
import com.intentguard.domain.SignalSource;

/**
 * Field-preserving normalization of a raw Audit_Feed record into a {@link CommandEvent}
 * (Req 2.1, 2.5).
 *
 * <p>This is the post-execution detection path: auditd cannot block, it only observes actions the
 * kernel has already performed. An Audit_Feed event is normalized with
 * {@link SignalSource#AUDIT}; if it is later matched to a Shell_Hook record it is promoted to
 * {@link SignalSource#CORRELATED} by the {@link Correlator}. An audit-only event that never
 * matches a hook record indicates a bypass of the blocking gate.
 *
 * <p>The auditd stream carries no typed-vs-pasted indicator, so {@link InputOrigin#UNKNOWN} is
 * recorded (Req 2.4). Intent association and repository resolution happen later in the pipeline;
 * this step only preserves the raw fields (user identity, timestamp, command/exe, cwd, and — for a
 * file-write — the affected path).
 */
@Component
public class AuditSignalNormalizer {

    private final Supplier<String> eventIdGenerator;

    /** Production constructor: generates random UUID event ids. */
    public AuditSignalNormalizer() {
        this(() -> UUID.randomUUID().toString());
    }

    /**
     * Constructor allowing a deterministic event-id generator (used in tests).
     *
     * @param eventIdGenerator supplier of non-null, unique event ids
     */
    public AuditSignalNormalizer(Supplier<String> eventIdGenerator) {
        this.eventIdGenerator = Objects.requireNonNull(eventIdGenerator, "eventIdGenerator must not be null");
    }

    /**
     * Normalize an Audit_Feed record into a {@link CommandEvent} with {@code signalSource=AUDIT},
     * preserving all provided fields.
     *
     * @param event the raw audit event parsed from the auditd stream (never {@code null})
     * @return a normalized {@code CommandEvent}
     */
    public CommandEvent normalize(RawAuditEvent event) {
        Objects.requireNonNull(event, "event must not be null");

        String eventId = eventIdGenerator.get();
        Objects.requireNonNull(eventId, "generated eventId must not be null");

        Map<String, String> envContext = new LinkedHashMap<>();
        envContext.put("auditType", event.type().name());
        if (event.path() != null) {
            envContext.put("auditPath", event.path());
        }

        // A file-write record has no command text; the affected path is the observed action.
        String commandText = event.commandText();
        if (commandText == null || commandText.isBlank()) {
            commandText = event.path() != null ? event.path() : "";
        }

        return new CommandEvent(
                eventId,
                Actor.human(event.userId()),
                null, // sessionId resolved later by intent association
                commandText,
                event.cwd(),
                null, // repo resolved later
                envContext,
                event.timestamp(),
                InputOrigin.UNKNOWN, // auditd carries no typed-vs-pasted indicator (Req 2.4)
                SignalSource.AUDIT,
                IntentSource.NONE,
                AgentRiskMarkers.none());
    }
}
