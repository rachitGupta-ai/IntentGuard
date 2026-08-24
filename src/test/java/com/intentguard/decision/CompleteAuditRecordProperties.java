package com.intentguard.decision;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
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
import com.intentguard.domain.ProfileState;
import com.intentguard.domain.RawShellSignal;
import com.intentguard.domain.Verdict;
import com.intentguard.explanation.ExplanationGenerator;
import com.intentguard.ingest.ShellSignalNormalizer;
import com.intentguard.intent.DefaultIntentSessionManager;
import com.intentguard.llm.LlmService;
import com.intentguard.persistence.AuditHistoryDocument;
import com.intentguard.persistence.AuditHistoryRepository;
import com.intentguard.persistence.AuditWriteAheadBuffer;
import com.intentguard.persistence.BehavioralProfileDocument;
import com.intentguard.persistence.BehavioralProfileRepository;
import com.intentguard.persistence.ComponentScoreDocument;
import com.intentguard.persistence.IntentSessionDocument;
import com.intentguard.persistence.IntentSessionRepository;
import com.intentguard.persistence.ThresholdConfigRepository;
import com.intentguard.profile.BehavioralProfileManager;
import com.intentguard.scoring.ScoringPipeline;
import com.mongodb.client.MongoDatabase;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.DoubleRange;

/**
 * Feature: intentguard-semantic-firewall, Property 12: Every decision persists a complete audit record.
 *
 * <p>For any Command_Event that reaches a corrective decision, a persisted Audit_History record
 * exists containing the Command_Event, every component score with its applied weight, the composite
 * Divergence_Score, the Corrective_Action, and (for ask/block) the Explanation; in particular every
 * block decision is persisted (Validates: Requirements 5.7, 7.4, 8.3, 11.1).
 *
 * <p>The property drives the fully-wired {@link PipelineDecisionProvider} (Task 13.1) end-to-end
 * over arbitrary {@link RawShellSignal}s (varied user, command text, and input origin) and an
 * arbitrary {@link DivergenceResult} fed through a controllable {@link ScoringPipeline}. Thresholds
 * are fixed at ask=0.4 / block=0.7 while the composite ranges over the whole [0,1] interval, so the
 * generated decisions span ALLOW, ASK, and BLOCK across iterations. An ACTIVE profile is seeded for
 * every actor so a genuine block is never clamped to ask by the learning rule, letting the property
 * assert that <em>every</em> block is persisted. After each decision it flushes the write-ahead
 * buffer and asserts the persisted record is complete: the Command_Event fields, one entry per
 * component carrying its applied weight (excluded components carried with a {@code null} score), the
 * composite Divergence_Score, the Corrective_Action, and a non-empty Explanation for ask/block (and
 * a {@code null} Explanation for allow).
 */
class CompleteAuditRecordProperties {

    private static final double ASK_THRESHOLD = 0.4;
    private static final double BLOCK_THRESHOLD = 0.7;

