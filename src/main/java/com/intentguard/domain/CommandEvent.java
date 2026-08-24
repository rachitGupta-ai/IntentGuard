package com.intentguard.domain;

import java.util.Map;
import java.util.Objects;

/**
 * A single normalized observed action, produced by the Signal_Ingestor from a Shell_Hook signal
 * and/or an Audit_Feed event (Req 2.2). This is the central unit scored by the pipeline and
 * embedded in the Audit_History record.
 *
 * @param eventId      unique identifier for this event
 * @param actor        the originating {@link Actor} (human or agent)
 * @param sessionId    the associated Intent_Session id, or {@code null} if none was open
 * @param commandText  the observed command text
 * @param cwd          the working directory the command was issued from
 * @param repo         the repository the cwd belongs to, or {@code null} if not in a repo
 * @param envContext   selected environment context key/value pairs (never {@code null})
 * @param timestamp    UTC epoch millis when the event occurred
 * @param inputOrigin  typed-vs-pasted indicator; {@code UNKNOWN} when not provided (Req 2.4)
 * @param signalSource which source produced the event (hook, audit, or correlated)
 * @param intentSource provenance of the intent this event is scored against
 * @param agentRiskMarkers agent-related risk flags observed for this event (Req 13.5)
 */
public record CommandEvent(
        String eventId,
        Actor actor,
        String sessionId,
        String commandText,
        String cwd,
        String repo,
        Map<String, String> envContext,
        long timestamp,
        InputOrigin inputOrigin,
        SignalSource signalSource,
        IntentSource intentSource,
        AgentRiskMarkers agentRiskMarkers) {

    public CommandEvent {
        Objects.requireNonNull(eventId, "eventId must not be null");
        Objects.requireNonNull(actor, "actor must not be null");
        Objects.requireNonNull(commandText, "commandText must not be null");
        // A missing typed-vs-pasted indicator is recorded as UNKNOWN and processing continues.
        inputOrigin = inputOrigin == null ? InputOrigin.UNKNOWN : inputOrigin;
        signalSource = signalSource == null ? SignalSource.HOOK : signalSource;
        intentSource = intentSource == null ? IntentSource.NONE : intentSource;
        envContext = envContext == null ? Map.of() : Map.copyOf(envContext);
        agentRiskMarkers = agentRiskMarkers == null ? AgentRiskMarkers.none() : agentRiskMarkers;
    }

    public ActorType actorType() {
        return actor.type();
    }

    public String userId() {
        return actor.userId();
    }

    public boolean hasIntent() {
        return intentSource != IntentSource.NONE;
    }

    public boolean isPasted() {
        return inputOrigin == InputOrigin.PASTED;
    }
}
