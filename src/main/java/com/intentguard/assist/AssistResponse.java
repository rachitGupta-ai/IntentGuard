package com.intentguard.assist;

import java.util.List;

/**
 * Response body for POST /api/assist.
 *
 * @param sessionId    session identifier
 * @param queryEcho    the English query text used for generation
 * @param alternatives array of generated command alternatives
 */
public record AssistResponse(
        String sessionId,
        String queryEcho,
        List<CommandAlternative> alternatives) {}
