package com.intentguard.intent;

import java.util.Objects;

import org.springframework.stereotype.Service;

import com.intentguard.domain.Actor;
import com.intentguard.translation.LanguagePreferenceService;
import com.intentguard.translation.LanguageTag;
import com.intentguard.translation.SupportedLanguages;
import com.intentguard.translation.TranslationResult;
import com.intentguard.translation.TranslationService;

/**
 * Orchestrates an inbound Declared_Intent submission: it translates a non-English intent to the
 * Engine_Language (English) and then opens the Intent_Session on the English text, recording both
 * texts, or rejects the submission when translation fails (Req 3.1-3.4).
 *
 * <p>The flow is the seam between the {@code Control_Tower} input surface (task 12.1) and the
 * existing {@link IntentSessionManager}, keeping the Enforcement_Engine reading only Engine_Language
 * text (Req 7.2, 7.3): translation happens <em>before</em> {@code open(...)} and the localized /
 * original Source_Text is never routed into scoring — it is only recorded alongside the English text
 * for audit (Req 3.2, 10.4).
 *
 * <ul>
 *   <li><strong>English submission</strong> - opens the session directly on the English text; the
 *       original Source_Text is left {@code null} and the tag defaults to {@code "en"}.</li>
 *   <li><strong>Non-English Supported_Language</strong> - translates the Source_Text to English via
 *       {@link TranslationService#translateInbound}; on success opens the session on the English text
 *       and records both the Source_Text and its language tag (Req 3.1, 3.2, 10.4).</li>
 *   <li><strong>Translation failure</strong> - a provider timeout/error, a lost Technical_Token, or
 *       an unsupported source language yields no usable Engine_Language text, so the submission is
 *       rejected, <strong>no</strong> session is opened, and a prompt in the Operator's
 *       Language_Preference to retry or submit in English is returned (Req 3.3, 3.4).</li>
 * </ul>
 */
@Service
public class InboundIntentService {

    private final TranslationService translationService;
    private final IntentSessionManager sessionManager;
    private final LanguagePreferenceService languagePreferenceService;

    public InboundIntentService(
            TranslationService translationService,
            IntentSessionManager sessionManager,
            LanguagePreferenceService languagePreferenceService) {
        this.translationService =
                Objects.requireNonNull(translationService, "translationService must not be null");
        this.sessionManager = Objects.requireNonNull(sessionManager, "sessionManager must not be null");
        this.languagePreferenceService = Objects.requireNonNull(
                languagePreferenceService, "languagePreferenceService must not be null");
    }

    /**
     * Submits a Declared_Intent in {@code sourceLanguageTag} on behalf of {@code operatorId}.
     *
     * @param operatorId        the human operator submitting the intent (also the session user)
     * @param declaredIntent    the Declared_Intent Source_Text as typed/spoken by the operator
     * @param sourceLanguageTag the language the intent was submitted in; {@code null} is treated as
     *                          English
     * @param actor             the requesting actor (an Agent_Actor is rejected by the session
     *                          manager per Req 13.3)
     * @return a {@link InboundIntentResult} describing whether a session was opened (Req 3.1, 3.2) or
     *         the submission was rejected with a localized prompt (Req 3.3, 3.4)
     */
    public InboundIntentResult submit(
            String operatorId, String declaredIntent, LanguageTag sourceLanguageTag, Actor actor) {
        Objects.requireNonNull(operatorId, "operatorId must not be null");
        Objects.requireNonNull(actor, "actor must not be null");
        String intentText = declaredIntent == null ? "" : declaredIntent;
        LanguageTag source = sourceLanguageTag == null ? SupportedLanguages.ENGLISH : sourceLanguageTag;

        // English submission: nothing to translate — open directly on the English text (Req 3.1).
        if (SupportedLanguages.ENGLISH.equals(source)) {
            IntentSession session = sessionManager.open(operatorId, intentText, actor);
            return InboundIntentResult.sessionOpened(session);
        }

        // Non-English: translate the Source_Text to the Engine_Language before opening (Req 3.1).
        TranslationResult translation = translationService.translateInbound(intentText, source);
        switch (translation.outcome()) {
            case TRANSLATED, CACHED -> {
                // Open on the English text and record BOTH texts on the session (Req 3.1, 3.2, 10.4).
                IntentSession session = sessionManager.open(
                        operatorId, translation.text(), intentText, source.value(), actor);
                return InboundIntentResult.sessionOpened(session);
            }
            case ENGLISH_PASSTHROUGH -> {
                // Defensive: the source was effectively English, so there is nothing to record as an
                // original; open directly on the (unchanged) English text.
                IntentSession session = sessionManager.open(operatorId, translation.text(), actor);
                return InboundIntentResult.sessionOpened(session);
            }
            // Provider timeout/error, a lost Technical_Token, or an unsupported source language all
            // mean no usable Engine_Language text was produced: reject the submission, open no
            // session, and prompt (in the Language_Preference) to retry or submit in English
            // (Req 3.3, 3.4).
            default -> {
                LanguageTag preference = languagePreferenceService.getPreference(operatorId);
                return InboundIntentResult.rejected(InboundIntentMessages.translationFailed(preference));
            }
        }
    }
}
