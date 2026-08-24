package com.intentguard.scenario;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;

import com.intentguard.domain.CommandEvent;
import com.intentguard.domain.Decision;
import com.intentguard.domain.DivergenceResult;
import com.intentguard.llm.LlmService;

/**
 * A fully deterministic, network-free {@link LlmService} used to make scenario replays reproducible
 * (Req 16.2, the basis for Property 20).
 *
 * <p>Unlike the production Gemini-backed adapter, this stub never performs I/O, reads no wall-clock
 * time, and uses no randomness. Its Semantic_Inconsistency score for a Command_Event is a pure
 * function of the command text: an exact per-command override when one has been configured,
 * otherwise a fixed default. Because the mapping is total and deterministic, replaying the same
 * scripted scenario twice yields identical semantic scores and therefore identical decisions.
 *
 * <h2>Semantic scoring</h2>
 * <p>{@link #semanticInconsistency(CommandEvent, String)} mirrors the {@link LlmService} contract:
 * when no intent text is available it returns {@link OptionalDouble#empty()} (so the
 * Semantic_Inconsistency component excludes itself and the pipeline renormalizes); otherwise it
 * returns the configured score for the command text (or the default), clamped to {@code [0,1]}.
 *
 * <h2>Explanation</h2>
 * <p>{@link #explain(CommandEvent, DivergenceResult, Decision)} returns a fixed string when one has
 * been configured, and otherwise {@link Optional#empty()} so the caller falls back to the
 * deterministic, component-derived explanation template (which names the top contributing
 * components and states a pasted origin). Both paths are reproducible.
 */
public final class DeterministicLlmStub implements LlmService {

    /** Default Semantic_Inconsistency score returned when no per-command override is configured. */
    public static final double DEFAULT_SEMANTIC_SCORE = 0.5;

    private final double defaultSemanticScore;
    private final Map<String, Double> scoresByCommand;
    private final String fixedExplanation;

    /** A stub returning {@link #DEFAULT_SEMANTIC_SCORE} for every command and no fixed explanation. */
    public DeterministicLlmStub() {
        this(DEFAULT_SEMANTIC_SCORE);
    }

    /** A stub returning {@code defaultSemanticScore} for every command and no fixed explanation. */
    public DeterministicLlmStub(double defaultSemanticScore) {
        this(defaultSemanticScore, Map.of(), null);
    }

    /**
     * Full constructor.
     *
     * @param defaultSemanticScore the score returned for a command with no explicit override
     * @param scoresByCommand      exact command-text to score overrides (copied defensively)
     * @param fixedExplanation     a fixed explanation to return, or {@code null} to defer to the
     *                             deterministic template
     */
    public DeterministicLlmStub(
            double defaultSemanticScore, Map<String, Double> scoresByCommand, String fixedExplanation) {
        this.defaultSemanticScore = clampUnit(defaultSemanticScore);
        this.scoresByCommand = scoresByCommand == null
                ? Map.of()
                : new LinkedHashMap<>(scoresByCommand);
        this.fixedExplanation = fixedExplanation;
    }

    /**
     * Returns a copy of this stub with an exact per-command Semantic_Inconsistency override added,
     * enabling scenarios to script the semantic score of a specific command deterministically.
     */
    public DeterministicLlmStub withCommandScore(String commandText, double score) {
        Objects.requireNonNull(commandText, "commandText must not be null");
        Map<String, Double> merged = new LinkedHashMap<>(this.scoresByCommand);
        merged.put(commandText, clampUnit(score));
        return new DeterministicLlmStub(this.defaultSemanticScore, merged, this.fixedExplanation);
    }

    /** Returns a copy of this stub that always returns {@code explanation} from {@link #explain}. */
    public DeterministicLlmStub withFixedExplanation(String explanation) {
        return new DeterministicLlmStub(this.defaultSemanticScore, this.scoresByCommand, explanation);
    }

    @Override
    public OptionalDouble semanticInconsistency(CommandEvent event, String intentText) {
        Objects.requireNonNull(event, "event must not be null");
        // Contract: no intent to score against -> empty, so the component excludes and renormalizes.
        if (intentText == null || intentText.isBlank()) {
            return OptionalDouble.empty();
        }
        double score = scoresByCommand.getOrDefault(event.commandText(), defaultSemanticScore);
        return OptionalDouble.of(clampUnit(score));
    }

    @Override
    public Optional<String> explain(CommandEvent event, DivergenceResult result, Decision decision) {
        Objects.requireNonNull(event, "event must not be null");
        Objects.requireNonNull(result, "result must not be null");
        Objects.requireNonNull(decision, "decision must not be null");
        return Optional.ofNullable(fixedExplanation);
    }

    private static double clampUnit(double value) {
        if (Double.isNaN(value)) {
            return 1.0;
        }
        if (value < 0.0) {
            return 0.0;
        }
        return Math.min(value, 1.0);
    }
}
