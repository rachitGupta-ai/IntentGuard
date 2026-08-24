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
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Generic cloud {@link TranslationProvider} adapter (Req 8.1), interchangeable with
 * {@link BhashiniTranslationProvider} by configuration only. It targets a cloud translation API
 * (e.g. a Google-Translate-style REST endpoint) and, like the Bhashini adapter, transmits only over
 * encrypted (HTTPS) transport (Req 11.1) and never throws across the service boundary: any timeout,
 * transport error, or malformed response degrades to {@link java.util.Optional#empty()} via
 * {@link AbstractHttpTranslationProvider}.
 */
@Component
public class CloudTranslationProvider extends AbstractHttpTranslationProvider {

    /** Cloud translation endpoint — HTTPS only (Req 11.1). */
    static final String DEFAULT_ENDPOINT = "https://translation.googleapis.com/language/translate/v2";

    private static final String PROVIDER_ID = "cloud";

    /** Production constructor: builds the HTTPS transport lazily from configuration. */
    @Autowired
    public CloudTranslationProvider(TranslationProperties properties) {
        super(properties, DEFAULT_ENDPOINT, null);
    }

    /**
     * Test/override seam: supply an explicit endpoint and transport so encrypted-transport
     * validation and never-throw behavior can be exercised without the network.
     */
    CloudTranslationProvider(
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
            root.put("q", maskedText);
            root.put("source", source.value());
            root.put("target", target.value());
            root.put("format", "text");

            HttpRequest request = HttpRequest.newBuilder(endpoint)
                    .timeout(Duration.ofMillis(properties.getTimeoutMs()))
                    .header("Content-Type", "application/json; charset=utf-8")
                    .header("Authorization", "Bearer " + apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(
                            mapper.writeValueAsString(root), StandardCharsets.UTF_8))
                    .build();

            HttpResponse<String> response =
                    client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("Cloud translation returned HTTP " + response.statusCode());
            }
            JsonNode body = mapper.readTree(response.body());
            JsonNode translated = body.path("data").path("translations").path(0).path("translatedText");
            if (translated.isMissingNode() || translated.isNull()) {
                throw new IllegalStateException("Cloud translation response missing translatedText");
            }
            return translated.asText();
        };
    }
}
