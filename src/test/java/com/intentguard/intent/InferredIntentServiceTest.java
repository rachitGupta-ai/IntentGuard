package com.intentguard.intent;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.mock;

import com.intentguard.domain.CommandEvent;
import com.intentguard.domain.Decision;
import com.intentguard.domain.DivergenceResult;
import com.intentguard.llm.LlmService;
import com.intentguard.persistence.BehavioralProfileDocument;
import com.intentguard.persistence.BehavioralProfileRepository;
import com.intentguard.profile.BehavioralProfileManager;
import com.mongodb.client.MongoDatabase;

/**
 * Unit tests for {@link InferredIntentService} (Req 14.1): feature-flag gating, derivation from
 * recent command statistics via LLM summarization, and graceful degradation when the user has no
 * history or the LLM is unavailable.
 */
class InferredIntentServiceTest {

    @Test
    void disabledFlagNeverDerivesIntent() {
        FakeProfileRepository repo = new FakeProfileRepository();
        repo.seed("alice", Map.of("git", 10, "kubectl", 5));
        BehavioralProfileManager profileManager = new BehavioralProfileManager(repo);
        RecordingLlmService llm = new RecordingLlmService(Optional.of("deploy the service"));

        InferredIntentService service = new InferredIntentService(profileManager, llm, /* enabled */ false);

        assertThat(service.isEnabled()).isFalse();
        assertThat(service.deriveInferredIntent("alice")).isEmpty();
        // The LLM must not even be consulted when the flag is off.
        assertThat(llm.summarizeCalled).isFalse();
    }

    @Test
    void enabledWithRecentCommandsDerivesIntentFromSummary() {
        FakeProfileRepository repo = new FakeProfileRepository();
        // Insertion order deliberately not by frequency, to assert frequency-desc ordering.
        Map<String, Integer> vocab = new LinkedHashMap<>();
        vocab.put("ls", 2);
        vocab.put("git", 50);
        vocab.put("kubectl", 20);
        repo.seed("bob", vocab);
        BehavioralProfileManager profileManager = new BehavioralProfileManager(repo);
        RecordingLlmService llm = new RecordingLlmService(Optional.of("  working on git deployments  "));

        InferredIntentService service = new InferredIntentService(profileManager, llm, /* enabled */ true);

        Optional<String> inferred = service.deriveInferredIntent("bob");

        assertThat(inferred).contains("working on git deployments"); // trimmed
        assertThat(llm.summarizeCalled).isTrue();
        // Recent command window is the vocabulary sorted by frequency, highest first.
        assertThat(llm.lastRecentCommands).containsExactly("git", "kubectl", "ls");
    }

    @Test
    void enabledButNoRecentCommandsDegradesGracefully() {
        FakeProfileRepository repo = new FakeProfileRepository(); // no profile for carol
        BehavioralProfileManager profileManager = new BehavioralProfileManager(repo);
        RecordingLlmService llm = new RecordingLlmService(Optional.of("something"));

        InferredIntentService service = new InferredIntentService(profileManager, llm, /* enabled */ true);

        assertThat(service.deriveInferredIntent("carol")).isEmpty();
        // With no command window there is nothing to summarize.
        assertThat(llm.summarizeCalled).isFalse();
    }

    @Test
    void enabledButLlmUnavailableDegradesGracefully() {
        FakeProfileRepository repo = new FakeProfileRepository();
        repo.seed("dave", Map.of("curl", 3));
        BehavioralProfileManager profileManager = new BehavioralProfileManager(repo);
        RecordingLlmService llm = new RecordingLlmService(Optional.empty()); // LLM returns nothing

        InferredIntentService service = new InferredIntentService(profileManager, llm, /* enabled */ true);

        assertThat(service.deriveInferredIntent("dave")).isEmpty();
        assertThat(llm.summarizeCalled).isTrue();
    }

    @Test
    void enabledButBlankSummaryDegradesGracefully() {
        FakeProfileRepository repo = new FakeProfileRepository();
        repo.seed("erin", Map.of("npm", 4));
        BehavioralProfileManager profileManager = new BehavioralProfileManager(repo);
        RecordingLlmService llm = new RecordingLlmService(Optional.of("   ")); // blank summary

        InferredIntentService service = new InferredIntentService(profileManager, llm, /* enabled */ true);

        assertThat(service.deriveInferredIntent("erin")).isEmpty();
    }

    // --- fakes --------------------------------------------------------------------------------

    /** An {@link LlmService} recording whether summarization was requested and with which window. */
    private static final class RecordingLlmService implements LlmService {
        private final Optional<String> summary;
        private boolean summarizeCalled;
        private List<String> lastRecentCommands;

        RecordingLlmService(Optional<String> summary) {
            this.summary = summary;
        }

        @Override
        public OptionalDouble semanticInconsistency(CommandEvent event, String intentText) {
            return OptionalDouble.empty();
        }

        @Override
        public Optional<String> explain(CommandEvent event, DivergenceResult result, Decision decision) {
            return Optional.empty();
        }

        @Override
        public Optional<String> summarizeIntent(List<String> recentCommands) {
            this.summarizeCalled = true;
            this.lastRecentCommands = recentCommands;
            return summary;
        }
    }

    /** In-memory {@link BehavioralProfileRepository} seeded with per-user command vocabularies. */
    private static final class FakeProfileRepository extends BehavioralProfileRepository {
        private final Map<String, BehavioralProfileDocument> store = new HashMap<>();

        FakeProfileRepository() {
            super(mock(MongoDatabase.class));
        }

        void seed(String userId, Map<String, Integer> vocabulary) {
            BehavioralProfileDocument doc = new BehavioralProfileDocument();
            doc.setUserId(userId);
            doc.setEventCount(vocabulary.values().stream().mapToLong(Integer::longValue).sum());
            doc.setVocabulary(new LinkedHashMap<>(vocabulary));
            store.put(userId, doc);
        }

        @Override
        public Optional<BehavioralProfileDocument> findByUserId(String userId) {
            return Optional.ofNullable(store.get(userId));
        }

        @Override
        public void save(BehavioralProfileDocument profile) {
            store.put(profile.getUserId(), profile);
        }
    }
}
