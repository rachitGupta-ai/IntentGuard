package com.intentguard.llm;

import java.io.IOException;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Low-level HTTP client for Ollama-compatible servers (including BT SRE LLM service).
 *
 * <p>Encapsulates the {@code /api/generate} call with optional {@code x-api-key} authentication,
 * timeout enforcement, and JSON response parsing. Follows the project's never-throw convention:
 * all failures degrade to {@link Optional#empty()}.
 *
 * <p>This is a shared utility used by {@link OllamaLlmService} and
 * {@link com.intentguard.translation.OllamaTranslationProvider}.
 */
public class OllamaClient {

    private static final Logger log = System.getLogger(OllamaClient.class.getName());
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String baseUrl;
    private final String apiKey;
    private final HttpClient httpClient;

    public OllamaClient(String baseUrl, String apiKey, long timeoutMs) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.apiKey = apiKey;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(timeoutMs))
                .build();
    }

    /**
     * Calls the Ollama {@code /api/generate} endpoint with the given model and prompt.
     *
     * @param model   the model name (e.g., {@code "Qwen2.5:14B"})
     * @param prompt  the full prompt text
     * @param timeoutMs per-call timeout in milliseconds
     * @return the generated text, or {@link Optional#empty()} on any failure
     */
    public Optional<String> generate(String model, String prompt, long timeoutMs) {
        return generate(model, prompt, timeoutMs, 0.1, 500);
    }

    /**
     * Calls the Ollama {@code /api/generate} endpoint with full control over generation parameters.
     *
     * @param model      the model name
     * @param prompt     the full prompt text
     * @param timeoutMs  per-call timeout in milliseconds
     * @param temperature sampling temperature
     * @param numPredict maximum tokens to generate
     * @return the generated text, or {@link Optional#empty()} on any failure
     */
    public Optional<String> generate(String model, String prompt, long timeoutMs,
                                     double temperature, int numPredict) {
        try {
            ObjectNode body = MAPPER.createObjectNode();
            body.put("model", model);
            body.put("prompt", prompt);
            body.put("stream", false);

            ObjectNode options = MAPPER.createObjectNode();
            options.put("temperature", temperature);
            options.put("num_predict", numPredict);
            body.set("options", options);

            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/api/generate"))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofMillis(timeoutMs))
                    .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body)));

            if (apiKey != null && !apiKey.isBlank()) {
                requestBuilder.header("x-api-key", apiKey);
            }

            HttpResponse<String> response = httpClient.send(requestBuilder.build(),
                    HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                log.log(Level.DEBUG, "Ollama returned HTTP {0}: {1}",
                        response.statusCode(), truncate(response.body(), 200));
                return Optional.empty();
            }

            JsonNode root = MAPPER.readTree(response.body());
            JsonNode responseNode = root.get("response");
            if (responseNode == null || responseNode.asText().isBlank()) {
                return Optional.empty();
            }
            return Optional.of(responseNode.asText().trim());

        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            log.log(Level.DEBUG, "Ollama call failed: {0}", e.getMessage());
            return Optional.empty();
        } catch (Exception e) {
            log.log(Level.DEBUG, "Ollama call failed unexpectedly: {0}", e.getMessage());
            return Optional.empty();
        }
    }

    private static String truncate(String text, int maxLen) {
        if (text == null) return "";
        return text.length() <= maxLen ? text : text.substring(0, maxLen) + "...";
    }
}
