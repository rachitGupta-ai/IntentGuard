package com.intentguard.llm;

import java.util.Map;
import java.util.OptionalDouble;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

import com.intentguard.domain.Actor;
import com.intentguard.domain.CommandEvent;
import com.intentguard.domain.ComponentId;
import com.intentguard.domain.ComponentResult;
import com.intentguard.domain.InputOrigin;
import com.intentguard.domain.IntentSource;
import com.intentguard.domain.ProfileState;
import com.intentguard.domain.ScoringConfig;
import com.intentguard.domain.ScoringContext;
import com.intentguard.domain.SignalSource;
import com.intentguard.scoring.SemanticInconsistencyComponent;

/**
 * Fallback-within-budget tests (Req 5.8, 6.3). A slow LLM stub sleeps far beyond the configured
 * per-call timeout; the tight timeout guarantees {@link GeminiLlmService#semanticInconsistency}
 * returns {@link OptionalDouble#empty()} promptly — well under the 2-second decision budget — and
 * the {@link SemanticInconsistencyComponent} maps that empty result to an <em>excluded</em>
 * {@link ComponentResult} so the pipeline can renormalize and still decide within budget.
 */
class SemanticFallbackWithinBudgetTest {

    /** Per-call LLM timeout used for the test: tight, so a slow model excludes quickly. */
    private static final long LLM_TIMEOUT_MS = 150;

    /** How long a slow model call sleeps — much longer than the timeout and the decision budget. */
    private static final long SLOW_CALL_MS = 5_000;

    /** Upper bound the fallback must beat: comfortably under the 2s decision budget (Req 5.8). */
    private static final long BUDGET_BOUND_MS = 1_500;

    private static LlmProperties tightTimeoutProps() {
        LlmProperties p = new LlmProperties();
        p.setApiKey("");
        p.setModel("gemini-test");
        p.setTimeoutMs(LLM_TIMEOUT_MS);
        return p;
    }

    private static GeminiTextGenerator slowGenerator() {
        return prompt -> {
            Thread.sleep(SLOW_CALL_MS);
            return "{\"semantic_inconsistency\": 0.9}";
        };
    }

    private static CommandEvent event() {
        return new CommandEvent(
                "evt-slow-1",
                Actor.human("alice"),
                "sess-1",
                "kubectl delete deployment payments",
                "/home/alice/infra",
                "infra",
                Map.of(),
                1_710_000_000_000L,
                InputOrigin.TYPED,
                SignalSource.HOOK,
                IntentSource.DECLARED,
                null);
    }

    private static ScoringContext contextWithIntent(CommandEvent event) {
        ScoringConfig config = new ScoringConfig(
                Map.of(
                        ComponentId.SEQUENCE_SURPRISE, 0.25,
                        ComponentId.CONTEXT_MISMATCH, 0.20,
                        ComponentId.BEHAVIORAL_DEVIATION, 0.25,
                        ComponentId.SEMANTIC_INCONSISTENCY, 0.30),
                0.15);
        return new ScoringContext(event, "scale down the staging cluster", IntentSource.DECLARED,
                ProfileState.ACTIVE, config);
    }

    @Test
    void slowLlmYieldsEmptyScoreWellWithinBudget() {
        GeminiLlmService service = new GeminiLlmService(tightTimeoutProps(), slowGenerator());

        long start = System.nanoTime();
        OptionalDouble score = service.semanticInconsistency(event(), "scale down the staging cluster");
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertFalse(score.isPresent(), "a slow LLM must be excluded rather than waited on");
        assertTrue(elapsedMs < BUDGET_BOUND_MS,
                "fallback must complete within the decision budget, took " + elapsedMs + "ms");
    }

    @Test
    void slowLlmDecidesViaExclusionInComponentWithinBudget() {
        GeminiLlmService service = new GeminiLlmService(tightTimeoutProps(), slowGenerator());
        SemanticInconsistencyComponent component = new SemanticInconsistencyComponent(service);

        long start = System.nanoTime();
        ComponentResult result = component.score(contextWithIntent(event()));
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        // The tight LLM timeout guarantees a decision via exclusion, so the pipeline can renormalize.
        assertTrue(result.isExcluded(), "slow LLM must make the semantic component excluded");
        assertEquals(ComponentId.SEMANTIC_INCONSISTENCY, result.id());
        assertEquals("llm_unavailable", result.note(), "exclusion reason should be recorded");
        assertTrue(elapsedMs < BUDGET_BOUND_MS,
                "component must reach a decision within the budget, took " + elapsedMs + "ms");
    }
}
