package com.intentguard.translation;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * {@link TranslationProvider} adapter for the national Bhashini machine-translation (NMT) service
 * (Req 8.1). It transmits Source_Text to Bhashini only over encrypted (HTTPS) transport (Req 11.1)
 * and, following the {@code LlmService} contract, never throws across the service boundary: any
 * timeout, transport error, or malformed response degrades to {@link java.util.Optional#empty()}
 * via {@link AbstractHttpTranslationProvider}.
 *
 * <p>The adapter operates on already-masked text (Technical_Token sentinels in place) and simply
 * relays it to Bhashini and returns the translated masked text; token restoration is the
 * {@code TranslationService}'s responsibility.
 */
@Component
public class BhashiniTranslationProvider extends AbstractHttpTranslationProvider {

    /** Bhashini inference endpoint — HTTPS only (Req 11.1). */
    static final String DEFAULT_ENDPOINT =
            "https://dhruva-api.bhashini.gov.in/services/inference/pipeline";

    private static final String PROVIDER_ID = "bhashini";

    /** Production constructor: builds the HTTPS transport lazily from configuration. */
    @Autowired
    public BhashiniTranslationProvider(TranslationProperties properties) {
        super(properties, DEFAULT_ENDPOINT, null);
    }

    /**
     * Test/override seam: supply an explicit endpoint and transport so encrypted-transport
     * validation and never-throw behavior can be exercised without the network.
     */
    BhashiniTranslationProvider(
            TranslationProperties properties, String endpoint, TranslationHttpTransport transport) {
        super(properties, endpoint, transport);
    }

    @Override
    public String id() {
        return PROVIDER_ID;
    }

    @Override
    protected TranslationHttpTransport buildTransport(URI endpoint, TranslationProperties properties) {
        ObjectMapper mapper = new ObjectMapper();
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(properties.getTimeoutMs()))
                .build();
        String apiKey = properties.getApiKey();
        return (maskedText, source, target) -> {
            ObjectNode root = mapper.createObjectNode();
            ObjectNode config = root.putObject("config");
            ObjectNode language = config.putObject("language");
            language.put("sourceLanguage", source.value());
            language.put("targetLanguage", target.value());
            ArrayNode input = root.putArray("input");
            input.addObject().put("source", maskedText);

            HttpRequest request = HttpRequest.newBuilder(endpoint)
                    .timeout(Duration.ofMillis(properties.getTimeoutMs()))
                    .header("Content-Type", "application/json")
                    .header("Authorization", apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(
                            mapper.writeValueAsString(root), StandardCharsets.UTF_8))
                    .build();

            HttpResponse<String> response =
                    client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("Bhashini returned HTTP " + response.statusCode());
            }
            JsonNode body = mapper.readTree(response.body());
            JsonNode target0 = body.path("pipelineResponse").path(0).path("output").path(0).path("target");
            if (target0.isMissingNode() || target0.isNull()) {
                throw new IllegalStateException("Bhashini response missing translated target");
            }
            return target0.asText();
        };
    }
}
