package com.intentguard.scenario;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.mock;

import com.intentguard.config.ThresholdConfigurationService;
import com.intentguard.domain.CorrectiveAction;
import com.intentguard.persistence.AuditHistoryDocument;
import com.intentguard.persistence.AuditHistoryRepository;
import com.intentguard.persistence.BehavioralProfileDocument;
import com.intentguard.persistence.BehavioralProfileRepository;
import com.intentguard.persistence.ScenarioBaselineDocument;
import com.intentguard.persistence.ScenarioBaselineRepository;
import com.intentguard.persistence.ThresholdConfigRepository;
import com.intentguard.profile.SessionAnomalyAlert;
import com.intentguard.profile.SessionAnomalyDetector;
import com.intentguard.scenario.DemoScenarios.ScenarioDefinition;
import com.mongodb.client.MongoDatabase;

/**
 * Focused per-scenario tests for the four scripted demo scenarios (Task 17.2, Req 16.3&ndash;16.7),
 * driven entirely with in-memory repository fakes (no live MongoDB). Each test proves the scenario's
 * required outcome:
 *
 * <ul>
 *   <li>agent hijack &rarr; the off-intent agent command is BLOCKed, its Explanation names the top
 *       contributing components, and the decision is persisted (Req 16.3);</li>
 *   <li>pasted payload &rarr; the pasted command is ASKed/BLOCKed, its Explanation states the pasted
 *       origin, and the decision is persisted (Req 16.4);</li>
 *   <li>session takeover &rarr; a session-anomaly alert is raised with Behavioral_Deviation evidence
 *       and recorded to the Audit_History (Req 16.5);</li>
 *   <li>normal work &rarr; every on-intent command is ALLOWed &mdash; neither ask nor block
 *       (Req 16.6, 16.7).</li>
 * </ul>
 */
class DemoScenariosTest {

    private DemoScenarios scenarios;
    private InMemoryScenarioBaselineRepository baselineRepo;
    private InMemoryProfileRepository profileRepo;
    private InMemoryAuditHistoryRepository auditRepo;
    private ThresholdConfigurationService thresholdService;

    @BeforeEach
    void setUp() {
        scenarios = new DemoScenarios();
        baselineRepo = new InMemoryScenarioBaselineRepository();
        profileRepo = new InMemoryProfileRepository();
        auditRepo = new InMemoryAuditHistoryRepository();
        thresholdService = new ThresholdConfigurationService(mock(ThresholdConfigRepository.class));
    }

    // --- Scenario 1: agent prompt-injection hijack (Req 16.3) ---------------------------------

    @Test
    void agentHijackBlocksOffIntentAgentCommandWithTopContributorExplanationAndPersists() {
        ScenarioDefinition def = scenarios.agentHijack();
        ScenarioReplayReport report = replay(def);

        // On-intent human command allowed; off-intent agent command blocked.
        assertThat(report.actions())
                .containsExactly(CorrectiveAction.ALLOW, CorrectiveAction.BLOCK);

        ScenarioReplayResult agentResult = report.results().get(1);
        assertThat(agentResult.action()).isEqualTo(CorrectiveAction.BLOCK);
        assertThat(agentResult.divergenceScore()).isGreaterThanOrEqualTo(DemoScenarios.BLOCK_THRESHOLD);
        // The explanation names the top contributing divergence components (Req 16.3 / Req 8.2).
        assertThat(agentResult.explanation()).isNotBlank();
        assertThat(agentResult.explanation()).contains("declared intent");

        // The decision is persisted to the Audit_History (Req 16.3).
        List<AuditHistoryDocument> persisted =
                scenarios.persistDecisions(def.baseline(), report, auditRepo);
        assertThat(persisted).hasSize(2);
        AuditHistoryDocument blockRecord = auditRepo.findByEventId("hijack-2").orElseThrow();
        assertThat(blockRecord.getCorrectiveAction()).isEqualTo("BLOCK");
        assertThat(blockRecord.getExplanation()).isNotBlank();
        assertThat(blockRecord.getActorType()).isEqualTo("AGENT");
    }

    // --- Scenario 2: pasted obfuscated payload (Req 16.4) -------------------------------------

    @Test
    void pastedPayloadIsAskedOrBlockedWithPastedOriginExplanationAndPersists() {
        ScenarioDefinition def = scenarios.pastedPayload();
        ScenarioReplayReport report = replay(def);

        ScenarioReplayResult result = report.results().get(0);
        assertThat(result.action())
                .isIn(CorrectiveAction.ASK, CorrectiveAction.BLOCK);
        // The explanation states the pasted origin (Req 16.4 / Req 9.3).
        assertThat(result.explanation()).isNotBlank();
        assertThat(result.explanation().toLowerCase()).contains("pasted");

        // The decision is persisted to the Audit_History (Req 16.4).
        scenarios.persistDecisions(def.baseline(), report, auditRepo);
        AuditHistoryDocument record = auditRepo.findByEventId("pasted-1").orElseThrow();
        assertThat(record.getCorrectiveAction()).isIn("ASK", "BLOCK");
        assertThat(record.getExplanation().toLowerCase()).contains("pasted");
        assertThat(record.getInputOrigin()).isEqualTo("PASTED");
    }

