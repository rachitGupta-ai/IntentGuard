package com.intentguard.decision;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;

import static org.mockito.Mockito.mock;

import com.intentguard.blastradius.BlastRadiusGuard;
import com.intentguard.blastradius.GuardrailConfig;
import com.intentguard.blastradius.GuardrailConfigService;
import com.intentguard.config.ThresholdConfigUpdate;
import com.intentguard.config.ThresholdConfiguration;
import com.intentguard.config.ThresholdConfigurationService;
import com.intentguard.domain.Actor;
import com.intentguard.domain.CommandEvent;
import com.intentguard.domain.ComponentId;
import com.intentguard.domain.ComponentResult;
import com.intentguard.domain.Decision;
import com.intentguard.domain.DivergenceResult;
import com.intentguard.domain.InputOrigin;
import com.intentguard.domain.ProfileState;
import com.intentguard.domain.RawShellSignal;
import com.intentguard.domain.ScoringConfig;
import com.intentguard.domain.ScoringContext;
import com.intentguard.dualcontrol.ApprovalStatus;
import com.intentguard.dualcontrol.DualControlService;
import com.intentguard.dualcontrol.PendingApproval;
import com.intentguard.explanation.ExplanationGenerator;
import com.intentguard.ingest.ShellSignalNormalizer;
import com.intentguard.intent.DefaultIntentSessionManager;
import com.intentguard.llm.LlmService;
import com.intentguard.persistence.AuditHistoryDocument;
import com.intentguard.persistence.AuditHistoryRepository;
import com.intentguard.persistence.AuditWriteAheadBuffer;
import com.intentguard.persistence.BehavioralProfileDocument;
import com.intentguard.persistence.BehavioralProfileRepository;
import com.intentguard.persistence.GuardrailConfigRepository;
import com.intentguard.persistence.IntentSessionDocument;
import com.intentguard.persistence.IntentSessionRepository;
import com.intentguard.persistence.PendingApprovalRepository;
import com.intentguard.persistence.ThresholdConfigRepository;
import com.intentguard.policy.CommandPolicy;
import com.intentguard.policy.CommandPolicyService;
import com.intentguard.profile.BehavioralProfileManager;
import com.intentguard.scoring.ScoringPipeline;
import com.mongodb.client.MongoDatabase;

/**
 * Shared deterministic, DB-free harness for the pipeline-level guardrail tests (Task 7.2, and the
 * blast-radius audit Properties 16 &amp; 17). It wires a real {@link PipelineDecisionProvider}
 * around a real {@link GuardrailDecisionEngine} (wrapping {@code new DefaultDecisionEngine(new
 * TamperClassifier())}) with in-memory repository fakes and a controllable {@link ScoringPipeline},
 * mirroring the in-memory-fakes pattern of {@code PipelineDecisionProviderTest} and
 * {@code CompleteAuditRecordProperties}.
 *
 * <p>The guardrail services ({@link CommandPolicyService}, {@link BlastRadiusGuard},
 * {@link GuardrailConfigService}, {@link DualControlService}) are wired via the provider's additive
 * {@code required = false} setters exactly as Spring wires them at runtime, so the composed chain is
 * exercised end-to-end through {@link PipelineDecisionProvider#decide(RawShellSignal)}.
 */
final class GuardrailPipelineSupport {

    private GuardrailPipelineSupport() {
    }

    /** A fresh, fully-wired harness. Guardrail services may be {@code null} to leave them unwired. */
    static Harness harness(
            DivergenceResult scored,
            ThresholdConfiguration thresholds,
            CommandPolicyService commandPolicyService,
            BlastRadiusGuard blastRadiusGuard,
            GuardrailConfigService guardrailConfigService,
            DualControlService dualControlService) {
        return new Harness(
                scored, thresholds, commandPolicyService, blastRadiusGuard, guardrailConfigService, dualControlService);
    }

    /** One isolated pipeline instance plus the fakes needed to assert what it persisted. */
    static final class Harness {
        final FakeAuditHistoryRepository auditRepository = new FakeAuditHistoryRepository();
        final FakeBehavioralProfileRepository profileRepository = new FakeBehavioralProfileRepository();
        final AuditWriteAheadBuffer auditBuffer = new AuditWriteAheadBuffer(auditRepository, 100);
        final PipelineDecisionProvider provider;

