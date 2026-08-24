// Feature: indian-language-translation, Property 4: Provider failure falls back to English within budget and is recorded
package com.intentguard.translation;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

import com.intentguard.speech.SpeechProperties;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

/**
 * Feature: indian-language-translation, Property 4: Provider failure falls back to English within
 * budget and is recorded.
 *
 * <p>For any Operator_Facing_Content and any Translation_Provider that times out (beyond the
 * configured budget) or returns an error, the Control_Tower presents the original English content,
 * the outcome is recorded as {@link TranslationOutcome#PROVIDER_TIMEOUT} or
 * {@link TranslationOutcome#PROVIDER_ERROR}, and the call returns within the timeout budget
 * (Validates: Requirements 2.4, 2.5, 9.1).
 *
 * <p>This drives the real {@link DefaultTranslationService} against two in-memory fakes:
 * <ul>
 *   <li>an {@link ErrorTranslationProvider} that returns {@link java.util.Optional#empty()} on every
 *       call, exercising the {@link TranslationOutcome#PROVIDER_ERROR} path (Req 2.5); and</li>
 *   <li>a {@link TimeoutTranslationProvider} whose per-call delay is deliberately set well beyond the
 *       configured timeout, exercising the {@link TranslationOutcome#PROVIDER_TIMEOUT} path (Req 2.4,
 *       9.1).</li>
 * </ul>
 *
 * <p>To keep the property fast the configured translation timeout is set to a small
 * {@value #TIMEOUT_MS}ms budget (via {@link TranslationProperties#setTimeoutMs(long)}); the assertion
 * that the {@code translate} call returns within {@code budget + margin} still proves the service
 * bounds a slow provider rather than blocking on it. In every case the presented text must equal the
 * original English Source_Text byte-for-byte and must not be marked as a machine translation.
 */
class ProviderFailureFallbackProperties {

    /** Small configured translation budget used to keep the timeout path fast in tests. */
    private static final long TIMEOUT_MS = 200;

    /**
     * Generous scheduling margin added to the configured budget for the elapsed-time assertion, so
     * the test proves boundedness without being flaky under CI thread-scheduling jitter.
     */
    private static final long MARGIN_MS = 2000;

    /** How the simulated provider fails. */
    enum FailureMode {
        /** Provider returns an error / empty result &rarr; {@link TranslationOutcome#PROVIDER_ERROR}. */
        ERROR,
        /** Provider blocks beyond the budget &rarr; {@link TranslationOutcome#PROVIDER_TIMEOUT}. */
        TIMEOUT
    }

    // Feature: indian-language-translation, Property 4: Provider failure falls back to English within budget and is recorded
    @Property(tries = 200)
    void providerFailureFallsBackToEnglishWithinBudgetAndIsRecorded(
            @ForAll("englishContent") String english,
            @ForAll("supportedNonEnglishTargets") LanguageTag target,
            @ForAll FailureMode mode) {

        TranslationProvider failing;
        TranslationOutcome expectedOutcome;
        if (mode == FailureMode.ERROR) {
            failing = new ErrorTranslationProvider();
            expectedOutcome = TranslationOutcome.PROVIDER_ERROR;
        } else {
            // Delay far beyond the small configured budget so the bounded call always times out.
            failing = new TimeoutTranslationProvider(Duration.ofMillis(TIMEOUT_MS * 25));
            expectedOutcome = TranslationOutcome.PROVIDER_TIMEOUT;
        }

        DefaultTranslationService service = serviceWith(failing, TIMEOUT_MS);

        long startNanos = System.nanoTime();
        TranslationResult result = service.translate(english, SupportedLanguages.ENGLISH, target);
        long elapsedMs = Duration.ofNanos(System.nanoTime() - startNanos).toMillis();

        // The outcome is recorded as the specific failure mode (Req 2.4, 2.5)...
        assertThat(result.outcome()).isEqualTo(expectedOutcome);
        // ...the original English content is presented byte-for-byte unchanged...
        assertThat(result.text()).isEqualTo(english);
        // ...it is not presented as a machine translation...
        assertThat(result.translated()).isFalse();
        // ...and the call returns within the configured budget plus a scheduling margin (Req 9.1).
        assertThat(elapsedMs)
                .as("translate must return within the %dms budget (+%dms margin) but took %dms",
                        TIMEOUT_MS, MARGIN_MS, elapsedMs)
                .isLessThanOrEqualTo(TIMEOUT_MS + MARGIN_MS);
    }

