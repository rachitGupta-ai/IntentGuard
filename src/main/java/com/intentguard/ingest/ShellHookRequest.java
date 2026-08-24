package com.intentguard.ingest;

import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.intentguard.domain.Actor;
import com.intentguard.domain.ActorType;
import com.intentguard.domain.InputOrigin;
import com.intentguard.domain.RawShellSignal;

/**
 * Wire representation of a Shell_Hook request as received over the Unix domain socket.
 *
 * <p>This flat, hook-friendly shape (the shell integration serializes a single JSON object) is
 * decoded into the domain {@link RawShellSignal}. Unknown fields are ignored so the hook can add
 * metadata without breaking the engine.
 *
 * @param actorType        {@code HUMAN} or {@code AGENT}; defaults to {@code HUMAN} when absent
 * @param userId           the OS user / session identity the command was observed under
 * @param humanPrincipalId for an agent, the bounding human principal; otherwise {@code null}
 * @param commandText      the command text about to run
 * @param cwd              the working directory
 * @param envContext       selected environment context key/value pairs
 * @param timestamp        UTC epoch millis when the hook fired; defaults to now when absent
 * @param inputOrigin      typed-vs-pasted indicator, possibly {@code null} (recorded as UNKNOWN)
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ShellHookRequest(
        ActorType actorType,
        String userId,
        String humanPrincipalId,
        String commandText,
        String cwd,
        Map<String, String> envContext,
        Long timestamp,
        InputOrigin inputOrigin) {

    /** Convert this wire request into a domain {@link RawShellSignal}, applying safe defaults. */
    public RawShellSignal toDomain() {
        ActorType type = actorType == null ? ActorType.HUMAN : actorType;
        String user = userId == null ? "unknown" : userId;
        Actor actor =
                type == ActorType.AGENT
                        ? new Actor(ActorType.AGENT, user, humanPrincipalId)
                        : new Actor(ActorType.HUMAN, user, null);
        long ts = timestamp == null ? System.currentTimeMillis() : timestamp.longValue();
        return new RawShellSignal(
                actor,
                commandText == null ? "" : commandText,
                cwd,
                envContext,
                ts,
                inputOrigin);
    }
}
