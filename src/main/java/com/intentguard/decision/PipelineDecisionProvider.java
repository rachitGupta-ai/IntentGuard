package com.intentguard.decision;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import com.intentguard.api.LivePushService;
import com.intentguard.api.ScoreEvent;
import com.intentguard.blastradius.BlastRadiusGuard;
import com.intentguard.blastradius.BlastRadiusResult;
import com.intentguard.blastradius.GuardrailConfig;
import com.intentguard.blastradius.GuardrailConfigService;
import com.intentguard.config.ThresholdConfiguration;
import com.intentguard.config.ThresholdConfigurationService;
import com.intentguard.domain.Actor;
import com.intentguard.domain.CommandEvent;
import com.intentguard.domain.ComponentId;
import com.intentguard.domain.ComponentResult;
import com.intentguard.domain.CorrectiveAction;
import com.intentguard.domain.Decision;
import com.intentguard.domain.DivergenceResult;
import com.intentguard.domain.IntentSource;
import com.intentguard.domain.ProfileState;
import com.intentguard.domain.RawShellSignal;
import com.intentguard.domain.ScoringConfig;
import com.intentguard.domain.ScoringContext;
import com.intentguard.domain.Verdict;
import com.intentguard.dualcontrol.ApprovalStatus;
import com.intentguard.dualcontrol.DualControlService;
import com.intentguard.dualcontrol.PendingApproval;
import com.intentguard.explanation.ExplanationGenerator;
import com.intentguard.ingest.InteractiveDecisionProvider;
import com.intentguard.ingest.ShellSignalNormalizer;
import com.intentguard.intent.InferredIntentService;
import com.intentguard.intent.IntentSession;
import com.intentguard.intent.IntentSessionManager;
import com.intentguard.persistence.AuditHistoryDocument;
import com.intentguard.persistence.AuditWriteAheadBuffer;
import com.intentguard.persistence.ComponentScoreDocument;
import com.intentguard.policy.CommandPolicyService;
import com.intentguard.policy.PolicyDecision;
import com.intentguard.profile.BehavioralProfileManager;
import com.intentguard.scoring.ScoringPipeline;

/**
 * The real ingest &rarr; scoring &rarr; decision &rarr; persist pipeline for the synchronous
 * blocking gate (Task 13.1), replacing the Task 2.3 {@code StubInteractiveDecisionProvider}.
 *
 * <p>Marked {@link Primary} so it is the {@link InteractiveDecisionProvider} the
 * {@code InteractiveSignalIngestor} resolves at runtime, even if the walking-skeleton stub bean is
 * still present. For each {@link RawShellSignal} received on the Unix domain socket it:
 * <ol>
 *   <li>normalizes the signal into a {@link CommandEvent} ({@link ShellSignalNormalizer});</li>
 *   <li>resolves the active Intent_Session for the actor's principal
 *       ({@link IntentSessionManager#activeSessionFor}), deriving the intent text, intent source,
 *       and whether a human session is open, and tags the event with the session id and intent
 *       source;</li>
 *   <li>reads the active {@link ThresholdConfiguration} (falling back to a built-in default when
 *       none is loaded, e.g. the Datastore is unreachable) and the actor's
 *       {@link ProfileState};</li>
 *   <li>runs the {@link ScoringPipeline} over a {@link ScoringContext} carrying the event, intent
 *       text/source, profile state, and derived {@link ScoringConfig} &rarr; a
 *       {@link DivergenceResult} with every component score, applied weight, and the excluded
 *       set;</li>
 *   <li>runs the {@link DecisionEngine} with the active configuration, profile state, and
 *       human-session flag &rarr; a {@link Decision};</li>
 *   <li>generates an {@code Explanation} for {@code ask}/{@code block} decisions (Req 8.3);</li>
 *   <li>updates the Behavioral_Profile only on {@code ALLOW}
 *       ({@link BehavioralProfileManager#recordEvent}); {@code ask}/{@code block} leave it
 *       unchanged (Req 3.2);</li>
 *   <li>persists a complete {@code Audit_History} record (event, every component score + applied
 *       weight, composite, action, reason code, intent presence/source, explanation, profile
 *       state) through a bounded write-ahead buffer so no decision — especially no block — is lost
 *       on a transient Datastore failure (Req 5.7, 7.4, 8.3, 11.1, 13.1, 13.2);</li>
 *   <li>returns the {@link Verdict} to the ingestor.</li>
 * </ol>
 */
