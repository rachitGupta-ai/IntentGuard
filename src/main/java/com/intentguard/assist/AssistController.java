package com.intentguard.assist;

import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;

/**
 * REST controller for the NL Operations Assistant.
 *
 * <p>Exposes four operations:
 * <ul>
 *   <li>{@code POST /api/assist} — submit a natural-language query and receive scored command
 *       alternatives (Req 9.1);</li>
 *   <li>{@code POST /api/assist/select} — select an alternative and receive its safety score and
 *       corrective action (Req 9.2);</li>
 *   <li>{@code POST /api/assist/confirm} — confirm execution of a previously scored command
 *       (Req 9.3);</li>
 *   <li>{@code DELETE /api/assist/sessions/{sessionId}} — close an assist session and release
 *       resources (Req 9.4).</li>
 * </ul>
 *
 * <p>Each endpoint requires the {@code X-Operator-Id} header identifying the calling operator.
 * Validation failures in request bodies are caught by the
 * {@link #onValidation(IllegalArgumentException)} handler and returned as HTTP 400 (Req 9.5).
 */
@RestController
@RequestMapping("/api/assist")
public class AssistController {

    private final NlAssistService assistService;

    public AssistController(NlAssistService assistService) {
        this.assistService = assistService;
    }

    /**
     * Accepts a natural-language query and returns 2–3 generated command alternatives.
     */
    @PostMapping
    public ResponseEntity<AssistResponse> query(
            @RequestBody @Valid AssistRequest request,
            @RequestHeader("X-Operator-Id") String operatorId) {
        AssistResponse response = assistService.query(operatorId, request);
        return ResponseEntity.ok(response);
    }

    /**
     * Scores a selected command alternative through the full safety pipeline.
     */
    @PostMapping("/select")
    public ResponseEntity<SelectResponse> select(
            @RequestBody @Valid SelectRequest request,
            @RequestHeader("X-Operator-Id") String operatorId) {
        SelectResponse response = assistService.select(operatorId, request);
        return ResponseEntity.ok(response);
    }

    /**
     * Executes a previously scored command after explicit operator confirmation.
     */
    @PostMapping("/confirm")
    public ResponseEntity<ConfirmResponse> confirm(
            @RequestBody @Valid ConfirmRequest request,
            @RequestHeader("X-Operator-Id") String operatorId) {
        ConfirmResponse response = assistService.confirm(operatorId, request);
        return ResponseEntity.ok(response);
    }

    /**
     * Closes an assist session and its associated Intent_Session, releasing all held resources.
     */
    @DeleteMapping("/sessions/{sessionId}")
    public ResponseEntity<Void> closeSession(
            @PathVariable String sessionId,
            @RequestHeader("X-Operator-Id") String operatorId) {
        assistService.closeSession(operatorId, sessionId);
        return ResponseEntity.noContent().build();
    }

    // ---- Exception handlers ----

    /**
     * Maps rate-limit violations to HTTP 429 with the retry-after hint (Req 8.2).
     */
    @ExceptionHandler(AssistRateLimitException.class)
    public ResponseEntity<Map<String, String>> onRateLimit(AssistRateLimitException ex) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .body(Map.of("error", ex.getMessage(),
                        "retryAfterMs", String.valueOf(ex.getRetryAfterMs())));
    }

    /**
     * Maps validation failures (including record compact-constructor checks) to HTTP 400 (Req 9.5).
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> onValidation(IllegalArgumentException ex) {
        return ResponseEntity.badRequest()
                .body(Map.of("error", ex.getMessage()));
    }

    /**
     * Maps session-not-found to HTTP 404.
     */
    @ExceptionHandler(AssistSessionNotFoundException.class)
    public ResponseEntity<Map<String, String>> onSessionNotFound(AssistSessionNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Map.of("error", ex.getMessage()));
    }

    /**
     * Maps blocked-execution attempts to HTTP 403.
     */
    @ExceptionHandler(AssistBlockedException.class)
    public ResponseEntity<Map<String, String>> onBlocked(AssistBlockedException ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(Map.of("error", ex.getMessage()));
    }

    /**
     * Maps translation failures to HTTP 502 (upstream dependency failure).
     */
    @ExceptionHandler(AssistTranslationException.class)
    public ResponseEntity<Map<String, String>> onTranslationFailure(AssistTranslationException ex) {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(Map.of("error", ex.getMessage()));
    }

    /**
     * Maps LLM generation failures to HTTP 502 (upstream dependency failure). The internal cause
     * (e.g. an empty/timed-out LLM response) is preserved as {@code detail} for logs/debugging,
     * while {@code error} carries a clean, operator-facing message so a cold or unavailable LLM
     * degrades to a clear "try again" rather than leaking a provider-internal string.
     */
    @ExceptionHandler(AssistGenerationException.class)
    public ResponseEntity<Map<String, String>> onGenerationFailure(AssistGenerationException ex) {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(Map.of(
                        "error", "The assistant is temporarily unavailable (the language model did not respond). Please try again in a moment.",
                        "detail", ex.getMessage() == null ? "" : ex.getMessage()));
    }

    /**
     * Maps blocklist rejection (all alternatives blocked) to HTTP 422 Unprocessable Entity.
     */
    @ExceptionHandler(AssistBlocklistException.class)
    public ResponseEntity<Map<String, String>> onBlocklistRejection(AssistBlocklistException ex) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(Map.of("error", ex.getMessage()));
    }
}
