package com.intentguard.assist;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.IntStream;

import org.springframework.stereotype.Service;

import com.intentguard.blastradius.BlastRadiusGuard;
import com.intentguard.blastradius.BlastRadiusResult;
import com.intentguard.blastradius.GuardrailConfig;
import com.intentguard.blastradius.GuardrailConfigService;
import com.intentguard.config.ThresholdConfiguration;
import com.intentguard.config.ThresholdConfigurationService;
import com.intentguard.decision.DecisionEngine;
import com.intentguard.decision.DualControlStatus;
import com.intentguard.decision.GuardrailContext;
import com.intentguard.decision.GuardrailDecisionEngine;
import com.intentguard.domain.Actor;
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
import com.intentguard.intent.InboundIntentResult;
import com.intentguard.intent.InboundIntentService;
import com.intentguard.intent.IntentChange;
import com.intentguard.intent.IntentSession;
import com.intentguard.intent.IntentSessionManager;
import com.intentguard.policy.PolicyDecision;
import com.intentguard.scoring.ScoringPipeline;
import com.intentguard.translation.LanguageTag;
import com.intentguard.translation.SupportedLanguages;

/**
 * Default orchestrator for the NL Operations Assistant. Coordinates the full lifecycle of an
 * assist interaction: translation, generation, safety filtering, intent session management,
 * divergence scoring, blast-radius evaluation, decision, execution, and audit persistence.
 *
 * <p>Implements requirements 1.1–1.3, 2.1, 3.3–3.4, 4.1–4.4, 5.1–5.5, 6.1–6.4, 8.1.
 */
@Service
public class DefaultNlAssistService implements NlAssistService {

    private final CommandGenerator commandGenerator;
    private final GenerationBlocklist generationBlocklist;
    private final AssistSessionManager sessionManager;
    private final AssistRateLimiter rateLimiter;
    private final InboundIntentService inboundIntentService;
    private final IntentSessionManager intentSessionManager;
    private final ScoringPipeline scoringPipeline;
    private final GuardrailDecisionEngine guardrailDecisionEngine;
    private final BlastRadiusGuard blastRadiusGuard;
    private final CommandExecutor commandExecutor;
    private final AssistAuditRepository auditRepository;
    private final ThresholdConfigurationService thresholdConfigurationService;
    private final GuardrailConfigService guardrailConfigService;
    private final Clock clock;

    public DefaultNlAssistService(
            CommandGenerator commandGenerator,
            GenerationBlocklist generationBlocklist,
            AssistSessionManager sessionManager,
            AssistRateLimiter rateLimiter,
            InboundIntentService inboundIntentService,
            IntentSessionManager intentSessionManager,
            ScoringPipeline scoringPipeline,
            GuardrailDecisionEngine guardrailDecisionEngine,
            BlastRadiusGuard blastRadiusGuard,
            CommandExecutor commandExecutor,
            AssistAuditRepository auditRepository,
            ThresholdConfigurationService thresholdConfigurationService,
            GuardrailConfigService guardrailConfigService,
            Clock clock) {
        this.commandGenerator = Objects.requireNonNull(commandGenerator);
        this.generationBlocklist = Objects.requireNonNull(generationBlocklist);
        this.sessionManager = Objects.requireNonNull(sessionManager);
        this.rateLimiter = Objects.requireNonNull(rateLimiter);
        this.inboundIntentService = Objects.requireNonNull(inboundIntentService);
        this.intentSessionManager = Objects.requireNonNull(intentSessionManager);
        this.scoringPipeline = Objects.requireNonNull(scoringPipeline);
        this.guardrailDecisionEngine = Objects.requireNonNull(guardrailDecisionEngine);
        this.blastRadiusGuard = Objects.requireNonNull(blastRadiusGuard);
        this.commandExecutor = Objects.requireNonNull(commandExecutor);
        this.auditRepository = Objects.requireNonNull(auditRepository);
        this.thresholdConfigurationService = Objects.requireNonNull(thresholdConfigurationService);
        this.guardrailConfigService = Objects.requireNonNull(guardrailConfigService);
        this.clock = Objects.requireNonNull(clock);
    }

    // -------------------------------------------------------------------------------------------
    // query() — Requirements 1.1, 1.2, 1.3, 2.1, 3.3, 3.4, 4.1, 4.2, 4.3, 8.1
    // -------------------------------------------------------------------------------------------

