package com.intentguard.domain;

import java.util.Map;
import java.util.Objects;

/**
 * A raw request received synchronously from the Shell_Hook over the Unix domain socket, before
 * normalization into a {@link CommandEvent}. This is the pre-execution blocking-gate path
 * (Req 2.2).
 *
 * <p>The {@code inputOrigin} may be {@code null} when the hook could not determine a
 * typed-vs-pasted indicator; the ingestor records it as {@code UNKNOWN} (Req 2.4).
 *
 * @param actor       the originating actor (human or agent)
 * @param commandText the command text about to be executed
 * @param cwd         the working directory
 * @param envContext  selected environment context key/value pairs
 * @param timestamp   UTC epoch millis when the hook fired
 * @param inputOrigin typed-vs-pasted indicator, possibly {@code null}
 */
public record RawShellSignal(
        Actor actor,
        String commandText,
        String cwd,
        Map<String, String> envContext,
        long timestamp,
        InputOrigin inputOrigin,
        AgentRiskMarkers agentRiskMarkers) {

    public RawShellSignal {
        Objects.requireNonNull(actor, "actor must not be null");
        Objects.requireNonNull(commandText, "commandText must not be null");
        envContext = envContext == null ? Map.of() : Map.copyOf(envContext);
        agentRiskMarkers = agentRiskMarkers == null ? AgentRiskMarkers.none() : agentRiskMarkers;
    }

    /**
     * Backward-compatible constructor without agent risk markers (defaults to none). Retained so
     * existing callers/tests that predate marker support keep compiling.
     */
    public RawShellSignal(
            Actor actor,
            String commandText,
            String cwd,
            Map<String, String> envContext,
            long timestamp,
            InputOrigin inputOrigin) {
        this(actor, commandText, cwd, envContext, timestamp, inputOrigin, AgentRiskMarkers.none());
    }
}
