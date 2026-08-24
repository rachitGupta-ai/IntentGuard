// Feature: indian-language-translation, Property 17: Live alerts are translated with English fallback
package com.intentguard.api;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

import com.intentguard.translation.LanguageTag;
import com.intentguard.translation.MaskedText;
import com.intentguard.translation.SupportedLanguages;
import com.intentguard.translation.TechnicalTokenProtector;
import com.intentguard.translation.TranslationOutcome;
import com.intentguard.translation.TranslationResult;
import com.intentguard.translation.TranslationService;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Assume;
import net.jqwik.api.Combinators;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

/**
 * Feature: indian-language-translation, Property 17: Live alerts are translated with English
 * fallback.
 *
 * <p>For any {@link AlertEvent} delivered to an Operator whose Language_Preference is a non-English
 * Supported_Language, the message delivered over the live channel is the translation of the alert
 * message (with every Technical_Token preserved byte-for-byte); if translation fails, the original
 * English message is delivered (Validates: Requirements 2.6, 9.2).
 *
 * <p>This drives the real {@link TranslatingLiveEventSink} decorator against an in-memory capturing
 * {@link RecordingSink} delegate and stub {@link TranslationService}s:
 * <ul>
 *   <li>a token-preserving {@link SuccessTranslationService} that masks Technical_Tokens with the
 *       real {@link TechnicalTokenProtector}, rewrites only the surrounding prose, and restores the
 *       tokens verbatim — modelling a genuine translation — so the decorator forwards a rewritten
 *       envelope carrying the {@code Translated_Text} while every Technical_Token survives;</li>
 *   <li>a {@link FailureTranslationService} that returns a fall-through outcome
 *       ({@link TranslationOutcome#PROVIDER_ERROR} or {@link TranslationOutcome#PROVIDER_TIMEOUT})
 *       carrying the original English text, so the decorator must deliver the original English
 *       message unchanged.</li>
 * </ul>
 *
 * <p>The English-preference case is covered too: the decorator must forward the alert unchanged and
 * must never invoke the {@link TranslationService}.
 */
class TranslatingLiveEventSinkProperties {

    private static final long NOW = 1_700_000_000_000L;

    // Feature: indian-language-translation, Property 17: Live alerts are translated with English fallback
    @Property(tries = 200)
    void nonEnglishSuccessfulTranslationDeliversTranslationWithTokensPreserved(
            @ForAll("alertMessagesWithTokens") String englishMessage,
            @ForAll("nonEnglishTargets") LanguageTag preference) {

        TechnicalTokenProtector protector = new TechnicalTokenProtector();
        MaskedText masked = protector.mask(englishMessage);
        // The token-preservation half of the property is only meaningful with Technical_Tokens present.
        Assume.that(masked.hasTokens());

        RecordingSink delegate = new RecordingSink();
        SuccessTranslationService translation = new SuccessTranslationService();
        TranslatingLiveEventSink sink = new TranslatingLiveEventSink(delegate, preference, translation);

        AlertEvent english = alert(englishMessage);
        sendQuietly(sink, LiveEvent.alert(english));

        // Exactly one envelope is delivered, still an ALERT carrying an AlertEvent.
        assertThat(delegate.received).hasSize(1);
        LiveEvent envelope = delegate.received.get(0);
        assertThat(envelope.type()).isEqualTo(LiveEvent.TYPE_ALERT);
        assertThat(envelope.payload()).isInstanceOf(AlertEvent.class);
        AlertEvent delivered = (AlertEvent) envelope.payload();

        // The delivered message is the translation (differs from the original English)...
        String expectedTranslation = fakeTranslate(englishMessage, preference, protector);
        assertThat(delivered.message()).isEqualTo(expectedTranslation);
        assertThat(delivered.message()).isNotEqualTo(englishMessage);
        // ...and every Technical_Token from the source message survives byte-for-byte (Req 2.6).
        for (String token : masked.tokens()) {
            assertThat(delivered.message())
                    .as("Technical_Token '%s' must survive translation byte-for-byte", token)
                    .contains(token);
        }

        // Non-message alert fields are carried through unchanged.
        assertThat(delivered.alertType()).isEqualTo(english.alertType());
        assertThat(delivered.userId()).isEqualTo(english.userId());
        assertThat(delivered.timestamp()).isEqualTo(english.timestamp());
        assertThat(delivered.highRisk()).isEqualTo(english.highRisk());
        assertThat(delivered.evidenceDeviations()).isEqualTo(english.evidenceDeviations());
        assertThat(translation.invocationCount()).isEqualTo(1);
    }