@Component
@Primary
public class PipelineDecisionProvider implements InteractiveDecisionProvider {

    private static final Logger log = LoggerFactory.getLogger(PipelineDecisionProvider.class);

    /** Audit_History {@code recordType} for a corrective decision (Req 11.1). */
    static final String RECORD_TYPE_DECISION = "DECISION";

    /**
     * Triggering-guardrail id recorded when a dual-control confirmation withholds or times out a
     * decision, so the Audit_History and Explanation name it (Req 4.9).
     */
    static final String DUAL_CONTROL_TRIGGER_ID = "dual-control";

    /**
     * Triggering-guardrail id recorded when an Agent_Actor event outside its configured capability
     * scope raises the floor, so the Audit_History and Explanation name it (Req 4.8).
     */
    static final String CAPABILITY_SCOPE_TRIGGER_ID = "capability-scope";

    /** {@code updatedBy} stamped on the fallback {@link GuardrailConfig} when none is active. */
    private static final String DEFAULT_GUARDRAIL_CONFIG_AUTHOR = "system";

    /**
     * Built-in default Threshold_Configuration used when none has been loaded from the Datastore
     * (e.g. first run, or the Datastore is temporarily unreachable). Mirrors the design's example
     * defaults so the engine still reaches sound decisions rather than failing.
     */
    static final ThresholdConfiguration DEFAULT_CONFIG = defaultConfig();

    private final ShellSignalNormalizer normalizer;
    private final IntentSessionManager intentSessionManager;
    private final ThresholdConfigurationService configService;
    private final BehavioralProfileManager profileManager;
    private final ScoringPipeline scoringPipeline;
    private final DecisionEngine decisionEngine;
    private final ExplanationGenerator explanationGenerator;
    private final AuditWriteAheadBuffer auditBuffer;

    /**
     * Optional live-push fan-out to subscribed Control_Tower clients (Req 12.6). Wired by Spring via
     * {@link #setLivePushService(LivePushService)} when present; left {@code null} in unit tests and
     * deployments without the Control_Tower so the core pipeline runs unchanged. Each reached
     * decision publishes a {@link ScoreEvent} after the Audit_History write.
     */
    private LivePushService livePushService;

    /**
     * Optional Inferred_Intent derivation (Req 14, stretch). Wired by Spring via
     * {@link #setInferredIntentService(InferredIntentService)} when present; left {@code null} in
     * unit tests and deployments where the stretch capability is not present, so the core pipeline
     * runs unchanged. Consulted only when no human Intent_Session is open (Req 14.1); when it
     * produces an Inferred_Intent, the event is scored against it with the intent source recorded as
     * {@link IntentSource#INFERRED} (Req 14.2) and the (strictly lower) inferred-intent semantic
     * weight applied downstream by the Semantic_Inconsistency component (Req 14.3).
     */
    private InferredIntentService inferredIntentService;

    /**
     * Optional DualControl authorization service (Req 4). Wired by Spring via
     * {@link #setDualControlService(DualControlService)} when present; left {@code null} in unit
     * tests and deployments without the guardrail layer so the core pipeline runs unchanged. When
     * wired, each decision has its {@link GuardrailContext} populated with the event's capability
     * scope (Req 4.8) and its {@link DualControlStatus} (Req 4.1, 4.2, 4.5): overdue approvals are
     * expired first, then any pending approval for the event is reflected into the context so the
     * {@link GuardrailDecisionEngine} can withhold (PENDING &rarr; at-least-{@code ASK}) or block
     * (TIMED_OUT) accordingly.
     */
    private DualControlService dualControlService;

