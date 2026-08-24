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
 * End-to-end integration suite for the four scripted demo scenarios (Task 17.4, Req 16.3&ndash;16.7).
 *
 * <p>Where {@link DemoScenariosTest} isolates each scenario's individual assertion, this suite runs
 * every scenario <b>end-to-end through the same pipeline the demo uses</b> &mdash; seed the frozen
 * baseline, replay it through {@link ScenarioReplayHarness} (and, for takeover, feed the replay to a
 * {@link SessionAnomalyDetector}), then persist the decisions &mdash; and asserts the three facets
 * the task calls out for each applicable scenario:
 *
 * <ol>
 *   <li><b>expected Corrective_Action</b>,</li>
 *   <li><b>explanation content</b>, and</li>
 *   <li><b>persistence</b> to the Audit_History.</li>
 * </ol>
 *
 * <p>Everything runs against in-memory repository fakes (no live MongoDB), mirroring
 * {@link DemoScenariosTest}, so the suite is hermetic and deterministic.
 */
class FourScenariosIntegrationTest {

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

    // --- Scenario 1: agent prompt-injection hijack, end-to-end (Req 16.3) ---------------------

    @Test
    void agentHijackEndToEnd_blocksOffIntentAgentCommand_explainsTopContributors_andPersists() {
        ScenarioDefinition def = scenarios.agentHijack();
        ScenarioReplayReport report = replay(def);

        // Facet 1 - Corrective_Action: the on-intent human command is allowed, the off-intent AGENT
        // command lands in the block range and is BLOCKED (Req 16.3).
        assertThat(report.actions()).containsExactly(CorrectiveAction.ALLOW, CorrectiveAction.BLOCK);
        ScenarioReplayResult agentResult = report.results().get(1);
        assertThat(agentResult.eventId()).isEqualTo("hijack-2");
        assertThat(agentResult.action()).isEqualTo(CorrectiveAction.BLOCK);
        assertThat(agentResult.divergenceScore())
                .isGreaterThanOrEqualTo(DemoScenarios.BLOCK_THRESHOLD);

        // Facet 2 - explanation content: names the top contributing divergence components and, for
        // this off-intent agent command, references the declared intent (Req 16.3 / Req 8.2).
        assertThat(agentResult.explanation()).isNotBlank();
        assertThat(agentResult.explanation()).contains("declared intent");

        // Facet 3 - persistence: the BLOCK decision is written to the Audit_History as a reviewable
        // record carrying the actor type and explanation (Req 16.3 / Req 11.1).
        List<AuditHistoryDocument> persisted =
                scenarios.persistDecisions(def.baseline(), report, auditRepo);
        assertThat(persisted).hasSize(2);
        AuditHistoryDocument blockRecord = auditRepo.findByEventId("hijack-2").orElseThrow();
        assertThat(blockRecord.getCorrectiveAction()).isEqualTo("BLOCK");
        assertThat(blockRecord.getActorType()).isEqualTo("AGENT");
        assertThat(blockRecord.getExplanation()).isNotBlank();
        assertThat(blockRecord.getExplanation()).contains("declared intent");
        assertThat(blockRecord.getRecordType()).isEqualTo("DECISION");
    }

    // --- Scenario 2: pasted obfuscated payload, end-to-end (Req 16.4) -------------------------

    @Test
    void pastedPayloadEndToEnd_asksOrBlocks_explainsPastedOrigin_andPersistsWithPastedOrigin() {
        ScenarioDefinition def = scenarios.pastedPayload();
        ScenarioReplayReport report = replay(def);

        // Facet 1 - Corrective_Action: the pasted command is ASKed or BLOCKed (Req 16.4).
        assertThat(report.results()).hasSize(1);
        ScenarioReplayResult result = report.results().get(0);
        assertThat(result.eventId()).isEqualTo("pasted-1");
        assertThat(result.action()).isIn(CorrectiveAction.ASK, CorrectiveAction.BLOCK);
        assertThat(result.divergenceScore()).isGreaterThanOrEqualTo(DemoScenarios.ASK_THRESHOLD);

        // Facet 2 - explanation content: states the pasted origin (Req 16.4 / Req 9.3).
        assertThat(result.explanation()).isNotBlank();
        assertThat(result.explanation().toLowerCase()).contains("pasted");

        // Facet 3 - persistence: written to the Audit_History with the pasted input origin and the
        // pasted-origin explanation (Req 16.4).
        scenarios.persistDecisions(def.baseline(), report, auditRepo);
        AuditHistoryDocument record = auditRepo.findByEventId("pasted-1").orElseThrow();
        assertThat(record.getCorrectiveAction()).isIn("ASK", "BLOCK");
        assertThat(record.getInputOrigin()).isEqualTo("PASTED");
        assertThat(record.getExplanation().toLowerCase()).contains("pasted");
        assertThat(record.getRecordType()).isEqualTo("DECISION");
    }