    // Feature: indian-language-translation, Property 17: Live alerts are translated with English fallback
    @Property(tries = 200)
    void translationFailureDeliversOriginalEnglishMessage(
            @ForAll("alertMessages") String englishMessage,
            @ForAll("nonEnglishTargets") LanguageTag preference,
            @ForAll("failureOutcomes") TranslationOutcome failure) {

        RecordingSink delegate = new RecordingSink();
        FailureTranslationService translation = new FailureTranslationService(failure);
        TranslatingLiveEventSink sink = new TranslatingLiveEventSink(delegate, preference, translation);

        AlertEvent english = alert(englishMessage);
        sendQuietly(sink, LiveEvent.alert(english));

        // On any translation fall-through the original English message is delivered (Req 2.6, 9.2).
        assertThat(delegate.received).hasSize(1);
        AlertEvent delivered = (AlertEvent) delegate.received.get(0).payload();
        assertThat(delivered.message()).isEqualTo(englishMessage);
        assertThat(delivered.alertType()).isEqualTo(english.alertType());
        assertThat(delivered.userId()).isEqualTo(english.userId());
        assertThat(delivered.timestamp()).isEqualTo(english.timestamp());
        assertThat(delivered.highRisk()).isEqualTo(english.highRisk());
        assertThat(delivered.evidenceDeviations()).isEqualTo(english.evidenceDeviations());
    }

    // Feature: indian-language-translation, Property 17: Live alerts are translated with English fallback
    @Property(tries = 200)
    void englishPreferenceForwardsAlertUnchangedWithoutTranslating(
            @ForAll("alertMessages") String englishMessage) {

        RecordingSink delegate = new RecordingSink();
        SuccessTranslationService translation = new SuccessTranslationService();
        TranslatingLiveEventSink sink =
                new TranslatingLiveEventSink(delegate, SupportedLanguages.ENGLISH, translation);

        AlertEvent english = alert(englishMessage);
        LiveEvent original = LiveEvent.alert(english);
        sendQuietly(sink, original);

        // An English preference forwards the original envelope unchanged and never translates (Req 2.2).
        assertThat(delegate.received).hasSize(1);
        assertThat(delegate.received.get(0)).isSameAs(original);
        assertThat(translation.invocationCount()).isZero();
    }

    // ---- Worked examples --------------------------------------------------------------------------

    @Example
    void hindiPreferenceTranslatesAlertPreservingEveryToken() {
        RecordingSink delegate = new RecordingSink();
        SuccessTranslationService translation = new SuccessTranslationService();
        TranslatingLiveEventSink sink =
                new TranslatingLiveEventSink(delegate, LanguageTag.of("hi"), translation);

        String message = "high risk command rm -rf /tmp/cache on host db.prod.internal "
                + "score 0.91 code DUAL_CONTROL_REQUIRED for session-42";
        sendQuietly(sink, LiveEvent.alert(alert(message)));

        AlertEvent delivered = (AlertEvent) delegate.received.get(0).payload();
        assertThat(delivered.message()).isNotEqualTo(message);
        for (String token : new String[] {
                "rm -rf /tmp/cache", "db.prod.internal", "0.91", "DUAL_CONTROL_REQUIRED", "session-42"}) {
            assertThat(delivered.message()).contains(token);
        }
        assertThat(translation.invocationCount()).isEqualTo(1);
    }

    @Example
    void providerErrorDeliversOriginalEnglishAlert() {
        RecordingSink delegate = new RecordingSink();
        FailureTranslationService translation =
                new FailureTranslationService(TranslationOutcome.PROVIDER_ERROR);
        TranslatingLiveEventSink sink =
                new TranslatingLiveEventSink(delegate, LanguageTag.of("ta"), translation);

        String message = "session anomaly for alice on host api.service.io";
        sendQuietly(sink, LiveEvent.alert(alert(message)));

        AlertEvent delivered = (AlertEvent) delegate.received.get(0).payload();
        assertThat(delivered.message()).isEqualTo(message);
    }

    // ---- Fakes ------------------------------------------------------------------------------------

    /** A capturing delegate sink that records every envelope it receives. */
    private static final class RecordingSink implements LiveEventSink {
        final List<LiveEvent> received = new ArrayList<>();

        @Override
        public void send(LiveEvent event) {
            received.add(event);
        }
    }

    /**
     * A stub {@link TranslationService} modelling a successful, token-preserving translation: it
     * masks Technical_Tokens, rewrites only the surrounding prose, and restores the tokens verbatim,
     * returning outcome {@link TranslationOutcome#TRANSLATED}.
     */
    private static final class SuccessTranslationService implements TranslationService {
        private final TechnicalTokenProtector protector = new TechnicalTokenProtector();
        private int invocations;

        @Override
        public TranslationResult translate(
                String sourceText, LanguageTag sourceLang, LanguageTag targetLang, boolean sensitive) {
            invocations++;
            String translated = fakeTranslate(sourceText, targetLang, protector);
            return new TranslationResult(translated, true, TranslationOutcome.TRANSLATED);
        }

