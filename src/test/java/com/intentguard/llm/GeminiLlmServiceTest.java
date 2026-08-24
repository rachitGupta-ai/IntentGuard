package com.intentguard.llm;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

import com.intentguard.domain.Actor;
import com.intentguard.domain.CommandEvent;
import com.intentguard.domain.ComponentId;
import com.intentguard.domain.ComponentResult;
import com.intentguard.domain.CorrectiveAction;
import com.intentguard.domain.Decision;
import com.intentguard.domain.DivergenceResult;
import com.intentguard.domain.InputOrigin;
import com.intentguard.domain.IntentSource;
import com.intentguard.domain.SignalSource;

/**
 * Tests for {@link GeminiLlmService} that isolate the SDK behind an injected
 * {@link GeminiTextGenerator}: valid output parses and clamps, errors and timeouts degrade to
 * empty, a slow call still yields a decision within budget via exclusion, and a missing key/no
 * generator returns empty without throwing. No network or API key is used.
 */
class GeminiLlmServiceTest {

    private static LlmProperties props(long timeoutMs) {
        LlmProperties p = new LlmProperties();
        p.setApiKey("");
        p.setModel("gemini-test");
        p.setTimeoutMs(timeoutMs);
        return p;
    }

    private static CommandEvent event() {
        return new CommandEvent(
                "evt-1",
                Actor.human("alice"),
                "sess-1",
                "rm -rf /",
                "/home/alice",
                null,
                Map.of(),
                1_710_000_000_000L,
                InputOrigin.TYPED,
                SignalSource.HOOK,
                IntentSource.DECLARED,
                null);
    }

    private static DivergenceResult result() {
        return new DivergenceResult(
                0.8,
                List.of(ComponentResult.scored(ComponentId.SEMANTIC_INCONSISTENCY, 0.9, 0.3, null)),
                Set.of());
    }

    @Test
    void semanticScoreParsedFromValidResponse() {
        GeminiLlmService service = new GeminiLlmService(props(1000),
                prompt -> "{\"semantic_inconsistency\": 0.65, \"rationale\": \"ok\"}");

        OptionalDouble score = service.semanticInconsistency(event(), "deploy the app");

        assertTrue(score.isPresent());
        assertEquals(0.65, score.getAsDouble(), 1e-9);
    }

    @Test
    void semanticScoreEmptyWhenGeneratorThrows() {
        GeminiLlmService service = new GeminiLlmService(props(1000),
                prompt -> {
                    throw new RuntimeException("boom");
                });

        assertFalse(service.semanticInconsistency(event(), "deploy the app").isPresent());
    }

    @Test
    void semanticScoreEmptyOnTimeout() {
        GeminiLlmService service = new GeminiLlmService(props(80),
                prompt -> {
                    Thread.sleep(1000);
                    return "{\"semantic_inconsistency\": 0.5}";
                });

        long start = System.nanoTime();
        OptionalDouble score = service.semanticInconsistency(event(), "deploy the app");
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertFalse(score.isPresent(), "timed-out call must exclude the score");
        assertTrue(elapsedMs < 900, "must return within the tight timeout, not wait for the slow call");
    }

    @Test
    void semanticScoreEmptyWhenIntentBlank() {
        GeminiLlmService service = new GeminiLlmService(props(1000),
                prompt -> "{\"semantic_inconsistency\": 0.5}");

        assertFalse(service.semanticInconsistency(event(), "  ").isPresent());
        assertFalse(service.semanticInconsistency(event(), null).isPresent());
    }

    @Test
    void semanticScoreEmptyOnMalformedResponse() {
        GeminiLlmService service = new GeminiLlmService(props(1000),
                prompt -> "the command looks fine to me");

        assertFalse(service.semanticInconsistency(event(), "deploy the app").isPresent());
    }

    @Test
    void explainReturnsTrimmedText() {
        GeminiLlmService service = new GeminiLlmService(props(1000),
                prompt -> "  This command was blocked because it diverges from the declared intent.  ");

        Optional<String> explanation =
                service.explain(event(), result(), new Decision(CorrectiveAction.BLOCK, 0.8, "THRESHOLD_BLOCK"));

        assertTrue(explanation.isPresent());
        assertEquals("This command was blocked because it diverges from the declared intent.",
                explanation.get());
    }

    @Test
    void explainEmptyOnBlankResponse() {
        GeminiLlmService service = new GeminiLlmService(props(1000), prompt -> "   ");

        assertFalse(service.explain(event(), result(),
                new Decision(CorrectiveAction.BLOCK, 0.8, "THRESHOLD_BLOCK")).isPresent());
    }

    @Test
    void degradedModeWhenNoApiKeyAndNoGenerator() {
        // No injected generator and a blank API key: the client is never built and calls degrade.
        GeminiLlmService service = new GeminiLlmService(props(1000));

        assertFalse(service.semanticInconsistency(event(), "deploy the app").isPresent());
        assertFalse(service.explain(event(), result(),
                new Decision(CorrectiveAction.BLOCK, 0.8, "THRESHOLD_BLOCK")).isPresent());
    }
}