    @Override
    public AssistResponse query(String operatorId, AssistRequest request) {
        Objects.requireNonNull(operatorId, "operatorId must not be null");
        Objects.requireNonNull(request, "request must not be null");

        // 1. Rate-limit check (Req 8.1)
        rateLimiter.checkAndRecord(operatorId, clock.millis());

        // 2. Resolve or create session
        String sessionId = request.sessionId() != null
                ? request.sessionId()
                : UUID.randomUUID().toString();
        AssistSession session = sessionManager.getOrCreate(sessionId, operatorId);

        // 3. Translate if non-English using InboundIntentService (Req 1.2, 1.3, 4.1)
        LanguageTag sourceLang = request.languageTag() != null
                ? LanguageTag.of(request.languageTag())
                : SupportedLanguages.ENGLISH;

        String queryEnglish;

        if (session.intentSessionId() == null) {
            // First query in session: open Intent_Session via InboundIntentService (handles
            // translation internally) — Req 4.1
            Actor actor = Actor.human(operatorId);
            InboundIntentResult intentResult = inboundIntentService.submit(
                    operatorId, request.query(), sourceLang, actor);
            if (!intentResult.opened()) {
                throw new AssistTranslationException(
                        intentResult.message() != null
                                ? intentResult.message()
                                : "Translation failed. Please retry or submit in English.");
            }
            IntentSession intentSession = intentResult.openedSession().orElseThrow();
            session = session.withIntentSessionId(intentSession.sessionId());

            // The engine-language (English) text is the declared intent
            queryEnglish = intentSession.declaredIntent();
        } else {
            // Follow-up query: translate inline if non-English, then modify the existing
            // Intent_Session (Req 4.2, 4.3)
            if (!SupportedLanguages.ENGLISH.equals(sourceLang)) {
                // For follow-up non-English queries, submit via inbound intent to get translation
                Actor actor = Actor.human(operatorId);
                InboundIntentResult intentResult = inboundIntentService.submit(
                        operatorId, request.query(), sourceLang, actor);
                if (!intentResult.opened()) {
                    throw new AssistTranslationException(
                            intentResult.message() != null
                                    ? intentResult.message()
                                    : "Translation failed. Please retry or submit in English.");
                }
                IntentSession newIntentSession = intentResult.openedSession().orElseThrow();
                queryEnglish = newIntentSession.declaredIntent();
                // Close the newly opened intent session since we already have one, and modify the
                // existing instead
                intentSessionManager.close(newIntentSession.sessionId(), Actor.human(operatorId));
                intentSessionManager.modify(
                        session.intentSessionId(),
                        new IntentChange(queryEnglish),
                        Actor.human(operatorId));
            } else {
                queryEnglish = request.query();
                // Modify the existing Intent_Session's declared intent (Req 4.3)
                intentSessionManager.modify(
                        session.intentSessionId(),
                        new IntentChange(queryEnglish),
                        Actor.human(operatorId));
            }
        }

        // 5. Generate alternatives via LLM (Req 2.1)
        List<CommandAlternative> raw = commandGenerator.generate(queryEnglish, session.history());

        // 6. Blocklist filter (Req 3.3, 3.4)
        List<CommandAlternative> filtered = generationBlocklist.filter(raw);
        if (filtered.isEmpty()) {
            throw new AssistBlocklistException(
                    "All generated commands were blocked by safety filters. Please rephrase your request.");
        }

        // 7. Re-index filtered alternatives
        List<CommandAlternative> alternatives = IntStream.range(0, filtered.size())
                .mapToObj(i -> new CommandAlternative(
                        filtered.get(i).command(),
                        filtered.get(i).explanation(),
                        i))
                .toList();

        // 8. Update session state
        session = session.withCurrentAlternatives(alternatives)
                .withLastActivityAt(clock.millis());
        sessionManager.update(session);

        // 9. Audit: log query + alternatives (Req 10.1, 10.2)
        auditRepository.saveQuery(session.sessionId(), operatorId, queryEnglish, alternatives);

        return new AssistResponse(session.sessionId(), queryEnglish, alternatives);
    }

    // -------------------------------------------------------------------------------------------
    // select() — Requirements 5.1, 5.2, 5.3, 5.4, 5.5
    // -------------------------------------------------------------------------------------------