    /**
     * Optional CommandPolicy evaluation (Req 2). Wired by Spring via
     * {@link #setCommandPolicyService(CommandPolicyService)} when present; left {@code null} in unit
     * tests and deployments without the guardrail layer so the core pipeline runs unchanged. When
     * wired (and the {@link GuardrailDecisionEngine} is active), each event is evaluated against the
     * active CommandPolicy and the resulting {@link PolicyDecision} is placed on the
     * {@link GuardrailContext} so a {@code DENY} short-circuits to {@code BLOCK} and a
     * {@code REQUIRE_CONFIRM} raises the floor to {@code ASK} (Req 2.3, 2.7, 2.8).
     */
    private CommandPolicyService commandPolicyService;

    /**
     * Optional blast-radius / protected-target guard (Req 3). Wired by Spring via
     * {@link #setBlastRadiusGuard(BlastRadiusGuard)} when present; left {@code null} in unit tests
     * and deployments without the guardrail layer so the core pipeline runs unchanged. When wired
     * (and the {@link GuardrailDecisionEngine} is active), each event is evaluated against the active
     * {@link GuardrailConfig} and the resulting {@link BlastRadiusResult} is placed on the
     * {@link GuardrailContext} (Req 3.2, 3.3, 3.4, 3.5, 3.6, 3.8).
     */
    private BlastRadiusGuard blastRadiusGuard;

    /**
     * Optional active-{@link GuardrailConfig} source for the blast-radius guard (Req 3.1). Wired by
     * Spring via {@link #setGuardrailConfigService(GuardrailConfigService)} when present; when absent
     * (or no config has been loaded) the blast-radius guard is evaluated against
     * {@link GuardrailConfig#defaults(String, long)} so it still fails safe.
     */
    private GuardrailConfigService guardrailConfigService;

    public PipelineDecisionProvider(
            ShellSignalNormalizer normalizer,
            IntentSessionManager intentSessionManager,
            ThresholdConfigurationService configService,
            BehavioralProfileManager profileManager,
            ScoringPipeline scoringPipeline,
            DecisionEngine decisionEngine,
            ExplanationGenerator explanationGenerator,
            AuditWriteAheadBuffer auditBuffer) {
        this.normalizer = Objects.requireNonNull(normalizer, "normalizer must not be null");
        this.intentSessionManager =
                Objects.requireNonNull(intentSessionManager, "intentSessionManager must not be null");
        this.configService = Objects.requireNonNull(configService, "configService must not be null");
        this.profileManager = Objects.requireNonNull(profileManager, "profileManager must not be null");
        this.scoringPipeline = Objects.requireNonNull(scoringPipeline, "scoringPipeline must not be null");
        this.decisionEngine = Objects.requireNonNull(decisionEngine, "decisionEngine must not be null");
        this.explanationGenerator =
                Objects.requireNonNull(explanationGenerator, "explanationGenerator must not be null");
        this.auditBuffer = Objects.requireNonNull(auditBuffer, "auditBuffer must not be null");
    }

    /**
     * Optionally wires the Control_Tower live-push fan-out (Req 12.6). Marked
     * {@code required = false} so the 8-argument constructor remains the sole wiring contract and
     * the pipeline runs identically when no live channel is present.
     */
    @Autowired(required = false)
    public void setLivePushService(LivePushService livePushService) {
        this.livePushService = livePushService;
    }

    /**
     * Optionally wires the Inferred_Intent derivation service (Req 14, stretch). Marked
     * {@code required = false} so the 8-argument constructor remains the sole wiring contract and
     * the pipeline runs identically when the stretch capability is absent.
     */
    @Autowired(required = false)
    public void setInferredIntentService(InferredIntentService inferredIntentService) {
        this.inferredIntentService = inferredIntentService;
    }

    /**
     * Optionally wires the DualControl authorization service (Req 4). Marked {@code required = false}
     * so the 8-argument constructor remains the sole wiring contract and the pipeline runs
     * identically (with an {@linkplain GuardrailContext#empty() empty} guardrail context) when the
     * guardrail layer is absent.
     */
    @Autowired(required = false)
    public void setDualControlService(DualControlService dualControlService) {
        this.dualControlService = dualControlService;
    }

    /**
     * Optionally wires the CommandPolicy evaluation service (Req 2). Marked {@code required = false}
     * so the 8-argument constructor remains the sole wiring contract and the pipeline runs
     * identically (with {@link PolicyDecision#none()}) when the guardrail layer is absent.
     */
    @Autowired(required = false)
    public void setCommandPolicyService(CommandPolicyService commandPolicyService) {
        this.commandPolicyService = commandPolicyService;
    }