    // ---- Worked examples --------------------------------------------------------------------------

    @Example
    void providerErrorFallsBackToEnglish() {
        DefaultTranslationService service = serviceWith(new ErrorTranslationProvider(), TIMEOUT_MS);

        String english = "review the alert for session-42 on host db.prod.internal";
        TranslationResult result = service.translate(english, SupportedLanguages.ENGLISH, LanguageTag.of("hi"));

        assertThat(result.outcome()).isEqualTo(TranslationOutcome.PROVIDER_ERROR);
        assertThat(result.text()).isEqualTo(english);
        assertThat(result.translated()).isFalse();
    }

    @Example
    void providerTimeoutFallsBackToEnglishWithinBudget() {
        TimeoutTranslationProvider slow = new TimeoutTranslationProvider(Duration.ofMillis(TIMEOUT_MS * 25));
        DefaultTranslationService service = serviceWith(slow, TIMEOUT_MS);

        String english = "run backup on host db.prod.internal score 0.91";
        long startNanos = System.nanoTime();
        TranslationResult result = service.translate(english, SupportedLanguages.ENGLISH, LanguageTag.of("bn"));
        long elapsedMs = Duration.ofNanos(System.nanoTime() - startNanos).toMillis();

        assertThat(result.outcome()).isEqualTo(TranslationOutcome.PROVIDER_TIMEOUT);
        assertThat(result.text()).isEqualTo(english);
        assertThat(result.translated()).isFalse();
        assertThat(elapsedMs).isLessThanOrEqualTo(TIMEOUT_MS + MARGIN_MS);
        // The provider was in fact reached (the timeout is a bounded provider call, not a guard).
        assertThat(slow.invocationCount()).isEqualTo(1);
    }

    // ---- Helpers ----------------------------------------------------------------------------------

    /**
     * Builds a live {@link DefaultTranslationService} directly (no Spring) around the given failing
     * provider, wiring the {@link TranslationRuntimeConfig} to select that provider by identity and
     * bounding provider calls by {@code timeoutMs} so the timeout path stays fast.
     */
    private DefaultTranslationService serviceWith(TranslationProvider provider, long timeoutMs) {
        TranslationProperties translationProperties = new TranslationProperties();
        translationProperties.setProvider(provider.id());
        translationProperties.setApiKey("test-key");
        translationProperties.setTimeoutMs(timeoutMs);

        TranslationRuntimeConfig runtimeConfig =
                new TranslationRuntimeConfig(translationProperties, new SpeechProperties());
        runtimeConfig.initialize();

        return new DefaultTranslationService(
                List.of(provider),
                new TranslationCache(),
                runtimeConfig,
                SupportedLanguages.defaults(),
                new TechnicalTokenProtector());
    }

    // ---- Generators -------------------------------------------------------------------------------

    @Provide
    Arbitrary<String> englishContent() {
        // English Operator_Facing_Content: plain prose, and prose mixed with Technical_Tokens
        // (command lines, paths, hostnames, numeric scores, timestamps, reason codes).
        Arbitrary<String> prose = Arbitraries.strings().ascii().ofMinLength(1).ofMaxLength(80);
        Arbitrary<String> mixed = Arbitraries.of(
                "run git status on host db.prod.internal",
                "score 0.91 at 2024-01-15T02:30:00Z code DUAL_CONTROL_REQUIRED",
                "please review /etc/passwd for session-42",
                "delete rm -rf /tmp/cache before approval");
        return Arbitraries.oneOf(prose, mixed);
    }

    @Provide
    Arbitrary<LanguageTag> supportedNonEnglishTargets() {
        return Arbitraries.of("hi", "bn", "te", "mr", "ta", "gu", "kn", "ml", "pa", "or")
                .map(LanguageTag::of);
    }
}
