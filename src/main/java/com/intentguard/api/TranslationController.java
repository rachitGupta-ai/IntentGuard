package com.intentguard.api;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.intentguard.translation.LanguagePreferenceService;
import com.intentguard.translation.LanguagePreferenceUpdate;
import com.intentguard.translation.LanguageTag;
import com.intentguard.translation.SupportedLanguages;
import com.intentguard.translation.TranslationResult;
import com.intentguard.translation.TranslationService;

/**
 * REST surface for on-demand outbound text translation and Language_Preference selection.
 *
 * <p>Exposes two Operator operations (task 12.2):
 * <ul>
 *   <li>{@code POST /api/content/translate} — on-demand outbound translation of an item of
 *       Operator_Facing_Content into a target Supported_Language, preserving every Technical_Token
 *       and falling back to English on any timeout/error/unsupported-language path; the response
 *       carries the presented text and the classified {@code TranslationOutcome} (Req 2.1).</li>
 *   <li>{@code PUT /api/preferences/language} — sets an Operator's Language_Preference; a
 *       Supported_Language selection returns HTTP 200 with the effective preference (indicating
 *       not-saved when persistence failed and only the session selection is held, Req 1.6), while a
 *       tag outside the Supported_Language set is rejected with HTTP 422 and the current preference
 *       is retained (Req 1.1, 1.2, 1.5).</li>
 * </ul>
 *
 * <p><strong>SECURITY — UNAUTHENTICATED PROTOTYPE ENDPOINTS.</strong> Like
 * {@link ControlTowerController} and {@link IntentSessionController}, these endpoints act on behalf
 * of an operator (translating content and mutating a stored Language_Preference) but currently have
 * <em>no authentication or authorization</em> layer. This is acceptable only for the hackathon
 * prototype. Per the reference-monitor trust model, before any non-prototype use these endpoints
 * MUST be protected — e.g. bound to a loopback/OS-restricted interface owned by the
 * {@code intentguard} service account, and/or placed behind an authenticating filter (mTLS, signed
 * admin token, or a reverse proxy enforcing operator identity). Do not expose this controller on an
 * untrusted network as-is. To avoid leaking sensitive content, this controller never logs the
 * Source_Text or Translated_Text in the clear (Req 11).
 */
@RestController
@RequestMapping("/api")
public class TranslationController {

    private static final String DEFAULT_OPERATOR = "admin";

    private final TranslationService translationService;
    private final LanguagePreferenceService languagePreferenceService;

    public TranslationController(
            TranslationService translationService,
            LanguagePreferenceService languagePreferenceService) {
        this.translationService = translationService;
        this.languagePreferenceService = languagePreferenceService;
    }

    /**
     * Translates an item of Operator_Facing_Content into {@code targetLanguageTag}, preserving every
     * Technical_Token and honouring the sensitive-content gate; the presented text and classified
     * outcome are returned (Req 2.1). A {@code null}/blank {@code sourceLanguageTag} defaults to
     * English (the common outbound case). The Translation_Service never throws across its boundary,
     * so this always returns HTTP 200 with a presentable text.
     */
    @PostMapping("/content/translate")
    public ResponseEntity<TranslatedContentResponse> translateContent(
            @RequestBody TranslateContentRequest request) {
        LanguageTag sourceLanguage = normalizeSourceLanguage(request.sourceLanguageTag());
        LanguageTag targetLanguage = parseTargetLanguage(request.targetLanguageTag());

        TranslationResult result = translationService.translate(
                request.content(), sourceLanguage, targetLanguage, request.sensitive());
        return ResponseEntity.ok(TranslatedContentResponse.from(result));
    }

    /**
     * Sets an Operator's Language_Preference (Req 1.1, 1.2). A Supported_Language selection returns
     * HTTP 200 with the effective preference (the {@code saved} flag is {@code false} when only the
     * session selection is held after a persistence failure, Req 1.6); a tag outside the
     * Supported_Language set is rejected with HTTP 422 and the current preference is retained
     * (Req 1.5).
     */
    @PutMapping("/preferences/language")
    public ResponseEntity<LanguagePreferenceView> setLanguagePreference(
            @RequestBody LanguagePreferenceRequest request) {
        String operatorId = (request.operatorId() == null || request.operatorId().isBlank())
                ? DEFAULT_OPERATOR
                : request.operatorId();
        LanguageTag requestedTag = parseRequestedLanguage(request.languageTag());

        LanguagePreferenceUpdate update =
                languagePreferenceService.setPreference(operatorId, requestedTag);
        LanguagePreferenceView body = LanguagePreferenceView.from(operatorId, update);

        if (update.accepted()) {
            // SAVED or SAVED_IN_SESSION_ONLY: the selection is in effect for subsequent content.
            return ResponseEntity.ok(body);
        }
        // REJECTED_UNSUPPORTED: the current preference was retained (Req 1.5).
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(body);
    }

    /**
     * Maps a blank/{@code null} source-language tag to English (the outbound default); otherwise
     * parses the BCP-47 tag. The Translation_Service applies the unsupported-language guard.
     */
    private static LanguageTag normalizeSourceLanguage(String sourceLanguageTag) {
        if (sourceLanguageTag == null || sourceLanguageTag.isBlank()) {
            return SupportedLanguages.ENGLISH;
        }
        return LanguageTag.of(sourceLanguageTag);
    }

    private static LanguageTag parseTargetLanguage(String targetLanguageTag) {
        if (targetLanguageTag == null || targetLanguageTag.isBlank()) {
            throw new IllegalArgumentException("targetLanguageTag must be provided");
        }
        return LanguageTag.of(targetLanguageTag);
    }

    private static LanguageTag parseRequestedLanguage(String languageTag) {
        if (languageTag == null || languageTag.isBlank()) {
            throw new IllegalArgumentException("languageTag must be provided");
        }
        return LanguageTag.of(languageTag);
    }
}
