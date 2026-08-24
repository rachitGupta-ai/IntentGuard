package com.intentguard.assist;

import java.util.List;

/**
 * Generates shell command alternatives from a natural-language description.
 * Implementations use LLM to translate intent into executable commands.
 */
public interface CommandGenerator {

    /**
     * Generates 2-3 command alternatives for the given English query.
     *
     * @param queryEnglish the English-language operation description
     * @param context      prior turns for multi-turn context (empty list for first query)
     * @return list of 2-3 generated alternatives
     * @throws AssistGenerationException on LLM failure or timeout
     */
    List<CommandAlternative> generate(String queryEnglish, List<AssistTurn> context);
}