    // --- Scenario 3: session takeover, end-to-end (Req 16.5) ----------------------------------

    @Test
    void sessionTakeoverEndToEnd_raisesAnomalyAlertWithDeviationEvidence_andRecordsIt() {
        ScenarioDefinition def = scenarios.sessionTakeover();
        ScenarioReplayReport report = replay(def);

        // Each takeover command carries a high Behavioral_Deviation - the anomaly evidence.
        assertThat(report.results()).hasSize(3);
        for (ScenarioReplayResult result : report.results()) {
            assertThat(DemoScenarios.behavioralDeviationOf(result)).isGreaterThanOrEqualTo(0.6);
        }

        // Facet 1 - outcome: a session-anomaly alert is raised (Req 16.5 / Req 10.1).
        SessionAnomalyDetector detector = new SessionAnomalyDetector(auditRepo);
        List<SessionAnomalyAlert> alerts = scenarios.detectSessionTakeover(report, detector);
        assertThat(alerts).hasSize(1);

        // Facet 2 - evidence: the alert carries the Behavioral_Deviation evidence (Req 16.5 / Req 10.2).
        SessionAnomalyAlert alert = alerts.get(0);
        assertThat(alert.meanDeviation()).isGreaterThanOrEqualTo(alert.threshold());
        assertThat(alert.evidenceDeviations()).hasSize(3);
        assertThat(alert.evidenceDeviations())
                .allSatisfy(d -> assertThat(d).isGreaterThanOrEqualTo(0.6));
        assertThat(alert.message()).isNotBlank();

        // Facet 3 - persistence: a SESSION_ANOMALY record with the evidence is recorded to the
        // Audit_History (Req 16.5 / Req 10.3).
        AuditHistoryDocument anomalyRecord = auditRepo.saved().stream()
                .filter(r -> "SESSION_ANOMALY".equals(r.getRecordType()))
                .findFirst()
                .orElseThrow();
        assertThat(anomalyRecord.getDivergenceScore()).isGreaterThanOrEqualTo(alert.threshold());
        assertThat(anomalyRecord.getExplanation()).isNotBlank();
        assertThat(anomalyRecord.getComponents()).hasSize(3);
    }

    // --- Scenario 4: on-intent normal work, end-to-end (Req 16.6, 16.7) -----------------------

    @Test
    void normalWorkEndToEnd_allowsEveryCommand_neitherAskNorBlock_andPersistsNoFlaggedDecision() {
        ScenarioDefinition def = scenarios.normalWork();
        ScenarioReplayReport report = replay(def);

        // Facet 1 - Corrective_Action: every command is ALLOWed - neither ask nor block (Req 16.6, 16.7).
        assertThat(report.results()).hasSize(3);
        assertThat(report.actions())
                .containsExactly(
                        CorrectiveAction.ALLOW, CorrectiveAction.ALLOW, CorrectiveAction.ALLOW);
        for (ScenarioReplayResult result : report.results()) {
            assertThat(result.action()).isEqualTo(CorrectiveAction.ALLOW);
            assertThat(result.divergenceScore()).isLessThan(DemoScenarios.ASK_THRESHOLD);
            // Facet 2 - explanation content: an ALLOW carries no flagged-decision explanation.
            assertThat(result.explanation()).isNull();
        }

        // Facet 3 - persistence: every allowed decision is still recorded, and none is ASK/BLOCK.
        List<AuditHistoryDocument> persisted =
                scenarios.persistDecisions(def.baseline(), report, auditRepo);
        assertThat(persisted).hasSize(3);
        assertThat(persisted)
                .allSatisfy(record -> assertThat(record.getCorrectiveAction()).isEqualTo("ALLOW"));
    }

    // --- Helpers ------------------------------------------------------------------------------

    private ScenarioReplayReport replay(ScenarioDefinition def) {
        baselineRepo.save(def.baseline());
        ScenarioReplayHarness harness =
                scenarios.harnessFor(def, baselineRepo, profileRepo, thresholdService);
        return harness.replay(def.baseline());
    }

    // --- In-memory repository fakes (no live MongoDB), mirroring DemoScenariosTest -------------

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