        private Harness(
                DivergenceResult scored,
                ThresholdConfiguration thresholds,
                CommandPolicyService commandPolicyService,
                BlastRadiusGuard blastRadiusGuard,
                GuardrailConfigService guardrailConfigService,
                DualControlService dualControlService) {
            FakeIntentSessionRepository sessionRepository = new FakeIntentSessionRepository();
            BehavioralProfileManager profileManager = new BehavioralProfileManager(profileRepository);
            DefaultIntentSessionManager intentSessionManager =
                    new DefaultIntentSessionManager(sessionRepository, auditRepository);
            ThresholdConfigurationService configService =
                    new ThresholdConfigurationService(mock(ThresholdConfigRepository.class));
            configService.initialize(thresholds);
            ControllableScoringPipeline scoringPipeline = new ControllableScoringPipeline();
            scoringPipeline.willReturn(scored);
            ExplanationGenerator explanationGenerator = new ExplanationGenerator(new NoLlmService());

            this.provider = new PipelineDecisionProvider(
                    new ShellSignalNormalizer(),
                    intentSessionManager,
                    configService,
                    profileManager,
                    scoringPipeline,
                    new GuardrailDecisionEngine(new DefaultDecisionEngine(new TamperClassifier()), new TamperClassifier()),
                    explanationGenerator,
                    auditBuffer);

            if (commandPolicyService != null) {
                provider.setCommandPolicyService(commandPolicyService);
            }
            if (blastRadiusGuard != null) {
                provider.setBlastRadiusGuard(blastRadiusGuard);
            }
            if (guardrailConfigService != null) {
                provider.setGuardrailConfigService(guardrailConfigService);
            }
            if (dualControlService != null) {
                provider.setDualControlService(dualControlService);
            }
        }

        /** Seeds an ACTIVE profile so a genuine block is never clamped to ask by the learning rule. */
        Harness withActiveProfile(String userId) {
            BehavioralProfileDocument doc = new BehavioralProfileDocument();
            doc.setUserId(userId);
            doc.setEventCount(25);
            doc.setState(ProfileState.ACTIVE.name());
            profileRepository.save(doc);
            return this;
        }

        /** Drives one signal through the full pipeline and flushes the audit buffer durably. */
        com.intentguard.domain.Verdict decide(RawShellSignal signal) {
            com.intentguard.domain.Verdict verdict = provider.decide(signal);
            auditBuffer.flush();
            return verdict;
        }

        /** The single persisted {@code DECISION} Audit_History record. */
        AuditHistoryDocument decisionRecord() {
            for (AuditHistoryDocument doc : auditRepository.saved()) {
                if (PipelineDecisionProvider.RECORD_TYPE_DECISION.equals(doc.getRecordType())) {
                    return doc;
                }
            }
            throw new AssertionError("no DECISION audit record was persisted");
        }
    }

    // --- builders -------------------------------------------------------------------------------

    static RawShellSignal signal(String user, String command, InputOrigin origin) {
        return new RawShellSignal(
                Actor.human(user), command, "/home/" + user, Map.of(), 1_710_000_000_000L, origin);
    }

    static ThresholdConfiguration thresholds(double ask, double block, int learningMinEvents) {
        Map<ComponentId, Double> weights = new EnumMap<>(ComponentId.class);
        weights.put(ComponentId.SEQUENCE_SURPRISE, 0.25);
        weights.put(ComponentId.CONTEXT_MISMATCH, 0.20);
        weights.put(ComponentId.BEHAVIORAL_DEVIATION, 0.25);
        weights.put(ComponentId.SEMANTIC_INCONSISTENCY, 0.30);
        ThresholdConfigUpdate update = new ThresholdConfigUpdate(
                ask, block, weights, 0.15, learningMinEvents, 5000, 15000, 1200, 1000);
        return ThresholdConfiguration.fromUpdate(1, update, "test", 0L);
    }

