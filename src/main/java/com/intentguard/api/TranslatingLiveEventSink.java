package com.intentguard.api;

import java.io.IOException;
import java.util.Objects;

import com.intentguard.translation.LanguageTag;
import com.intentguard.translation.SupportedLanguages;
import com.intentguard.translation.TranslationOutcome;
import com.intentguard.translation.TranslationResult;
import com.intentguard.translation.TranslationService;

/**
 * A per-operator {@link LiveEventSink} decorator that translates outbound live alerts into the
 * subscribing Operator's {@code Language_Preference} just before delivery (Req 2.1, 2.6, 9.2).
 *
 * <p>Because {@link LivePushService} fans every {@link LiveEvent} out to all subscribers as a
 * broadcast, but translation is a <em>per-operator</em> concern (each Operator has their own
 * {@code Language_Preference}), the cleanest seam is to wrap each subscriber's transport sink with
 * this decorator. On {@link #send(LiveEvent)} it inspects the envelope:
 *
 * <ul>
 *   <li>if the event is an {@code ALERT} and the operator's preference is a non-English
 *       {@code Supported_Language}, it translates the {@link AlertEvent#message()} via the
 *       {@link TranslationService} (which preserves every Technical_Token byte-for-byte) and
 *       forwards a rewritten envelope carrying the {@code Translated_Text};</li>
 *   <li>otherwise — English preference, a non-alert envelope, or any translation fall-through —
 *       it forwards the <strong>original</strong> English envelope unchanged.</li>
 * </ul>
 *
 * <p>The feature's overarching rule, <strong>fail to English, never block the operator</strong>,
 * governs failures here too: only a {@link TranslationOutcome#TRANSLATED} or
 * {@link TranslationOutcome#CACHED} result is delivered as a translation; every other outcome (and
 * any unexpected runtime failure while translating) forwards the original English message. The
 * decorator never throws across {@code send} beyond what the wrapped delegate throws, so a failed
 * delivery is still surfaced to {@link LivePushService} for subscriber cleanup.
 */
public final class TranslatingLiveEventSink implements LiveEventSink {

    private final LiveEventSink delegate;
    private final LanguageTag preference;
    private final TranslationService translationService;

    /**
     * @param delegate           the wrapped transport sink events are forwarded to; must not be null
     * @param preference         the subscribing operator's {@code Language_Preference}; must not be null
     * @param translationService the orchestrator used to translate alert messages; must not be null
     */
    public TranslatingLiveEventSink(
            LiveEventSink delegate, LanguageTag preference, TranslationService translationService) {
        this.delegate = Objects.requireNonNull(delegate, "delegate must not be null");
        this.preference = Objects.requireNonNull(preference, "preference must not be null");
        this.translationService =
                Objects.requireNonNull(translationService, "translationService must not be null");
    }

    @Override
    public void send(LiveEvent event) throws IOException {
        delegate.send(translateIfNeeded(event));
    }

    /**
     * Returns the envelope to deliver: a rewritten {@code ALERT} envelope whose message is the
     * Translated_Text when the preference is non-English and translation succeeds, otherwise the
     * original envelope unchanged.
     */
    private LiveEvent translateIfNeeded(LiveEvent event) {
        if (event == null || !LiveEvent.TYPE_ALERT.equals(event.type())) {
            return event;
        }
        if (isEnglish(preference)) {
            return event;
        }
        if (!(event.payload() instanceof AlertEvent alert)) {
            return event;
        }
        String englishMessage = alert.message();
        if (englishMessage == null || englishMessage.isEmpty()) {
            return event;
        }

        TranslationResult result;
        try {
            result = translationService.translate(englishMessage, SupportedLanguages.ENGLISH, preference);
        } catch (RuntimeException translationFailure) {
            // Fail to English: any unexpected failure delivers the original English message (Req 2.6).
            return event;
        }

        if (result == null || !isTranslated(result.outcome())) {
            // Fall-through outcome (timeout, error, unsupported, token-integrity): deliver English.
            return event;
        }

        AlertEvent translated = new AlertEvent(
                alert.alertType(),
                alert.userId(),
                alert.timestamp(),
                alert.highRisk(),
                result.text(),
                alert.evidenceDeviations());
        return LiveEvent.alert(translated);
    }

    private static boolean isEnglish(LanguageTag tag) {
        return SupportedLanguages.ENGLISH.equals(tag);
    }

    private static boolean isTranslated(TranslationOutcome outcome) {
        return outcome == TranslationOutcome.TRANSLATED || outcome == TranslationOutcome.CACHED;
    }
}