    // --- Scenario 3: session takeover (Req 16.5) ----------------------------------------------

    @Test
    void sessionTakeoverRaisesRecordedAnomalyAlertWithEvidence() {
        ScenarioDefinition def = scenarios.sessionTakeover();
        ScenarioReplayReport report = replay(def);

        // Each takeover command has a high Behavioral_Deviation (the anomaly evidence).
        for (ScenarioReplayResult result : report.results()) {
            assertThat(DemoScenarios.behavioralDeviationOf(result)).isGreaterThanOrEqualTo(0.6);
        }

        SessionAnomalyDetector detector = new SessionAnomalyDetector(auditRepo);
        List<SessionAnomalyAlert> alerts = scenarios.detectSessionTakeover(report, detector);

        // A session-anomaly alert is raised (Req 16.5 / Req 10.1) with Behavioral_Deviation evidence
        // (Req 10.2).
        assertThat(alerts).hasSize(1);
        SessionAnomalyAlert alert = alerts.get(0);
        assertThat(alert.meanDeviation()).isGreaterThanOrEqualTo(alert.threshold());
        assertThat(alert.evidenceDeviations()).hasSize(3);
        assertThat(alert.evidenceDeviations()).allSatisfy(d -> assertThat(d).isGreaterThanOrEqualTo(0.6));

        // The alert is recorded to the Audit_History (Req 16.5 / Req 10.3).
        assertThat(auditRepo.saved())
                .anySatisfy(record -> assertThat(record.getRecordType()).isEqualTo("SESSION_ANOMALY"));
    }

    // --- Scenario 4: on-intent normal work (Req 16.6, 16.7) -----------------------------------

    @Test
    void normalWorkAllowsEveryCommandNeitherAskNorBlock() {
        ScenarioDefinition def = scenarios.normalWork();
        ScenarioReplayReport report = replay(def);

        assertThat(report.results()).hasSize(3);
        assertThat(report.actions())
                .containsExactly(
                        CorrectiveAction.ALLOW, CorrectiveAction.ALLOW, CorrectiveAction.ALLOW);
        // Neither ask nor block anywhere (Req 16.7); no flagged event carries an explanation.
        for (ScenarioReplayResult result : report.results()) {
            assertThat(result.action()).isEqualTo(CorrectiveAction.ALLOW);
            assertThat(result.divergenceScore()).isLessThan(DemoScenarios.ASK_THRESHOLD);
            assertThat(result.explanation()).isNull();
        }
    }

    // --- All scenarios are distinct and well-formed -------------------------------------------

    @Test
    void allFourScenariosAreDefined() {
        List<ScenarioDefinition> all = scenarios.all();
        assertThat(all).hasSize(4);
        assertThat(all).extracting(ScenarioDefinition::scenarioId)
                .containsExactly(
                        DemoScenarios.SCENARIO_AGENT_HIJACK,
                        DemoScenarios.SCENARIO_PASTED_PAYLOAD,
                        DemoScenarios.SCENARIO_SESSION_TAKEOVER,
                        DemoScenarios.SCENARIO_NORMAL_WORK);
    }

    // --- Helpers ------------------------------------------------------------------------------

    private ScenarioReplayReport replay(ScenarioDefinition def) {
        baselineRepo.save(def.baseline());
        ScenarioReplayHarness harness =
                scenarios.harnessFor(def, baselineRepo, profileRepo, thresholdService);
        return harness.replay(def.baseline());
    }

    // --- In-memory repository fakes (no live MongoDB) -----------------------------------------

    private static final class InMemoryScenarioBaselineRepository extends ScenarioBaselineRepository {
        private final Map<String, ScenarioBaselineDocument> store = new HashMap<>();

        InMemoryScenarioBaselineRepository() {
            super(mock(MongoDatabase.class));
        }

        @Override
        public void save(ScenarioBaselineDocument baseline) {
            store.put(baseline.getScenarioId(), baseline);
        }

        @Override
        public Optional<ScenarioBaselineDocument> findByScenarioId(String scenarioId) {
            return Optional.ofNullable(store.get(scenarioId));
        }
    }

    private static final class InMemoryProfileRepository extends BehavioralProfileRepository {
        private final Map<String, BehavioralProfileDocument> store = new HashMap<>();

        InMemoryProfileRepository() {
            super(mock(MongoDatabase.class));
        }

        @Override
        public void save(BehavioralProfileDocument profile) {
            store.put(profile.getUserId(), profile);
        }

        @Override
        public Optional<BehavioralProfileDocument> findByUserId(String userId) {
            return Optional.ofNullable(store.get(userId));
        }
    }

    private static final class InMemoryAuditHistoryRepository extends AuditHistoryRepository {
        private final List<AuditHistoryDocument> store = new ArrayList<>();

        InMemoryAuditHistoryRepository() {
            super(mock(MongoDatabase.class));
        }

        @Override
        public void save(AuditHistoryDocument record) {
            store.add(record);
        }

        @Override
        public Optional<AuditHistoryDocument> findByEventId(String eventId) {
            return store.stream().filter(r -> eventId.equals(r.getEventId())).findFirst();
        }

        List<AuditHistoryDocument> saved() {
            return new ArrayList<>(store);
        }
    }
}
