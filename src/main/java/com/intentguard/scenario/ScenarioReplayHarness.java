package com.intentguard.scenario;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.intentguard.config.ThresholdConfiguration;
import com.intentguard.config.ThresholdConfigurationService;
import com.intentguard.decision.DecisionEngine;
import com.intentguard.decision.DefaultDecisionEngine;
import com.intentguard.decision.TamperClassifier;
import com.intentguard.domain.Actor;
import com.intentguard.domain.ActorType;
import com.intentguard.domain.AgentRiskMarkers;
import com.intentguard.domain.CommandEvent;
import com.intentguard.domain.CorrectiveAction;
import com.intentguard.domain.Decision;
import com.intentguard.domain.DivergenceResult;
import com.intentguard.domain.InputOrigin;
import com.intentguard.domain.IntentSource;
import com.intentguard.domain.ProfileState;
import com.intentguard.domain.ScoringContext;
import com.intentguard.domain.SignalSource;
import com.intentguard.explanation.ExplanationGenerator;
import com.intentguard.persistence.BehavioralProfileDocument;
import com.intentguard.persistence.BehavioralProfileRepository;
import com.intentguard.persistence.ScenarioBaselineDocument;
import com.intentguard.persistence.ScenarioBaselineRepository;
import com.intentguard.persistence.ScenarioCommandDocument;
import com.intentguard.scoring.AgentRiskAdjuster;
import com.intentguard.scoring.BehavioralDeviationComponent;
import com.intentguard.scoring.ContextMismatchComponent;
import com.intentguard.scoring.DefaultScoringPipeline;
import com.intentguard.scoring.DivergenceComponent;
import com.intentguard.scoring.ProfileSnapshot;
import com.intentguard.scoring.ProfileSnapshotProvider;
import com.intentguard.scoring.ScoringPipeline;
import com.intentguard.scoring.SemanticInconsistencyComponent;
import com.intentguard.scoring.SequenceSurpriseComponent;

/**
 * Seeds a scenario from its frozen baseline and replays the scripted Command_Events deterministically
 * (Req 16.1, 16.2).
 *
 * <p>For a given {@code scenario_baselines} document the harness:
 * <ol>
 *   <li><b>Loads</b> the baseline by {@code scenarioId} (via {@link ScenarioBaselineRepository}), or
 *       accepts a baseline document directly (so tests can drive it with no Datastore).</li>
 *   <li><b>Resets to the frozen baseline</b> before replay (Req 16.1): the seed Behavioral_Profile is
 *       written back to the profile store, and the seed Threshold_Configuration is applied to the
 *       {@link ThresholdConfigurationService} so both are the fixed starting point.</li>
 *   <li><b>Replays</b> the scripted Command_Events in order through a scoring pipeline and Decision
 *       Engine constructed against the frozen seed (the seed profile snapshot, the seed thresholds,
 *       and a {@link DeterministicLlmStub} in place of the network-backed LLM), collecting the
 *       resulting Corrective_Actions, reason codes, scores, and explanations in order.</li>
 * </ol>
 *
 * <h2>Determinism (Req 16.2, Property 20)</h2>
 * <p>Every input to the replay is fixed by the baseline: the profile is scored as the immutable seed
 * snapshot (no in-flight mutation, no wall-clock, no randomness), the thresholds are the seed
 * thresholds, and semantic scoring comes from the deterministic stub. Consequently replaying the same
 * baseline twice yields identical {@link ScenarioReplayReport}s. Each call re-resets to the baseline,
 * so runs are independent of one another and of any prior engine state.
 *
 * <h2>Testability</h2>
 * <p>The harness is a plain collaborator wired from existing beans in production (the {@code @Autowired}
 * constructor supplies a fresh {@link DeterministicLlmStub}), and is fully driveable in tests without a
 * live MongoDB: pass in-memory fakes of the repositories/services and a configured stub via the primary
 * constructor, or call {@link #replay(ScenarioBaselineDocument)} with a hand-built baseline.
 */
@Component
public class ScenarioReplayHarness {

    /**
     * Conventional {@code envContext} key carrying the Declared_Intent text for a scripted event.
     * The scenario document has no dedicated intent-text field, so the intent a command should be
     * scored against (when its {@code intentSource} is not {@code NONE}) is read from here.
     */
    public static final String INTENT_ENV_KEY = "declaredIntent";

    private final ScenarioBaselineRepository baselineRepository;
    private final BehavioralProfileRepository profileRepository;
    private final ThresholdConfigurationService thresholdService;
    private final DeterministicLlmStub llmStub;