    @Property(tries = 200)
    void everyDecisionPersistsACompleteAuditRecord(
            @ForAll("userIds") String userId,
            @ForAll("commands") String commandText,
            @ForAll("origins") InputOrigin origin,
            @ForAll @DoubleRange(min = 0.0, max = 1.0) double composite,
            @ForAll("componentPlans") List<ComponentPlan> plans) {

        // Fresh, isolated harness per try (jqwik does not re-run @BeforeEach between tries).
        FakeAuditHistoryRepository auditRepository = new FakeAuditHistoryRepository();
        FakeIntentSessionRepository sessionRepository = new FakeIntentSessionRepository();
        FakeBehavioralProfileRepository profileRepository = new FakeBehavioralProfileRepository();
        AuditWriteAheadBuffer auditBuffer = new AuditWriteAheadBuffer(auditRepository, 100);
        BehavioralProfileManager profileManager = new BehavioralProfileManager(profileRepository);
        DefaultIntentSessionManager intentSessionManager =
                new DefaultIntentSessionManager(sessionRepository, auditRepository);
        ThresholdConfigurationService configService =
                new ThresholdConfigurationService(mock(ThresholdConfigRepository.class));
        ControllableScoringPipeline scoringPipeline = new ControllableScoringPipeline();
        ExplanationGenerator explanationGenerator = new ExplanationGenerator(new NoLlmService());

        // Seed an ACTIVE profile so a would-be block is not clamped to ask by the learning rule.
        seedActiveProfile(profileRepository, userId, 25);
        configService.initialize(config(ASK_THRESHOLD, BLOCK_THRESHOLD, /* learningMinEvents */ 1));

        DivergenceResult scored = buildResult(composite, plans);
        scoringPipeline.willReturn(scored);

        PipelineDecisionProvider provider = new PipelineDecisionProvider(
                new ShellSignalNormalizer(),
                intentSessionManager,
                configService,
                profileManager,
                scoringPipeline,
                new DefaultDecisionEngine(new TamperClassifier()),
                explanationGenerator,
                auditBuffer);

        Verdict verdict = provider.decide(signal(userId, commandText, origin));

        // Flush the bounded write-ahead buffer so the record is durable in the Datastore.
        auditBuffer.flush();

        // The action is determined solely by the composite vs. the thresholds (human actor, no
        // tamper, ACTIVE profile => no override/clamp/containment applies).
        CorrectiveAction expectedAction = expectedAction(composite);
        assertThat(verdict.action()).isEqualTo(expectedAction);

        // Exactly one Audit_History record was persisted for this single decision; in particular
        // every block decision is persisted.
        assertThat(auditRepository.saved()).hasSize(1);
        AuditHistoryDocument record = auditRepository.saved().get(0);

        // (a) The Command_Event is captured.
        assertThat(record.getRecordType()).isEqualTo(PipelineDecisionProvider.RECORD_TYPE_DECISION);
        assertThat(record.getEventId()).isNotBlank();
        assertThat(record.getUserId()).isEqualTo(userId);
        assertThat(record.getCommandText()).isEqualTo(commandText);
        assertThat(record.getInputOrigin()).isEqualTo(origin.name());

        // (b) Every component score is recorded with its applied weight; excluded components are
        // carried with a null score.
        assertThat(record.getComponents()).hasSize(plans.size());
        Map<String, ComponentScoreDocument> byId = new HashMap<>();
        for (ComponentScoreDocument c : record.getComponents()) {
            byId.put(c.getId(), c);
        }
        Set<String> expectedExcluded = new HashSet<>();
        for (ComponentPlan plan : plans) {
            ComponentScoreDocument doc = byId.get(plan.id().name());
            assertThat(doc).as("component %s persisted", plan.id()).isNotNull();
            assertThat(doc.getWeight()).as("weight preserved for %s", plan.id()).isEqualTo(plan.weight());
            if (plan.excluded()) {
                assertThat(doc.getScore()).as("excluded %s carries null score", plan.id()).isNull();
                expectedExcluded.add(plan.id().name());
            } else {
                assertThat(doc.getScore()).as("score preserved for %s", plan.id()).isEqualTo(plan.score());
            }
        }
        assertThat(record.getExcludedComponents()).containsExactlyInAnyOrderElementsOf(expectedExcluded);

        // (c) The composite Divergence_Score is recorded.
        assertThat(record.getDivergenceScore()).isEqualTo(composite);

        // (d) The Corrective_Action is recorded.
        assertThat(record.getCorrectiveAction()).isEqualTo(expectedAction.name());

        // (e) The Explanation is present for ask/block and absent for allow.
        if (expectedAction == CorrectiveAction.ALLOW) {
            assertThat(record.getExplanation()).isNull();
            assertThat(verdict.explanation()).isNull();
        } else {
            assertThat(record.getExplanation()).isNotBlank();
            assertThat(verdict.explanation()).isNotBlank();
            // The persisted explanation is exactly the one returned to the hook.
            assertThat(record.getExplanation()).isEqualTo(verdict.explanation());
        }

        // The buffer retained nothing (writes succeeded), so no decision is pending or lost.
        assertThat(auditBuffer.bufferedCount()).isZero();
    }

    private static CorrectiveAction expectedAction(double composite) {
        if (composite < ASK_THRESHOLD) {
            return CorrectiveAction.ALLOW;
        }
        if (composite < BLOCK_THRESHOLD) {
            return CorrectiveAction.ASK;
        }
        return CorrectiveAction.BLOCK;
    }

