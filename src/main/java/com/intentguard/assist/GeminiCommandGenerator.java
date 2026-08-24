package com.intentguard.assist;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Gemini-backed {@link CommandGenerator} implementation. Translates natural-language operation
 * descriptions into 2–3 shell command alternatives via the Gemini LLM.
 *
 * <p>The generation prompt constrains output to a JSON array of objects with {@code command} and
 * {@code explanation} fields. Multi-turn context from previous {@link AssistTurn}s is included so
 * follow-up queries can reference prior commands and their outcomes (Req 7.2).
 *
 * <p>Any SDK failure, timeout, or JSON-parse error is wrapped in {@link AssistGenerationException}
 * so callers have a single exception path (Req 2.3).
 */
@Service
public class GeminiCommandGenerator implements CommandGenerator {

    private static final Logger log = System.getLogger(GeminiCommandGenerator.class.getName());

    private static final String SYSTEM_ROLE = """
            You are a Linux command generator. Given a natural-language description of an operation, \
            you produce 2 to 3 valid shell commands that accomplish the described goal. \
            Each command must be a single-line, syntactically valid shell command that can be run \
            on a standard Linux system (bash-compatible).""";

    private static final String OUTPUT_INSTRUCTIONS = """
            Respond ONLY with a JSON array containing 2 to 3 objects. Each object must have exactly \
            two fields:
            - "command": the shell command (string)
            - "explanation": a plain-English description of what the command does and its potential impact (string)
            
            Do NOT include any text outside the JSON array. Do NOT use markdown code fences. \
            Do NOT include comments. Output raw JSON only.""";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final AssistTextGenerator textGenerator;

    public GeminiCommandGenerator(AssistTextGenerator textGenerator) {
        this.textGenerator = textGenerator;
    }

    @Override
    public List<CommandAlternative> generate(String queryEnglish, List<AssistTurn> context) {
        String prompt = buildPrompt(queryEnglish, context);
        try {
            String response = textGenerator.generate(prompt);
            return parseAlternatives(response);
        } catch (AssistGenerationException e) {
            throw e;
        } catch (Exception e) {
            log.log(Level.DEBUG, "Command generation failed", e);
            throw new AssistGenerationException("Command generation failed: " + e.getMessage());
        }
    }

    /**
     * Builds the complete generation prompt including role, output format instructions,
     * multi-turn context, and the current query.
     */
    String buildPrompt(String queryEnglish, List<AssistTurn> context) {
        StringBuilder sb = new StringBuilder();

        sb.append(SYSTEM_ROLE).append("\n\n");
        sb.append(OUTPUT_INSTRUCTIONS).append("\n\n");

        // Include multi-turn context if present
        if (context != null && !context.isEmpty()) {
            sb.append("Previous conversation context:\n");
            for (AssistTurn turn : context) {
                sb.append("- Query: ").append(turn.queryEnglish()).append("\n");
                if (turn.alternatives() != null && !turn.alternatives().isEmpty()) {
                    if (turn.selectedIndex() != null && turn.selectedIndex() < turn.alternatives().size()) {
                        CommandAlternative selected = turn.alternatives().get(turn.selectedIndex());
                        sb.append("  Selected command: ").append(selected.command()).append("\n");
                    }
                }
                if (turn.executionResult() != null) {
                    ExecutionResult result = turn.executionResult();
                    sb.append("  Exit code: ").append(result.exitCode()).append("\n");
                    if (result.exitCode() != 0 && result.stderr() != null && !result.stderr().isBlank()) {
                        // Include error output for follow-up context (truncate if long)
                        String stderr = result.stderr().length() > 200
                                ? result.stderr().substring(0, 200) + "..."
                                : result.stderr();
                        sb.append("  Error: ").append(stderr).append("\n");
                    }
                }
            }
            sb.append("\n");
        }

        sb.append("Operator request: ").append(queryEnglish);

        return sb.toString();
    }

    /**
     * Parses the LLM JSON response into a list of {@link CommandAlternative} records.
     * Expects a JSON array of objects with "command" and "explanation" fields.
     */
    List<CommandAlternative> parseAlternatives(String response) {
        if (response == null || response.isBlank()) {
            throw new AssistGenerationException("Empty response from LLM");
        }

        // Strip markdown code fences if the model wraps the JSON despite instructions
        String json = stripCodeFences(response.trim());

        List<RawAlternative> raw;
        try {
            raw = MAPPER.readValue(json, new TypeReference<List<RawAlternative>>() {});
        } catch (JsonProcessingException e) {
            throw new AssistGenerationException(
                    "Failed to parse LLM response as JSON array: " + e.getMessage());
        }

        if (raw == null || raw.isEmpty()) {
            throw new AssistGenerationException("LLM returned an empty alternatives array");
        }

        List<CommandAlternative> alternatives = new ArrayList<>();
        for (int i = 0; i < raw.size(); i++) {
            RawAlternative entry = raw.get(i);
            if (entry.command == null || entry.command.isBlank()
                    || entry.explanation == null || entry.explanation.isBlank()) {
                continue; // skip malformed entries
            }
            alternatives.add(new CommandAlternative(entry.command.trim(), entry.explanation.trim(), i));
        }

        if (alternatives.isEmpty()) {
            throw new AssistGenerationException("LLM response contained no valid command alternatives");
        }

        return alternatives;
    }

    /**
     * Removes markdown code fences (```json ... ``` or ``` ... ```) that some models add despite
     * explicit instructions not to.
     */
    private static String stripCodeFences(String text) {
        if (text.startsWith("```")) {
            // Remove the opening fence line
            int firstNewline = text.indexOf('\n');
            if (firstNewline >= 0) {
                text = text.substring(firstNewline + 1);
            }
            // Remove the closing fence
            if (text.endsWith("```")) {
                text = text.substring(0, text.length() - 3);
            }
            return text.trim();
        }
        return text;
    }

    /**
     * Internal DTO for Jackson deserialization of the LLM JSON response.
     */
    private static class RawAlternative {
        public String command;
        public String explanation;
    }
}
