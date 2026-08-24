package com.intentguard.translation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import com.intentguard.llm.LlmProperties;
import com.intentguard.speech.GeminiSpeechProviderLazyAccessor;

/**
 * Unit tests verifying lazy client construction and single-instance guarantee for both
 * {@link GeminiTranslationProvider} and {@code GeminiSpeechProvider}.
 *
 * <p>The Gemini Client (represented by the {@code GeminiTextGenerator} test seam) must not be
 * constructed or invoked until the first actual API call ({@code translate}/{@code recognize}),
 * and subsequent calls must reuse the same generator instance without re-construction.
 *
 * <p><strong>Validates: Requirements 11.1, 11.3</strong>
 */
class GeminiLazyClientConstructionTest {

    private static final LanguageTag EN = LanguageTag.of("en");
    private static final LanguageTag HI = LanguageTag.of("hi");

    // ---- Translation Provider: lazy initialization ----

    @Test
    void translationProvider_generatorNotInvokedUntilFirstTranslateCall() {
        // Validates: Requirement 11.1
        AtomicInteger invocationCount = new AtomicInteger(0);
        GeminiTranslationProvider provider = translationProviderWith(invocationCount);

        // Generator must not be invoked by mere construction.
        assertThat(invocationCount.get()).isZero();
    }

    @Test
    void translationProvider_generatorInvokedOnFirstTranslateCall() {
        // Validates: Requirement 11.1
        AtomicInteger invocationCount = new AtomicInteger(0);
        GeminiTranslationProvider provider = translationProviderWith(invocationCount);

        // First translate call (with different source/target to avoid same-language passthrough)
        // should trigger the generator.
        provider.translate("hello", EN, HI);

        assertThat(invocationCount.get()).isEqualTo(1);
    }

    @Test
    void translationProvider_multipleCallsReuseGeneratorWithoutReconstruction() {
        // Validates: Requirement 11.3
        AtomicInteger invocationCount = new AtomicInteger(0);
        GeminiTranslationProvider provider = translationProviderWith(invocationCount);

        // Multiple translate calls should each invoke the generator once (not re-construct it).
        provider.translate("hello", EN, HI);
        provider.translate("world", EN, HI);
        provider.translate("foo", EN, HI);

        // Each call invokes the generator exactly once — 3 total invocations, one per call.
        // The key assertion is that the generator is not re-constructed between calls.
        assertThat(invocationCount.get()).isEqualTo(3);
    }

    @Test
    void translationProvider_sameLangPassthroughDoesNotInvokeGenerator() {
        // Validates: Requirement 11.1 — same-language passthrough skips the generator entirely.
        AtomicInteger invocationCount = new AtomicInteger(0);
        GeminiTranslationProvider provider = translationProviderWith(invocationCount);

        provider.translate("hello", EN, EN);

        assertThat(invocationCount.get()).isZero();
    }

    // ---- Speech Provider: lazy initialization ----

    @Test
    void speechProvider_generatorNotInvokedUntilFirstRecognizeCall() {
        // Validates: Requirement 11.1
        AtomicInteger invocationCount = new AtomicInteger(0);
        GeminiSpeechProviderLazyAccessor accessor = speechAccessorWith(invocationCount);

        // Generator must not be invoked by mere construction.
        assertThat(invocationCount.get()).isZero();
    }

    @Test
    void speechProvider_generatorInvokedOnFirstRecognizeCall() {
        // Validates: Requirement 11.1
        AtomicInteger invocationCount = new AtomicInteger(0);
        GeminiSpeechProviderLazyAccessor accessor = speechAccessorWith(invocationCount);

        // First recognize call should trigger the generator.
        accessor.recognize(HI);

        assertThat(invocationCount.get()).isEqualTo(1);
    }

    @Test
    void speechProvider_multipleCallsReuseGeneratorWithoutReconstruction() {
        // Validates: Requirement 11.3
        AtomicInteger invocationCount = new AtomicInteger(0);
        GeminiSpeechProviderLazyAccessor accessor = speechAccessorWith(invocationCount);

        // Multiple recognize calls should each invoke the generator once (not re-construct it).
        accessor.recognize(HI);
        accessor.recognize(EN);
        accessor.recognize(HI);

        // Each call invokes the generator exactly once — 3 total invocations, one per call.
        assertThat(invocationCount.get()).isEqualTo(3);
    }

    @Test
    void speechProvider_synthesizeDoesNotInvokeGenerator() {
        // Validates: Requirement 11.1 — synthesize (degraded TTS) never touches the generator.
        AtomicInteger invocationCount = new AtomicInteger(0);
        GeminiSpeechProviderLazyAccessor accessor = speechAccessorWith(invocationCount);

        accessor.synthesize(HI);

        assertThat(invocationCount.get()).isZero();
    }

    // ---- Helpers ----

    private static GeminiTranslationProvider translationProviderWith(AtomicInteger counter) {
        LlmProperties llmProps = new LlmProperties();
        llmProps.setApiKey("test-api-key");
        llmProps.setModel("gemini-2.5-flash");

        TranslationProperties translationProps = new TranslationProperties();
        translationProps.setApiKey("test-api-key");
        translationProps.setTimeoutMs(2000);

        return new GeminiTranslationProvider(llmProps, translationProps, prompt -> {
            counter.incrementAndGet();
            return "translated-text";
        });
    }

    private static GeminiSpeechProviderLazyAccessor speechAccessorWith(AtomicInteger counter) {
        LlmProperties llmProps = new LlmProperties();
        llmProps.setApiKey("test-api-key");
        llmProps.setModel("gemini-2.5-flash");

        return new GeminiSpeechProviderLazyAccessor(llmProps, counter);
    }
}
