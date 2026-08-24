package com.intentguard.api;

import java.util.Optional;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.intentguard.domain.Actor;
import com.intentguard.intent.InboundIntentResult;
import com.intentguard.intent.InboundIntentService;
import com.intentguard.intent.IntentSession;
import com.intentguard.intent.IntentSessionManager;
import com.intentguard.persistence.IntentSessionRepository;
import com.intentguard.translation.LanguageTag;

/**
 * REST surface for Intent_Session creation and read-back with inbound-language support.
 *
 * <p>Exposes the Control_Tower's inbound-intent seam (task 12.1):
 * <ul>
 *   <li>{@code POST /api/sessions} — accepts a Declared_Intent plus its {@code sourceLanguageTag}
 *       (BCP-47) and runs the inbound-text flow via {@link InboundIntentService}: a non-English
 *       intent is translated to the Engine_Language (English) before the session opens, recording
 *       both texts; a successful open returns HTTP 201 with the opened session (including the
 *       original Source_Text and its language tag alongside the English translation), and a
 *       translation failure returns HTTP 422 with a localized retry/English prompt and opens no
 *       session (Req 3.1, 3.3, 3.4).</li>
 *   <li>{@code GET /api/sessions/{sessionId}} and {@code GET /api/sessions?userId=} — return the
 *       Intent_Session including the original Declared_Intent Source_Text alongside its English
 *       translation so an audit query returns both texts (Req 10.4).</li>
 * </ul>
 *
 * <p><strong>SECURITY — UNAUTHENTICATED ADMIN ENDPOINTS.</strong> Like {@link ControlTowerController},
 * these endpoints perform privileged operator actions (opening enforcement Intent_Sessions and
 * reading their recorded intent text) but currently have <em>no authentication or authorization</em>
 * layer. This is acceptable only for the hackathon prototype. Per the reference-monitor trust model,
 * before any non-prototype use these endpoints MUST be protected — e.g. bound to a
 * loopback/OS-restricted interface owned by the {@code intentguard} service account, and/or placed
 * behind an authenticating filter (mTLS, signed admin token, or a reverse proxy enforcing operator
 * identity). Do not expose this controller on an untrusted network as-is. To avoid leaking
 * sensitive input, this controller never logs the Declared_Intent Source_Text, recognized speech
 * text, or any audio in the clear (Req 11).
 */
@RestController
@RequestMapping("/api/sessions")
public class IntentSessionController {

    private static final String DEFAULT_OPERATOR = "admin";

    private final InboundIntentService inboundIntentService;
    private final IntentSessionManager sessionManager;
    private final IntentSessionRepository sessionRepository;

    public IntentSessionController(
            InboundIntentService inboundIntentService,
            IntentSessionManager sessionManager,
            IntentSessionRepository sessionRepository) {
        this.inboundIntentService = inboundIntentService;
        this.sessionManager = sessionManager;
        this.sessionRepository = sessionRepository;
    }

    /**
     * Opens an Intent_Session from an operator-submitted Declared_Intent, translating a non-English
     * Source_Text to the Engine_Language first (Req 3.1). A successful open returns HTTP 201 with the
     * opened session (both texts recorded); a translation failure returns HTTP 422 with the localized
     * retry/English prompt and opens no session (Req 3.3, 3.4, 10.4).
     */
    @PostMapping
    public ResponseEntity<?> openSession(@RequestBody OpenSessionRequest request) {
        String operatorId = (request.operatorId() == null || request.operatorId().isBlank())
                ? DEFAULT_OPERATOR
                : request.operatorId();
        LanguageTag sourceLanguage = normalizeSourceLanguage(request.sourceLanguageTag());

        InboundIntentResult result = inboundIntentService.submit(
                operatorId, request.declaredIntent(), sourceLanguage, Actor.human(operatorId));

        if (result.opened()) {
            IntentSession session = result.openedSession().orElseThrow();
            return ResponseEntity.status(HttpStatus.CREATED).body(SessionView.from(session));
        }
        // Translation failed: reject the submission (no session opened) with the localized prompt.
        String message = result.messageText().orElse("Translation failed.");
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(new RejectedSubmissionResponse(message));
    }

    /**
     * Returns the Intent_Session identified by {@code sessionId}, exposing the original
     * Declared_Intent Source_Text alongside its English translation (Req 10.4); HTTP 404 when no
     * such session exists.
     */
    @GetMapping("/{sessionId}")
    public ResponseEntity<SessionView> getSession(@PathVariable String sessionId) {
        return sessionRepository
                .findBySessionId(sessionId)
                .map(document -> ResponseEntity.ok(SessionView.from(document)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * Returns the currently open Intent_Session for {@code userId}, exposing both the original
     * Source_Text and its English translation (Req 10.4); HTTP 404 when the user has no open session.
     */
    @GetMapping
    public ResponseEntity<SessionView> getActiveSession(@RequestParam String userId) {
        Optional<IntentSession> active = sessionManager.activeSessionFor(userId);
        return active.map(session -> ResponseEntity.ok(SessionView.from(session)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * Maps a blank/{@code null} source-language tag to English (an English submission has nothing to
     * translate); otherwise parses the BCP-47 tag. {@link InboundIntentService} applies the
     * unsupported-language guard, so any non-English tag is validated there.
     */
    private static LanguageTag normalizeSourceLanguage(String sourceLanguageTag) {
        if (sourceLanguageTag == null || sourceLanguageTag.isBlank()) {
            return null;
        }
        return LanguageTag.of(sourceLanguageTag);
    }
}