    /**
     * Optionally wires the blast-radius / protected-target guard (Req 3). Marked
     * {@code required = false} so the 8-argument constructor remains the sole wiring contract and the
     * pipeline runs identically (with {@link BlastRadiusResult#none()}) when the guardrail layer is
     * absent.
     */
    @Autowired(required = false)
    public void setBlastRadiusGuard(BlastRadiusGuard blastRadiusGuard) {
        this.blastRadiusGuard = blastRadiusGuard;
    }

    /**
     * Optionally wires the active-{@link GuardrailConfig} source used by the blast-radius guard
     * (Req 3.1). Marked {@code required = false} so the 8-argument constructor remains the sole
     * wiring contract; when absent the guard falls back to {@link GuardrailConfig#defaults}.
     */
    @Autowired(required = false)
    public void setGuardrailConfigService(GuardrailConfigService guardrailConfigService) {
        this.guardrailConfigService = guardrailConfigService;
    }

    @Override
    public Verdict decide(RawShellSignal signal) {
        Objects.requireNonNull(signal, "signal must not be null");

        // 1. Normalize the raw signal into a CommandEvent.
        CommandEvent normalized = normalizer.normalize(signal);

        // 2. Resolve the active Intent_Session for the actor's principal and tag the event.
        Actor actor = signal.actor();
        String principalKey = principalKey(actor);
        Optional<IntentSession> session = intentSessionManager.activeSessionFor(principalKey);
        boolean humanSessionOpen = session.isPresent();
        String intentText = session.map(IntentSession::declaredIntent).orElse(null);
        IntentSource intentSource = session.map(IntentSession::intentSource).orElse(IntentSource.NONE);
        String sessionId = session.map(IntentSession::sessionId).orElse(null);

        // 2a. Inferred_Intent (Req 14, stretch): when the feature flag is on and no human
        // Intent_Session is open, derive an Inferred_Intent from recent command statistics + LLM
        // summarization. When one is produced, score against it and record the source as INFERRED;
        // the (strictly lower) inferred-intent semantic weight is applied downstream (Req 14.2, 14.3).
        // If the service is absent, disabled, or degrades, intent stays NONE and Semantic is excluded.
        if (!humanSessionOpen && inferredIntentService != null) {
            Optional<String> inferred = inferredIntentService.deriveInferredIntent(normalized.userId());
            if (inferred.isPresent()) {
                intentText = inferred.get();
                intentSource = IntentSource.INFERRED;
            }
        }

        CommandEvent event = withIntent(normalized, sessionId, intentSource);

        // 3. Read the active Threshold_Configuration and the actor's profile state.
        ThresholdConfiguration cfg = configService.getActiveConfig().orElse(DEFAULT_CONFIG);
        ScoringConfig scoringConfig = cfg.toScoringConfig();
        ProfileState profileState = profileManager.profileStateFor(event.userId(), cfg.learningMinEvents());

        // 4. Run the scoring pipeline over a fully-populated ScoringContext.
        ScoringContext ctx = new ScoringContext(event, intentText, intentSource, profileState, scoringConfig);
        DivergenceResult result = scoringPipeline.score(ctx);

        // 5. Build the guardrail context and run the decision engine. When the GuardrailDecisionEngine
        // is active and at least one guardrail service is wired, build the FULL GuardrailContext
        // (policy + blast-radius + capability scope + dual-control status) and use the
        // GuardrailDecisionEngine overload; otherwise fall back to the plain interface call so the
        // core pipeline runs unchanged (Req 1.1, 2.3, 3.2, 4.8).
        GuardrailContext guardrail = buildGuardrailContext(event);
        Decision decision = decideWith(event, result, cfg, profileState, humanSessionOpen, guardrail);

        // 6. Determine the triggering guardrail ids that drove a flagged decision (Req 2.11, 3.7, 4.9).
        List<String> triggeredGuardrailIds = triggeredGuardrailIds(guardrail, decision);

        // 7. Generate an explanation for flagged (ask/block) decisions (Req 8.3). When a guardrail
        // drove the decision, use the naming overload so the matched rule / target / guardrail is
        // named even when the LLM is unavailable (Req 2.11, 3.7).
        String explanation = null;
        if (decision.action() != CorrectiveAction.ALLOW) {
            explanation = triggeredGuardrailIds.isEmpty()
                    ? explanationGenerator.explain(event, result, decision)
                    : explanationGenerator.explain(event, result, decision, triggeredGuardrailIds);
        }

        // 8. Update the Behavioral_Profile only on ALLOW (Req 3.2).
        profileManager.recordEvent(event, decision.action(), cfg.learningMinEvents());

        // 9. Persist a complete Audit_History record via the bounded write-ahead buffer (Req 11.1).
        // The guardrail-named explanation carries the triggering guardrail ids into the record so a
        // policy/blast-radius/dual-control hit is named there too (Req 2.10, 3.7, 4.9).
        AuditHistoryDocument record =
                buildAuditRecord(event, result, decision, explanation, profileState, intentSource);
        boolean persisted = auditBuffer.write(record);
        if (!persisted) {
            log.warn(
                    "Audit record for event '{}' ({}) buffered pending Datastore recovery",
                    event.eventId(),
                    decision.action());
        }

        // 10. Push a live score event to subscribed Control_Tower clients (Req 12.6), if wired.
        publishScore(event, decision, explanation);

        // 11. Return the verdict to the ingestor.
        return Verdict.from(decision, explanation);
    }

