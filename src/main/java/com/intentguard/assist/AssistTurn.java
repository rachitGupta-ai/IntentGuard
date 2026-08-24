package com.intentguard.assist;

import java.util.List;

/**
 * A single query-response-outcome turn within an AssistSession.
 *
 * @param queryEnglish    the English query text (post-translation)
 * @param alternatives    the generated alternatives for this turn
 * @param selectedIndex   the index the operator selected (null if not yet selected)
 * @param executionResult the execution outcome (null if not yet executed)
 * @param timestamp       when this turn occurred (UTC millis)
 */
public record AssistTurn(
        String queryEnglish,
        List<CommandAlternative> alternatives,
        Integer selectedIndex,
        ExecutionResult executionResult,
        long timestamp) {}
