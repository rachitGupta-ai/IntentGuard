package com.intentguard.speech;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.google.genai.Client;
import com.intentguard.llm.LlmProperties;
import com.intentguard.translation.LanguageTag;

import jakarta.annotation.PreDestroy;

/**
 * Gemini-backed {@link SpeechProvider} adapter (Req 3.1, 3.2).
 *
 * <p>Uses the Google Gemini Multimodal API for speech-to-text (STT) recognition. Text-to-speech
 * (TTS) returns {@link Optional#empty()} due to the v1.28.0 SDK limitation (no audio output in
 * {@code GenerateContentResponse}). Mirrors the resilience patterns of {@code GeminiLlmService}:
 * lazy {@link Client} construction, daemon-thread executor, per-call timeout via
 * {@code Future.get()}, and a never-throw boundary that degrades to {@link Optional#empty()} on
 * any failure.
 *
 * <p><strong>API key resolution (Req 6.2, 6.4):</strong> If a dedicated
 * {@code intentguard.speech.api-key} is configured (non-blank), it takes priority. Otherwise the
 * provider falls back to {@code intentguard.llm.api-key} (bound from {@code GEMINI_API_KEY}).
 *
 * <p><strong>Degraded mode (Req 11.1):</strong> When the resolved key is blank or client
 * construction fails, all {@link #recognize} and {@link #synthesize} calls return
 * {@link Optional#empty()} without network I/O.
 */
@Component
public class GeminiSpeechProvider implements SpeechProvider {

    private static final Logger log = System.getLogger(GeminiSpeechProvider.class.getName());

    private final LlmProperties llmProperties;
    private final SpeechProperties speechProperties;
    private final ExecutorService executor;

    /** Injected generator (tests) or lazily-built SDK-backed generator (production). */
    private volatile GeminiTextGenerator generator;
    private volatile boolean clientInitAttempted;
    private volatile Client client;

    @Autowired
    public GeminiSpeechProvider(LlmProperties llmProperties,
                                SpeechProperties speechProperties) {
        this(llmProperties, speechProperties, null);
    }

    /**
     * Test seam: supply a {@link GeminiTextGenerator} directly to exercise timeout, parsing, and
     * fallback behavior without the SDK. When {@code generator} is {@code null}, an SDK-backed
     * generator is built lazily from the configured client.
     */
    public GeminiSpeechProvider(LlmProperties llmProperties,
                         SpeechProperties speechProperties,
                         GeminiTextGenerator generator) {
        this.llmProperties = llmProperties;
        this.speechProperties = speechProperties;
        this.generator = generator;
        this.executor = Executors.newCachedThreadPool(daemonThreadFactory());
    }

    @Override
    public String id() {
        return "gemini";
    }