    /** A fully-scored {@link DivergenceResult} with the given composite (no exclusions). */
    static DivergenceResult scoredResult(double composite) {
        List<ComponentResult> components = List.of(
                ComponentResult.scored(ComponentId.SEQUENCE_SURPRISE, composite, 0.25, null),
                ComponentResult.scored(ComponentId.CONTEXT_MISMATCH, composite, 0.20, null),
                ComponentResult.scored(ComponentId.BEHAVIORAL_DEVIATION, composite, 0.25, null),
                ComponentResult.scored(ComponentId.SEMANTIC_INCONSISTENCY, composite, 0.30, null));
        return new DivergenceResult(composite, components, java.util.Set.of());
    }

    /** A {@link CommandPolicyService} whose active policy is the given one (no live Mongo). */
    static CommandPolicyService commandPolicyService(CommandPolicy policy) {
        CommandPolicyService service = new CommandPolicyService(mock(com.intentguard.persistence.CommandPolicyRepository.class));
        service.initialize(policy);
        return service;
    }

    /** A {@link GuardrailConfigService} that always serves the given active {@link GuardrailConfig}. */
    static GuardrailConfigService guardrailConfigService(GuardrailConfig config) {
        return new FixedGuardrailConfigService(config);
    }

    /** A {@link DualControlService} backed by in-memory fakes serving the given config. */
    static DualControlService dualControlService(GuardrailConfig config, AuditHistoryRepository audit) {
        return new DualControlService(
                new InMemoryPendingApprovalRepository(), audit, new FixedGuardrailConfigService(config));
    }

    // --- fakes ----------------------------------------------------------------------------------

    /** A {@link ScoringPipeline} that returns a preset {@link DivergenceResult}. */
    static final class ControllableScoringPipeline implements ScoringPipeline {
        private volatile DivergenceResult result;

        void willReturn(DivergenceResult result) {
            this.result = result;
        }

        @Override
        public DivergenceResult score(CommandEvent event, ScoringConfig config) {
            return result;
        }

        @Override
        public DivergenceResult score(ScoringContext ctx) {
            return result;
        }
    }

    /** An {@link LlmService} that is always unavailable, forcing the deterministic explanation. */
    static final class NoLlmService implements LlmService {
        @Override
        public OptionalDouble semanticInconsistency(CommandEvent event, String intentText) {
            return OptionalDouble.empty();
        }

        @Override
        public Optional<String> explain(CommandEvent event, DivergenceResult result, Decision decision) {
            return Optional.empty();
        }
    }

    /** A {@link GuardrailConfigService} that always serves a fixed (possibly {@code null}) config. */
    static final class FixedGuardrailConfigService extends GuardrailConfigService {
        private final GuardrailConfig config;

        FixedGuardrailConfigService(GuardrailConfig config) {
            super(mock(GuardrailConfigRepository.class));
            this.config = config;
        }

        @Override
        public Optional<GuardrailConfig> getActiveConfig() {
            return Optional.ofNullable(config);
        }
    }

    /** In-memory {@link PendingApprovalRepository} keyed by event id. */
    static final class InMemoryPendingApprovalRepository extends PendingApprovalRepository {
        private final Map<String, PendingApproval> byEventId = new HashMap<>();

        InMemoryPendingApprovalRepository() {
            super(mock(MongoDatabase.class));
        }

        @Override
        public void save(PendingApproval approval) {
            byEventId.put(approval.eventId(), approval);
        }

        @Override
        public Optional<PendingApproval> findByEventId(String eventId) {
            return Optional.ofNullable(byEventId.get(eventId));
        }

        @Override
        public List<PendingApproval> findByStatus(ApprovalStatus status) {
            return byEventId.values().stream().filter(a -> a.status() == status).toList();
        }
    }

    /** In-memory {@link AuditHistoryRepository} recording every saved record in order. */
    static final class FakeAuditHistoryRepository extends AuditHistoryRepository {
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
    static final class FakeIntentSessionRepository extends IntentSessionRepository {
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
    static final class FakeBehavioralProfileRepository extends BehavioralProfileRepository {
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