        @Override
        public TranslationResult translateInbound(String sourceText, LanguageTag sourceLang) {
            invocations++;
            String translated = fakeTranslate(sourceText, SupportedLanguages.ENGLISH, protector);
            return new TranslationResult(translated, true, TranslationOutcome.TRANSLATED);
        }

        int invocationCount() {
            return invocations;
        }
    }

    /**
     * A stub {@link TranslationService} modelling a translation fall-through: it returns the original
     * English text with a non-{@code TRANSLATED} outcome (provider error or timeout), so the decorator
     * must deliver the original English message.
     */
    private static final class FailureTranslationService implements TranslationService {
        private final TranslationOutcome outcome;

        FailureTranslationService(TranslationOutcome outcome) {
            this.outcome = outcome;
        }

        @Override
        public TranslationResult translate(
                String sourceText, LanguageTag sourceLang, LanguageTag targetLang, boolean sensitive) {
            // Fall-through: the presented text is the original English Source_Text unchanged.
            return new TranslationResult(sourceText, false, outcome);
        }

        @Override
        public TranslationResult translateInbound(String sourceText, LanguageTag sourceLang) {
            // Fall-through: the presented text is the original Source_Text unchanged.
            return new TranslationResult(sourceText, false, outcome);
        }
    }

    // ---- Helpers ----------------------------------------------------------------------------------

    /**
     * Produces a deterministic token-preserving "translation": prose is upper-cased and tagged with
     * the target language so the result differs from the English input, while every Technical_Token
     * is restored byte-for-byte via the {@link TechnicalTokenProtector}.
     */
    private static String fakeTranslate(String english, LanguageTag target, TechnicalTokenProtector protector) {
        MaskedText masked = protector.mask(english);
        String rewrittenProse = "[" + target.value() + "] " + masked.masked().toUpperCase(Locale.ROOT);
        return protector.restore(rewrittenProse, masked);
    }

    private static AlertEvent alert(String message) {
        return new AlertEvent(
                AlertEvent.TYPE_SESSION_ANOMALY, "alice", NOW, true, message, List.of(0.8, 0.85, 0.9));
    }

    private static void sendQuietly(TranslatingLiveEventSink sink, LiveEvent event) {
        try {
            sink.send(event);
        } catch (Exception e) {
            throw new AssertionError("in-memory delegate send must not throw", e);
        }
    }

    // ---- Generators -------------------------------------------------------------------------------

    @Provide
    Arbitrary<String> alertMessagesWithTokens() {
        // Interleave Technical_Tokens with prose; the property Assumes at least one token is present.
        Arbitrary<String> parts = Arbitraries.oneOf(technicalTokens(), proseWords());
        return parts.list().ofMinSize(3).ofMaxSize(10).map(list -> String.join(" ", list));
    }

    @Provide
    Arbitrary<String> alertMessages() {
        // Any non-empty alert message: plain prose, or prose mixed with Technical_Tokens.
        Arbitrary<String> prose = Arbitraries.strings().ascii().ofMinLength(1).ofMaxLength(60);
        Arbitrary<String> mixed = Combinators.combine(
                        technicalTokens().list().ofMinSize(1).ofMaxSize(4),
                        proseWords().list().ofMinSize(1).ofMaxSize(4))
                .as((tokens, prosey) -> {
                    List<String> all = new ArrayList<>(prosey);
                    all.addAll(tokens);
                    return String.join(" ", all);
                });
        return Arbitraries.oneOf(prose, mixed);
    }

    @Provide
    Arbitrary<String> technicalTokens() {
        return Arbitraries.of(
                "git status", "kubectl apply", "rm -rf /tmp/cache", "docker ps",
                "/etc/passwd", "/var/log/syslog", "src/main/App.java",
                "db.prod.internal", "api.service.io", "example.com",
                "https://example.com/data", "10.0.0.1",
                "0.91", "0.87", "42", "2024-01-15T02:30:00Z",
                "DUAL_CONTROL_REQUIRED", "BLAST_RADIUS_EXCEEDED",
                "session-42", "IntentSessionManager.open()");
    }

    @Provide
    Arbitrary<String> proseWords() {
        return Arbitraries.of("the", "operator", "should", "review", "and", "then",
                "please", "confirm", "before", "session", "alert", "message", "about", "with");
    }

    @Provide
    Arbitrary<LanguageTag> nonEnglishTargets() {
        return Arbitraries.of("hi", "bn", "te", "mr", "ta", "gu", "kn", "ml", "pa", "or")
                .map(LanguageTag::of);
    }

    @Provide
    Arbitrary<TranslationOutcome> failureOutcomes() {
        return Arbitraries.of(TranslationOutcome.PROVIDER_ERROR, TranslationOutcome.PROVIDER_TIMEOUT);
    }
}
