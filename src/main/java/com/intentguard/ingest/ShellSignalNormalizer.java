package com.intentguard.ingest;

import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.intentguard.domain.CommandEvent;
import com.intentguard.domain.InputOrigin;
import com.intentguard.domain.IntentSource;
import com.intentguard.domain.RawShellSignal;
import com.intentguard.domain.SignalSource;

/**
 * Field-preserving normalization of a raw Shell_Hook signal into a {@link CommandEvent}
 * (Req 2.2, 2.4).
 *
 * <p>This is the pre-execution blocking-gate normalization step. It converts a
 * {@link RawShellSignal} received on the Unix domain socket into the normalized
 * {@code CommandEvent} the scoring pipeline consumes, preserving every field the hook provided:
 * actor, command text, working directory, environment context, timestamp, and the typed-vs-pasted
 * indicator.
 *
 * <p>A missing typed-vs-pasted indicator ({@code null}) is recorded as {@link InputOrigin#UNKNOWN}
 * and processing continues (Req 2.4) rather than rejecting the signal.
 *
 * <p>Fields that are resolved later in the pipeline are intentionally left unset here:
 * {@code sessionId} and {@code repo} are {@code null} and {@code intentSource} is
 * {@link IntentSource#NONE}; intent association (Task 13.1) resolves them. The
 * {@link SignalSource} is always {@link SignalSource#HOOK} because this path is the
 * synchronous hook gate. No agent risk markers are inferred at normalization time; they are
 * assigned by the scoring pipeline.
 *
 * <p>This normalizer is additive: it does not alter the existing socket/ingestor flow. The
 * full pipeline wiring (Task 13.1) is responsible for feeding the produced {@code CommandEvent}
 * into scoring.
 */
@Component
public class ShellSignalNormalizer {

    private static final Logger log = LoggerFactory.getLogger(ShellSignalNormalizer.class);

    private final Supplier<String> eventIdGenerator;

    /** Production constructor: generates random UUID event ids. */
    public ShellSignalNormalizer() {
        this(() -> UUID.randomUUID().toString());
    }

    /**
     * Constructor allowing a deterministic event-id generator (used in tests).
     *
     * @param eventIdGenerator supplier of non-null, unique event ids
     */
    public ShellSignalNormalizer(Supplier<String> eventIdGenerator) {
        this.eventIdGenerator = Objects.requireNonNull(eventIdGenerator, "eventIdGenerator must not be null");
    }

    /**
     * Normalize a Shell_Hook signal into a {@link CommandEvent}, preserving all provided fields.
     *
     * @param signal the raw shell signal received from the hook (never {@code null})
     * @return a normalized {@code CommandEvent} with {@code signalSource=HOOK} and a generated
     *     event id; a missing typed-vs-pasted indicator is recorded as {@link InputOrigin#UNKNOWN}
     */
    public CommandEvent normalize(RawShellSignal signal) {
        Objects.requireNonNull(signal, "signal must not be null");

        InputOrigin inputOrigin = signal.inputOrigin();
        if (inputOrigin == null) {
            // Req 2.4: a Command_Event without a typed-vs-pasted indicator records it as UNKNOWN
            // and processing continues.
            inputOrigin = InputOrigin.UNKNOWN;
            log.debug(
                    "Shell_Hook signal for user '{}' has no typed-vs-pasted indicator; recording UNKNOWN",
                    signal.actor().userId());
        }

        String eventId = eventIdGenerator.get();
        Objects.requireNonNull(eventId, "generated eventId must not be null");

        return new CommandEvent(
                eventId,
                signal.actor(),
                null, // sessionId resolved later by intent association (Task 13.1)
                signal.commandText(),
                signal.cwd(),
                null, // repo resolved later
                signal.envContext(), // defensively copied by CommandEvent's canonical constructor
                signal.timestamp(),
                inputOrigin,
                SignalSource.HOOK,
                IntentSource.NONE, // intent source resolved later by intent association
                signal.agentRiskMarkers());
    }
}