    /**
     * Builds the additive {@link GuardrailContext} for {@code event} when the
     * {@link GuardrailDecisionEngine} is active and at least one guardrail service is wired:
     * <ul>
     *   <li>{@code policy} = {@link CommandPolicyService#evaluate(CommandEvent)} when wired, else
     *       {@link PolicyDecision#none()} (Req 2.3);</li>
     *   <li>{@code blastRadius} = {@link BlastRadiusGuard#evaluate(CommandEvent, GuardrailConfig)}
     *       against the active {@link GuardrailConfig} when wired, else
     *       {@link BlastRadiusResult#none()} (Req 3.2);</li>
     *   <li>{@code withinCapabilityScope} + dual-control status from the
     *       {@link DualControlService} when wired (Req 4.8, 4.1, 4.2, 4.5).</li>
     * </ul>
     *
     * Returns {@code null} when the guardrail chain is not active (the engine is not a
     * {@link GuardrailDecisionEngine}, or no guardrail service is wired) so the caller falls back to
     * the plain {@link DecisionEngine#decide} path and the core pipeline runs unchanged.
     */
    private GuardrailContext buildGuardrailContext(CommandEvent event) {
        if (!(decisionEngine instanceof GuardrailDecisionEngine) || !anyGuardrailServiceWired()) {
            return null;
        }
        PolicyDecision policy =
                commandPolicyService != null ? commandPolicyService.evaluate(event) : PolicyDecision.none();
        BlastRadiusResult blastRadius = evaluateBlastRadius(event);
        boolean withinCapabilityScope =
                dualControlService == null || dualControlService.withinCapabilityScope(event);
        DualControlStatus dualControl =
                dualControlService == null ? DualControlStatus.NONE : dualControlStatusFor(event);
        return new GuardrailContext(policy, blastRadius, withinCapabilityScope, dualControl);
    }

    /** Whether any of the additive guardrail services has been wired. */
    private boolean anyGuardrailServiceWired() {
        return commandPolicyService != null || blastRadiusGuard != null || dualControlService != null;
    }

    /**
     * Evaluates the blast-radius / protected-target guard against the active {@link GuardrailConfig}
     * (falling back to {@link GuardrailConfig#defaults} when none is loaded so the guard still fails
     * safe), or {@link BlastRadiusResult#none()} when the guard is not wired (Req 3.1, 3.2).
     */
    private BlastRadiusResult evaluateBlastRadius(CommandEvent event) {
        if (blastRadiusGuard == null) {
            return BlastRadiusResult.none();
        }
        GuardrailConfig activeConfig = guardrailConfigService == null
                ? GuardrailConfig.defaults(DEFAULT_GUARDRAIL_CONFIG_AUTHOR, event.timestamp())
                : guardrailConfigService.getActiveConfig()
                        .orElseGet(() -> GuardrailConfig.defaults(
                                DEFAULT_GUARDRAIL_CONFIG_AUTHOR, event.timestamp()));
        return blastRadiusGuard.evaluate(event, activeConfig);
    }

