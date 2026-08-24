package com.intentguard.e2e;

import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Loads and provides access to the command corpus from JSON.
 * The corpus maps English Linux commands to their Indian-language equivalents,
 * keyed by BCP-47 language tag.
 */
public final class CommandCorpus {

    private static final String CORPUS_RESOURCE = "/e2e/commands/corpus.json";

    private final List<Entry> entries;

    private CommandCorpus(List<Entry> entries) {
        this.entries = Collections.unmodifiableList(entries);
    }

    /**
     * A single corpus entry: an English command and its translations keyed by language tag.
     */
    public record Entry(int index, String englishCommand, Map<String, String> translations) {}

    /**
     * Loads the corpus from classpath resource.
     *
     * @throws IllegalStateException if the resource is missing or empty (Req 6.5)
     */
    public static CommandCorpus load() {
        try (InputStream is = CommandCorpus.class.getResourceAsStream(CORPUS_RESOURCE)) {
            if (is == null) {
                throw new IllegalStateException(
                    "Command corpus resource not found at classpath: " + CORPUS_RESOURCE);
            }

            ObjectMapper mapper = new ObjectMapper();
            CorpusFile corpusFile = mapper.readValue(is, CorpusFile.class);

            if (corpusFile.commands() == null || corpusFile.commands().isEmpty()) {
                throw new IllegalStateException(
                    "Command corpus is empty at classpath: " + CORPUS_RESOURCE);
            }

            List<Entry> entries = corpusFile.commands().stream()
                .map(cmd -> new Entry(
                    cmd.index(),
                    cmd.english(),
                    cmd.translations() != null ? Map.copyOf(cmd.translations()) : Map.of()
                ))
                .toList();

            return new CommandCorpus(entries);

        } catch (IOException e) {
            throw new IllegalStateException(
                "Failed to parse command corpus at classpath: " + CORPUS_RESOURCE, e);
        }
    }

    /**
     * All entries in the corpus.
     */
    public List<Entry> entries() {
        return entries;
    }

    /**
     * Entries filtered by language tag (non-empty translation for that language).
     */
    public List<Entry> forLanguage(String languageTag) {
        return entries.stream()
            .filter(entry -> {
                String translation = entry.translations().get(languageTag);
                return translation != null && !translation.isBlank();
            })
            .toList();
    }

    /**
     * Total number of test cases: for each entry, count how many language translations
     * have non-empty values, and sum across all entries.
     */
    public int totalTestCases() {
        return entries.stream()
            .mapToInt(entry -> (int) entry.translations().entrySet().stream()
                .filter(e -> e.getValue() != null && !e.getValue().isBlank())
                .count())
            .sum();
    }

    // --- Jackson deserialization model ---

    private record CorpusFile(@JsonProperty("commands") List<CommandEntry> commands) {}

    private record CommandEntry(
        @JsonProperty("index") int index,
        @JsonProperty("english") String english,
        @JsonProperty("translations") Map<String, String> translations
    ) {}
}
