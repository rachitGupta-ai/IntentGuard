package com.intentguard.assist;

/**
 * Orchestrator for the NL Operations Assistant. Coordinates translation, generation,
 * safety filtering, intent session management, scoring, and execution.
 *
 * <p>Implementations manage the full lifecycle of an assist interaction:
 * <ol>
 *     <li>{@link #query} — accept a natural-language query, translate if needed, generate alternatives</li>
 *     <li>{@link #select} — score a chosen alternative through the safety pipeline</li>
 *     <li>{@link #confirm} — execute the command after explicit operator confirmation</li>
 *     <li>{@link #closeSession} — end the session and release associated resources</li>
 * </ol>
 */
public interface NlAssistService {

    /**
     * Processes a natural-language query: translates if needed, generates 2–3 alternatives,
     * filters through the blocklist, and auto-opens/reuses an Intent_Session.
     *
     * @param operatorId the operator submitting the query
     * @param request    the assist request containing the query text, optional language tag,
     *                   and optional session ID for multi-turn continuation
     * @return the assist response with session ID, English query echo, and generated alternatives
     * @throws AssistTranslationException  if translation fails for a non-English query
     * @throws AssistGenerationException   if LLM generation fails or times out
     * @throws AssistBlocklistException    if all generated alternatives are blocked by safety filters
     * @throws AssistRateLimitException    if the operator exceeds the configured rate limit
     */
    AssistResponse query(String operatorId, AssistRequest request);

    /**
     * Scores a selected command alternative through the full safety pipeline, including
     * divergence scoring, blast-radius guardrails, and decision engine evaluation.
     *
     * @param operatorId the operator making the selection
     * @param request    the selection request containing the session ID and command index
     * @return the selection response with composite score, corrective action, explanation,
     *         and whether execution is blocked
     * @throws AssistSessionNotFoundException if the session does not exist or has expired
     * @throws IllegalArgumentException      if commandIndex is out of range for the current alternatives
     */
    SelectResponse select(String operatorId, SelectRequest request);

    /**
     * Executes a previously scored command after explicit operator confirmation.
     * Only commands with an ALLOW or ASK decision may be executed; BLOCK decisions
     * are rejected.
     *
     * @param operatorId the operator confirming execution
     * @param request    the confirmation request containing the session ID and command index
     * @return the execution response with stdout, stderr, exit code, and optional follow-up suggestion
     * @throws AssistSessionNotFoundException if the session does not exist or has expired
     * @throws AssistBlockedException         if the command was BLOCKed and cannot be executed
     */
    ConfirmResponse confirm(String operatorId, ConfirmRequest request);

    /**
     * Closes an assist session and its associated Intent_Session, releasing all
     * held resources.
     *
     * @param operatorId the operator closing the session
     * @param sessionId  the identifier of the session to close
     */
    void closeSession(String operatorId, String sessionId);
}