    /**
     * Runs the decision engine. When a {@link GuardrailContext} was built (guardrail chain active)
     * the {@link GuardrailDecisionEngine} overload is used; otherwise the plain
     * {@link DecisionEngine#decide} interface method is used, which is equivalent to an
     * {@linkplain GuardrailContext#empty() empty} context, so the core pipeline is unchanged.
     */
    private Decision decideWith(
            CommandEvent event,
            DivergenceResult result,
            ThresholdConfiguration cfg,
            ProfileState profileState,
            boolean humanSessionOpen,
            GuardrailContext guardrail) {
        if (guardrail == null || !(decisionEngine instanceof GuardrailDecisionEngine engine)) {
            return decisionEngine.decide(event, result, cfg, profileState, humanSessionOpen);
        }
        return engine.decide(event, result, cfg, profileState, humanSessionOpen, guardrail);
    }

    /**
     * Collects the identifiers of the guardrail(s) that drove a flagged (ask/block) decision, so the
     * Explanation and Audit_History name them (Req 2.11, 3.7, 4.9): the matched {@code PolicyRule} id
     * for an enforcing policy hit (DENY / REQUIRE_CONFIRM), the blast-radius / protected-target /
     * mass-op / destructive / indeterminate trigger ids, a dual-control marker when execution is
     * withheld or timed out, and a capability-scope marker for an out-of-scope agent action.
     *
     * <p>Returns an empty list when the guardrail chain is inactive or the decision was an ALLOW, so
     * an unflagged event (and the plain non-guardrail path) is never annotated.
     */
    private static List<String> triggeredGuardrailIds(GuardrailContext guardrail, Decision decision) {
        if (guardrail == null || decision.action() == CorrectiveAction.ALLOW) {
            return List.of();
        }
        List<String> ids = new ArrayList<>();
        if (guardrail.policy().isDeny() || guardrail.policy().isRequireConfirm()) {
            guardrail.policy().ruleId().ifPresent(ids::add);
        }
        ids.addAll(guardrail.blastRadius().triggeredGuardrailIds());
        if (guardrail.dualControl() == DualControlStatus.PENDING
                || guardrail.dualControl() == DualControlStatus.TIMED_OUT) {
            ids.add(DUAL_CONTROL_TRIGGER_ID);
        }
        if (!guardrail.withinCapabilityScope()) {
            ids.add(CAPABILITY_SCOPE_TRIGGER_ID);
        }
        return ids;
    }

    /**
     * Resolves the {@link DualControlStatus} for {@code event}: expires any overdue approvals first
     * (so a stale {@code PENDING} correctly surfaces as {@code TIMED_OUT}), then maps the event's
     * current approval, if any, to its guardrail-facing status. A rejected confirmation attempt
     * keeps the approval {@code PENDING}, so the event stays withheld (Req 4.3, 4.7).
     */
    private DualControlStatus dualControlStatusFor(CommandEvent event) {
        dualControlService.expireOverdue(event.timestamp());
        return dualControlService.find(event.eventId())
                .map(PendingApproval::status)
                .map(PipelineDecisionProvider::mapDualControlStatus)
                .orElse(DualControlStatus.NONE);
    }

    /** Maps a persisted {@link ApprovalStatus} to the chain's {@link DualControlStatus}. */
    private static DualControlStatus mapDualControlStatus(ApprovalStatus status) {
        return switch (status) {
            case CONFIRMED -> DualControlStatus.CONFIRMED;
            case TIMED_OUT -> DualControlStatus.TIMED_OUT;
            // PENDING and a rejected-but-still-withheld attempt both keep execution withheld.
            case PENDING, REJECTED -> DualControlStatus.PENDING;
        };
    }

