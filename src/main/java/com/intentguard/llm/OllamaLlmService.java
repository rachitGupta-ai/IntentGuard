package com.intentguard.llm;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import com.intentguard.domain.CommandEvent;
import com.intentguard.domain.Decision;
import com.intentguard.domain.DivergenceResult;

/**
 * Ollama-backed {@link LlmService} adapter. Mirrors the resilience patterns of
 * {@link GeminiLlmService}: per-call timeout, never-throw boundary, and graceful degradation to
 * empty results.
 *
 * <p>Activated when {@code intentguard.llm.provider=ollama} is set. Delegates to the shared
 * {@link OllamaClient} which speaks the Ollama REST API ({@code /api/generate}).
 *
 * <p>Reuses {@link LlmPromptBuilder} for prompt construction and {@link LlmResponseParser} for
 * score extraction, so the semantic contract is identical to the Gemini path.
 */
@Service
@ConditionalOnProperty(name = "intentguard.llm.provider", havingValue = "ollama")
public class OllamaLlmService implements LlmService {

    private static final Logger log = System.getLogger(OllamaLlmService.class.getName());

    private final OllamaProperties properties;
    private final OllamaClient client;

    public OllamaLlmService(OllamaProperties properties) {
        this.properties = properties;
        // Use the tight scoring timeout for the HTTP connect budget so the synchronous shell-hook
        // path stays responsive even when the LLM server is slow/loaded.
        this.client = new OllamaClient(properties.getBaseUrl(), properties.getApiKey(),
                properties.getScoringTimeoutMs());
        log.log(Level.INFO, "OllamaLlmService activated — base-url={0}, model={1}, scoringTimeoutMs={2}",
                properties.getBaseUrl(), properties.getModel(), properties.getScoringTimeoutMs());
    }

    @Override
    public OptionalDouble semanticInconsistency(CommandEvent event, String intentText) {
        if (event == null || intentText == null || intentText.isBlank()) {
            return OptionalDouble.empty();
        }
        String prompt = LlmPromptBuilder.semanticPrompt(event, intentText);
        // Scoring is on the synchronous shell-hook gate: use the tight scoring timeout so a slow
        // LLM causes graceful exclusion of the Semantic_Inconsistency component rather than a hang.
        Optional<String> response = client.generate(properties.getModel(), prompt,
                properties.getScoringTimeoutMs(), 0.1, 200);
        if (response.isEmpty()) {
            return OptionalDouble.empty();
        }
        return LlmResponseParser.parseSemanticScore(response.get());
    }

    @Override
    public Optional<String> explain(CommandEvent event, DivergenceResult result, Decision decision) {
        if (event == null || result == null || decision == null) {
            return Optional.empty();
        }
        String prompt = LlmPromptBuilder.explanationPrompt(event, result, decision);
        return client.generate(properties.getModel(), prompt,
                properties.getScoringTimeoutMs(), 0.3, 300)
                .map(String::trim)
                .filter(text -> !text.isEmpty());
    }

    @Override
    public Optional<String> summarizeIntent(List<String> recentCommands) {
        if (recentCommands == null || recentCommands.isEmpty()) {
            return Optional.empty();
        }
        String prompt = LlmPromptBuilder.summarizeIntentPrompt(recentCommands);
        return client.generate(properties.getModel(), prompt,
                properties.getTimeoutMs(), 0.3, 100)
                .map(String::trim)
                .filter(text -> !text.isEmpty());
    }
}