    /** Builds the controllable {@link DivergenceResult} from the generated per-component plans. */
    private static DivergenceResult buildResult(double composite, List<ComponentPlan> plans) {
        List<ComponentResult> components = new ArrayList<>(plans.size());
        Set<ComponentId> excluded = new HashSet<>();
        for (ComponentPlan plan : plans) {
            if (plan.excluded()) {
                components.add(ComponentResult.excluded(plan.id(), plan.weight(), "unavailable"));
                excluded.add(plan.id());
            } else {
                components.add(ComponentResult.scored(plan.id(), plan.score(), plan.weight(), null));
            }
        }
        return new DivergenceResult(composite, components, excluded);
    }

    // --- generators ---------------------------------------------------------------------------

    @Provide
    Arbitrary<String> userIds() {
        return Arbitraries.strings()
                .withChars("abcdefghijklmnopqrstuvwxyz0123456789")
                .ofMinLength(3)
                .ofMaxLength(8);
    }

    @Provide
    Arbitrary<String> commands() {
        // Benign command text over a safe alphabet (no underscore, so Datastore-collection tamper
        // fragments cannot appear); the only single-token fragment risk, "intentguard", is filtered.
        return Arbitraries.strings()
                .withChars("abcdefghijklmnopqrstuvwxyz0123456789 -/.")
                .ofMinLength(1)
                .ofMaxLength(30)
                .filter(cmd -> !cmd.contains("intentguard"));
    }

    @Provide
    Arbitrary<InputOrigin> origins() {
        return Arbitraries.of(InputOrigin.TYPED, InputOrigin.PASTED, InputOrigin.UNKNOWN);
    }

    @Provide
    Arbitrary<List<ComponentPlan>> componentPlans() {
        return Combinators.combine(
                        plan(ComponentId.SEQUENCE_SURPRISE),
                        plan(ComponentId.CONTEXT_MISMATCH),
                        plan(ComponentId.BEHAVIORAL_DEVIATION),
                        plan(ComponentId.SEMANTIC_INCONSISTENCY))
                .as((a, b, c, d) -> List.of(a, b, c, d));
    }

    private Arbitrary<ComponentPlan> plan(ComponentId id) {
        Arbitrary<Boolean> excluded = Arbitraries.of(true, false);
        Arbitrary<Double> score = Arbitraries.doubles().between(0.0, 1.0);
        Arbitrary<Double> weight = Arbitraries.doubles().between(0.0, 1.0);
        return Combinators.combine(excluded, score, weight)
                .as((ex, sc, w) -> new ComponentPlan(id, ex, sc, w));
    }

    /** A generated plan for one component: whether it is excluded, and its score and applied weight. */
    private record ComponentPlan(ComponentId id, boolean excluded, double score, double weight) {
    }

    // --- helpers ------------------------------------------------------------------------------

    private static void seedActiveProfile(
            FakeBehavioralProfileRepository repository, String userId, long eventCount) {
        BehavioralProfileDocument doc = new BehavioralProfileDocument();
        doc.setUserId(userId);
        doc.setEventCount(eventCount);
        doc.setState(ProfileState.ACTIVE.name());
        repository.save(doc);
    }

    private static RawShellSignal signal(String user, String command, InputOrigin origin) {
        return new RawShellSignal(
                Actor.human(user), command, "/home/" + user, Map.of(), 1_710_000_000_000L, origin);
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

    // --- fakes --------------------------------------------------------------------------------

    /** A {@link ScoringPipeline} that returns a preset {@link DivergenceResult}. */
    private static final class ControllableScoringPipeline implements ScoringPipeline {
        private volatile DivergenceResult result;

        void willReturn(DivergenceResult result) {
            this.result = result;
        }

        @Override
        public DivergenceResult score(CommandEvent event, com.intentguard.domain.ScoringConfig config) {
            return result;
        }

        @Override
        public DivergenceResult score(com.intentguard.domain.ScoringContext ctx) {
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

    /** In-memory {@link AuditHistoryRepository}. */
    private static final class FakeAuditHistoryRepository extends AuditHistoryRepository {
        private final List<AuditHistoryDocument> records = new ArrayList<>();

        FakeAuditHistoryRepository() {
            super(mock(MongoDatabase.class));
        }

        @Override
        public void save(AuditHistoryDocument record) {
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