    /**
     * Fans a {@link ScoreEvent} out to subscribed Control_Tower clients when the live channel is
     * wired (Req 12.6). Best-effort: a live-push failure never affects the enforcement verdict.
     */
    private void publishScore(CommandEvent event, Decision decision, String explanation) {
        LivePushService push = this.livePushService;
        if (push == null) {
            return;
        }
        try {
            push.publishScore(new ScoreEvent(
                    event.eventId(),
                    event.userId(),
                    decision.score(),
                    decision.action().name(),
                    event.timestamp(),
                    explanation));
        } catch (RuntimeException e) {
            log.debug("Live score push failed for event '{}': {}", event.eventId(), e.toString());
        }
    }

    /**
     * The identity whose Intent_Session envelope governs this actor: an Agent_Actor operates within
     * its human principal's session, so its principal id is used when present; a human uses its own
     * id.
     */
    private static String principalKey(Actor actor) {
        if (actor.isAgent() && actor.humanPrincipalId() != null) {
            return actor.humanPrincipalId();
        }
        return actor.userId();
    }

    /** Returns a copy of {@code event} tagged with the resolved session id and intent source. */
    private static CommandEvent withIntent(CommandEvent event, String sessionId, IntentSource intentSource) {
        return new CommandEvent(
                event.eventId(),
                event.actor(),
                sessionId,
                event.commandText(),
                event.cwd(),
                event.repo(),
                event.envContext(),
                event.timestamp(),
                event.inputOrigin(),
                event.signalSource(),
                intentSource,
                event.agentRiskMarkers());
    }

    /**
     * Builds the complete Audit_History record for a decision: the embedded Command_Event, every
     * component score with its applied weight (excluded components carried with their reason), the
     * composite Divergence_Score, the Corrective_Action and reason code, intent presence/source, the
     * explanation (for ask/block), and the profile state (Req 5.7, 7.4, 8.3, 11.1).
     */
    private static AuditHistoryDocument buildAuditRecord(
            CommandEvent event,
            DivergenceResult result,
            Decision decision,
            String explanation,
            ProfileState profileState,
            IntentSource intentSource) {
        AuditHistoryDocument record = new AuditHistoryDocument();
        record.setEventId(event.eventId());
        record.setUserId(event.userId());
        record.setActorType(event.actorType().name());
        record.setHumanPrincipalId(event.actor().humanPrincipalId());
        record.setSessionId(event.sessionId());
        record.setCommandText(event.commandText());
        record.setCwd(event.cwd());
        record.setRepo(event.repo());
        record.setEnvContext(new java.util.LinkedHashMap<>(event.envContext()));
        record.setTimestamp(event.timestamp());
        record.setInputOrigin(event.inputOrigin().name());
        record.setSignalSource(event.signalSource().name());

        List<ComponentScoreDocument> components = new ArrayList<>(result.components().size());
        for (ComponentResult component : result.components()) {
            Double score = component.score().isPresent() ? component.score().getAsDouble() : null;
            components.add(
                    new ComponentScoreDocument(component.id().name(), score, component.weight(), component.note()));
        }
        record.setComponents(components);

        List<String> excluded = new ArrayList<>(result.excluded().size());
        for (ComponentId id : result.excluded()) {
            excluded.add(id.name());
        }
        record.setExcludedComponents(excluded);

        record.setDivergenceScore(decision.score());
        record.setCorrectiveAction(decision.action().name());
        record.setReasonCode(decision.reasonCode());
        record.setIntentPresent(intentSource != IntentSource.NONE);
        record.setIntentSource(intentSource.name());
        record.setExplanation(explanation);
        record.setProfileState(profileState.name());
        record.setRecordType(RECORD_TYPE_DECISION);
        return record;
    }

    private static ThresholdConfiguration defaultConfig() {
        Map<ComponentId, Double> weights = new EnumMap<>(ComponentId.class);
        weights.put(ComponentId.SEQUENCE_SURPRISE, 0.25);
        weights.put(ComponentId.CONTEXT_MISMATCH, 0.20);
        weights.put(ComponentId.BEHAVIORAL_DEVIATION, 0.25);
        weights.put(ComponentId.SEMANTIC_INCONSISTENCY, 0.30);
        return new ThresholdConfiguration(
                1,
                0.4,
                0.7,
                weights,
                0.15,
                200,
                5000,
                15000,
                1200,
                1000,
                "default",
                0L);
    }
}