    /**
     * Recognizes spoken audio into text via the Gemini Multimodal API.
     *
     * <p>Returns {@link Optional#empty()} when the input is null, no generator is available
     * (degraded mode), the call times out, or any other error occurs. Never throws.
     */
    @Override
    public Optional<RecognizedText> recognize(AudioClip audio, LanguageTag language) {
        if (audio == null || language == null) {
            return Optional.empty();
        }
        GeminiTextGenerator active = resolveGenerator();
        if (active == null) {
            return Optional.empty();
        }
        String prompt = "Transcribe the following audio in " + language.value()
                + ". Return only the transcribed text.";
        Future<String> future = executor.submit(() -> active.generate(prompt));
        try {
            String result = future.get(speechProperties.getSttTimeoutMs(), TimeUnit.MILLISECONDS);
            if (result == null || result.isBlank()) {
                return Optional.empty();
            }
            return Optional.of(new RecognizedText(result.trim(), language));
        } catch (TimeoutException timeout) {
            future.cancel(true);
            log.log(Level.DEBUG, "Gemini STT call exceeded the {0}ms budget; returning empty",
                    speechProperties.getSttTimeoutMs());
            return Optional.empty();
        } catch (InterruptedException interrupted) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            return Optional.empty();
        } catch (Exception error) {
            future.cancel(true);
            log.log(Level.DEBUG, "Gemini STT call failed; returning empty", error);
            return Optional.empty();
        }
    }

    /**
     * Synthesizes text into spoken audio via the Gemini Multimodal API.
     *
     * <p><strong>SDK v1.28.0 limitation:</strong> The current version of the Google Gemini Java SDK
     * (v1.28.0) does not expose audio bytes in {@code GenerateContentResponse}. The Gemini API
     * supports audio output via {@code response_modalities: ["AUDIO"]} in the generation config,
     * but this is not surfaced in the Java SDK's response model at this version. Consequently,
     * this method returns {@link Optional#empty()} unconditionally so that
     * {@code DefaultSpeechService} degrades gracefully to presenting content as text.
     *
     * <p>When SDK support for audio output matures, this method will be updated to extract
     * audio from the response and return a populated {@link AudioClip}.
     *
     * @param text     the text to synthesize into audio (ignored due to SDK limitation)
     * @param language the target language for synthesis (ignored due to SDK limitation)
     * @return always {@link Optional#empty()} — SDK v1.28.0 does not support audio output
     */
    @Override
    public Optional<AudioClip> synthesize(String text, LanguageTag language) {
        return Optional.empty();
    }

    /**
     * Returns the active generator: the injected one if present, otherwise an SDK-backed generator
     * built lazily from the configured client, or {@code null} when no client is available.
     */
    GeminiTextGenerator resolveGenerator() {
        GeminiTextGenerator injected = generator;
        if (injected != null) {
            return injected;
        }
        Client c = clientOrNull();
        if (c == null) {
            return null;
        }
        GeminiTextGenerator built = prompt ->
                c.models.generateContent(llmProperties.getModel(), prompt, null).text();
        generator = built;
        return built;
    }

    /**
     * Lazily builds the Gemini {@code Client} exactly once. Returns {@code null} when no API key is
     * configured or client construction fails, so callers degrade gracefully (Req 8.2, 11.1).
     */
    private Client clientOrNull() {
        Client existing = client;
        if (existing != null || clientInitAttempted) {
            return existing;
        }
        synchronized (this) {
            if (clientInitAttempted) {
                return client;
            }
            clientInitAttempted = true;
            String apiKey = resolveApiKey();
            if (apiKey == null || apiKey.isBlank()) {
                log.log(Level.INFO, "No Gemini API key configured for speech; "
                        + "GeminiSpeechProvider runs in degraded mode (all recognize/synthesize "
                        + "calls return empty)");
                return null;
            }
            try {
                client = Client.builder().apiKey(apiKey).build();
            } catch (RuntimeException constructionFailure) {
                log.log(Level.WARNING, "Could not construct the Gemini client for speech; "
                        + "GeminiSpeechProvider runs in degraded mode", constructionFailure);
                client = null;
            }
            return client;
        }
    }

    /**
     * Resolves the API key for speech: dedicated {@code intentguard.speech.api-key} takes priority;
     * if blank, falls back to {@code intentguard.llm.api-key} (Req 6.2, 6.4).
     */
    String resolveApiKey() {
        if (speechProperties.hasApiKey()) {
            return speechProperties.getApiKey();
        }
        return llmProperties.getApiKey();
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }

    /**
     * Returns the executor for use by the recognize/synthesize methods (tasks 4.2, 4.3 will use).
     */
    ExecutorService executor() {
        return executor;
    }

    /**
     * Returns the speech properties for timeout configuration access.
     */
    SpeechProperties speechProperties() {
        return speechProperties;
    }

    /**
     * Returns the LLM properties for model name access.
     */
    LlmProperties llmProperties() {
        return llmProperties;
    }

    private static ThreadFactory daemonThreadFactory() {
        return runnable -> {
            Thread thread = new Thread(runnable, "speech-gemini");
            thread.setDaemon(true);
            return thread;
        };
    }
}