    /**
     * Production wiring: builds the harness from the existing repository/service beans and a fresh
     * deterministic LLM stub. The stub is created here (not injected) so it never competes with the
     * production {@link com.intentguard.llm.LlmService} bean.
     */
    @Autowired
    public ScenarioReplayHarness(
            ScenarioBaselineRepository baselineRepository,
            BehavioralProfileRepository profileRepository,
            ThresholdConfigurationService thresholdService) {
        this(baselineRepository, profileRepository, thresholdService, new DeterministicLlmStub());
    }

    /**
     * Full constructor (used by tests) allowing a configured {@link DeterministicLlmStub} so the
     * scripted semantic scores are controllable and reproducible.
     */
    public ScenarioReplayHarness(
            ScenarioBaselineRepository baselineRepository,
            BehavioralProfileRepository profileRepository,
            ThresholdConfigurationService thresholdService,
            DeterministicLlmStub llmStub) {
        this.baselineRepository =
                Objects.requireNonNull(baselineRepository, "baselineRepository must not be null");
        this.profileRepository =
                Objects.requireNonNull(profileRepository, "profileRepository must not be null");
        this.thresholdService =
                Objects.requireNonNull(thresholdService, "thresholdService must not be null");
        this.llmStub = Objects.requireNonNull(llmStub, "llmStub must not be null");
    }

    /**
     * Loads the scenario baseline by id and replays it (Req 16.1, 16.2).
     *
     * @throws IllegalArgumentException if no baseline exists for {@code scenarioId}
     */
    public ScenarioReplayReport replay(String scenarioId) {
        Objects.requireNonNull(scenarioId, "scenarioId must not be null");
        ScenarioBaselineDocument baseline = baselineRepository.findByScenarioId(scenarioId)
                .orElseThrow(() -> new IllegalArgumentException("no scenario baseline for id: " + scenarioId));
        return replay(baseline);
    }

    /**
     * Resets to the frozen baseline and replays its scripted Command_Events in order.
     *
     * @param baseline the frozen scenario baseline (seed profile, seed thresholds, event script)
     * @return the ordered per-event outcomes
     */
    public ScenarioReplayReport replay(ScenarioBaselineDocument baseline) {
        Objects.requireNonNull(baseline, "baseline must not be null");

        // 1. Reset the relevant Threshold_Configuration to the frozen seed and apply it (Req 16.1).
        ThresholdConfiguration cfg = ThresholdConfiguration.fromDocument(
                Objects.requireNonNull(baseline.getSeedThresholds(), "seedThresholds must not be null"));
        thresholdService.initialize(cfg);

        // 2. Reset the relevant Behavioral_Profile to the frozen seed (Req 16.1). The seed is written
        // back to the profile store so a real deployment reflects the baseline; scoring reads the
        // same frozen seed as an immutable snapshot below.
        BehavioralProfileDocument seedProfile = baseline.getSeedProfile();
        if (seedProfile != null) {
            profileRepository.save(seedProfile);
        }

        // 3. Build a deterministic pipeline over the frozen seed: fixed profile snapshot, seed
        // thresholds, and the deterministic LLM stub in place of the network-backed adapter.
        ProfileSnapshotProvider profileProvider = ProfileSnapshotProvider.fixed(snapshotOf(seedProfile));
        ScoringPipeline pipeline = buildPipeline(profileProvider);
        DecisionEngine decisionEngine = new DefaultDecisionEngine(new TamperClassifier());
        ExplanationGenerator explanationGenerator = new ExplanationGenerator(llmStub);
        ProfileState profileState = profileStateOf(seedProfile, cfg.learningMinEvents());

        // 4. Replay the scripted Command_Events in order.
        List<ScenarioCommandDocument> script =
                baseline.getEventScript() == null ? List.of() : baseline.getEventScript();
        List<ScenarioReplayResult> results = new ArrayList<>(script.size());
        int index = 0;
        for (ScenarioCommandDocument doc : script) {
            results.add(replayOne(doc, index++, cfg, pipeline, decisionEngine, explanationGenerator, profileState));
        }
        return new ScenarioReplayReport(baseline.getScenarioId(), cfg, results);
    }