    @Override
    public SelectResponse select(String operatorId, SelectRequest request) {
        Objects.requireNonNull(operatorId, "operatorId must not be null");
        Objects.requireNonNull(request, "request must not be null");

        // 1. Lookup session
        AssistSession session = sessionManager.get(request.sessionId())
                .orElseThrow(() -> new AssistSessionNotFoundException(request.sessionId()));

        // 2. Validate commandIndex bounds
        List<CommandAlternative> alternatives = session.currentAlternatives();
        if (request.commandIndex() >= alternatives.size()) {
            throw new IllegalArgumentException("commandIndex out of range: " + request.commandIndex());
        }
        CommandAlternative selected = alternatives.get(request.commandIndex());

        // 3. Construct CommandEvent for scoring (Req 5.1)
        CommandEvent event = new CommandEvent(
                UUID.randomUUID().toString(),
                Actor.human(operatorId),
                session.intentSessionId(),
                selected.command(),
                System.getProperty("user.dir"),
                null,
                Map.of(),
                clock.millis(),
                InputOrigin.TYPED,
                SignalSource.HOOK,
                IntentSource.DECLARED,
                AgentRiskMarkers.none());

        // 4. Resolve active configurations
        ThresholdConfiguration thresholdConfig = thresholdConfigurationService.getActiveConfig()
                .orElseThrow(() -> new IllegalStateException(
                        "No active Threshold_Configuration; cannot score command"));

        GuardrailConfig guardrailConfig = guardrailConfigService.getActiveConfig()
                .orElse(GuardrailConfig.defaults("system", clock.millis()));

        // 5. Score through full pipeline (Req 5.1)
        ScoringContext ctx = new ScoringContext(
                event,
                session.currentIntentText() != null ? session.currentIntentText() : selected.command(),
                IntentSource.DECLARED,
                ProfileState.ACTIVE,
                thresholdConfig.toScoringConfig());
        DivergenceResult result = scoringPipeline.score(ctx);

        // 6. Evaluate blast radius (Req 5.2)
        BlastRadiusResult brResult = blastRadiusGuard.evaluate(event, guardrailConfig);

        // 7. Decision with full guardrail chain (Req 5.3)
        GuardrailContext guardrailCtx = new GuardrailContext(
                PolicyDecision.none(),
                brResult,
                true,
                DualControlStatus.NONE);
        Decision decision = guardrailDecisionEngine.decide(
                event, result, thresholdConfig, ProfileState.ACTIVE, true, guardrailCtx);

        // 8. Build explanation (Req 5.3)
        String explanation = "Score: " + String.format("%.3f", decision.score())
                + " — Action: " + decision.action()
                + " (" + decision.reasonCode() + ")";

        boolean blocked = decision.action() == CorrectiveAction.BLOCK;

        // 9. Update session with decision
        session = session.withLastScoredDecision(decision)
                .withLastActivityAt(clock.millis());
        sessionManager.update(session);

        // 10. Audit: log selection + score (Req 10.2)
        auditRepository.saveSelection(session.sessionId(), selected.command(),
                decision.score(), decision.action().name(), blocked);

        return new SelectResponse(
                session.sessionId(),
                selected.command(),
                decision.score(),
                decision.action().name(),
                explanation,
                blocked);
    }

    // -------------------------------------------------------------------------------------------
    // confirm() — Requirements 6.1, 6.2, 6.3, 6.4
    // -------------------------------------------------------------------------------------------

    @Override
    public ConfirmResponse confirm(String operatorId, ConfirmRequest request) {
        Objects.requireNonNull(operatorId, "operatorId must not be null");
        Objects.requireNonNull(request, "request must not be null");

        // 1. Lookup session
        AssistSession session = sessionManager.get(request.sessionId())
                .orElseThrow(() -> new AssistSessionNotFoundException(request.sessionId()));

        // 2. Verify last decision not BLOCK (Req 5.4, 6.1)
        Decision lastDecision = session.lastScoredDecision();
        if (lastDecision == null || lastDecision.action() == CorrectiveAction.BLOCK) {
            throw new AssistBlockedException("Command was BLOCKed and cannot be executed.");
        }

        // 3. Validate commandIndex and get selected alternative
        List<CommandAlternative> alternatives = session.currentAlternatives();
        if (request.commandIndex() >= alternatives.size()) {
            throw new IllegalArgumentException("commandIndex out of range: " + request.commandIndex());
        }
        CommandAlternative selected = alternatives.get(request.commandIndex());

        // 4. Execute via CommandExecutor (Req 6.1, 6.2)
        ExecutionResult result = commandExecutor.execute(
                selected.command(), System.getProperty("user.dir"));

        // 5. Build follow-up suggestion if failed (Req 6.4)
        String suggestion = result.exitCode() != 0
                ? "The command failed. You can describe what went wrong or what you'd like to try next."
                : null;

        // 6. Update session history (Req 7.2)
        AssistTurn turn = new AssistTurn(
                session.currentIntentText(),
                session.currentAlternatives(),
                request.commandIndex(),
                result,
                clock.millis());
        session = session.withAddedTurn(turn).withLastActivityAt(clock.millis());
        sessionManager.update(session);

        // 7. Audit: log execution outcome (Req 10.2, 10.3)
        auditRepository.saveExecution(session.sessionId(), selected.command(),
                result.exitCode(), result.stdout(), result.stderr());

        return new ConfirmResponse(
                session.sessionId(),
                selected.command(),
                result.stdout(),
                result.stderr(),
                result.exitCode(),
                result.exitCode() == 0,
                suggestion);
    }

    // -------------------------------------------------------------------------------------------
    // closeSession() — Requirement 4.4
    // -------------------------------------------------------------------------------------------

    @Override
    public void closeSession(String operatorId, String sessionId) {
        Objects.requireNonNull(operatorId, "operatorId must not be null");
        Objects.requireNonNull(sessionId, "sessionId must not be null");
        sessionManager.close(sessionId);
    }
}