    private ScenarioReplayResult replayOne(
            ScenarioCommandDocument doc,
            int index,
            ThresholdConfiguration cfg,
            ScoringPipeline pipeline,
            DecisionEngine decisionEngine,
            ExplanationGenerator explanationGenerator,
            ProfileState profileState) {
        CommandEvent event = toCommandEvent(doc, index);
        IntentSource intentSource = event.intentSource();
        String intentText = event.envContext().get(INTENT_ENV_KEY);
        // An event carrying a session id is replayed as being inside an open human Intent_Session;
        // relevant only to the agent-containment rule for AGENT actors.
        boolean humanSessionOpen = event.sessionId() != null && !event.sessionId().isBlank();

        ScoringContext ctx = new ScoringContext(event, intentText, intentSource, profileState, cfg.toScoringConfig());
        DivergenceResult result = pipeline.score(ctx);
        Decision decision = decisionEngine.decide(event, result, cfg, profileState, humanSessionOpen);

        String explanation = null;
        if (decision.action() != CorrectiveAction.ALLOW) {
            explanation = explanationGenerator.explain(event, result, decision);
        }

        return new ScenarioReplayResult(
                event.eventId(),
                event.commandText(),
                decision.action(),
                decision.reasonCode(),
                decision.score(),
                explanation,
                result);
    }

    /**
     * Builds the four-component scoring pipeline used for replay: the three deterministic components
     * over the frozen seed snapshot, plus Semantic_Inconsistency backed by the deterministic stub.
     */
    private ScoringPipeline buildPipeline(ProfileSnapshotProvider profileProvider) {
        List<DivergenceComponent> components = List.of(
                new SequenceSurpriseComponent(profileProvider),
                new ContextMismatchComponent(profileProvider),
                new BehavioralDeviationComponent(profileProvider),
                new SemanticInconsistencyComponent(llmStub));
        return new DefaultScoringPipeline(components, new AgentRiskAdjuster());
    }

    /** Maps a scripted {@link ScenarioCommandDocument} into a {@link CommandEvent} for replay. */
    private static CommandEvent toCommandEvent(ScenarioCommandDocument doc, int index) {
        Objects.requireNonNull(doc, "scenario command document must not be null");

        ActorType actorType = parseEnum(doc.getActorType(), ActorType.class, ActorType.HUMAN);
        String userId = doc.getUserId() == null ? "unknown" : doc.getUserId();
        Actor actor = actorType == ActorType.AGENT
                ? Actor.agent(userId, doc.getHumanPrincipalId())
                : Actor.human(userId);

        String eventId = (doc.getEventId() == null || doc.getEventId().isBlank())
                ? "scenario-event-" + index
                : doc.getEventId();

        AgentRiskMarkers markers = new AgentRiskMarkers(
                doc.isOpensOutboundConnection(), doc.isAccessesSecret(), doc.isPrivilegeEscalation());

        Map<String, String> env = doc.getEnvContext() == null ? Map.of() : doc.getEnvContext();

        return new CommandEvent(
                eventId,
                actor,
                doc.getSessionId(),
                doc.getCommandText() == null ? "" : doc.getCommandText(),
                doc.getCwd(),
                doc.getRepo(),
                env,
                doc.getTimestamp(),
                parseEnum(doc.getInputOrigin(), InputOrigin.class, InputOrigin.UNKNOWN),
                parseEnum(doc.getSignalSource(), SignalSource.class, SignalSource.HOOK),
                parseEnum(doc.getIntentSource(), IntentSource.class, IntentSource.NONE),
                markers);
    }

    /** Derives an immutable {@link ProfileSnapshot} from a frozen seed profile document. */
    private static ProfileSnapshot snapshotOf(BehavioralProfileDocument profile) {
        if (profile == null) {
            return ProfileSnapshot.empty();
        }
        return ProfileSnapshot.builder()
                .eventCount(profile.getEventCount())
                .vocabulary(profile.getVocabulary())
                .sequenceStats(profile.getSequenceStats())
                .typedPastedRatioByCategory(profile.getTypedPastedRatioByCategory())
                .contextAssociations(profile.getContextAssociations())
                .lastCommandToken(null)
                .build();
    }

    /** The learning state implied by the frozen seed profile's event count and the configured minimum. */
    private static ProfileState profileStateOf(BehavioralProfileDocument profile, int learningMinEvents) {
        long eventCount = profile == null ? 0L : profile.getEventCount();
        return eventCount < learningMinEvents ? ProfileState.LEARNING : ProfileState.ACTIVE;
    }

    private static <E extends Enum<E>> E parseEnum(String value, Class<E> type, E fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Enum.valueOf(type, value);
        } catch (IllegalArgumentException unknownValue) {
            return fallback;
        }
    }
}
