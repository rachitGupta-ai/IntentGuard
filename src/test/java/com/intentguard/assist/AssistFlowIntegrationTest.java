package com.intentguard.assist;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.intentguard.blastradius.BlastRadiusGuard;
import com.intentguard.blastradius.BlastRadiusResult;
import com.intentguard.blastradius.GuardrailConfig;
import com.intentguard.blastradius.GuardrailConfigService;
import com.intentguard.config.ThresholdConfiguration;
import com.intentguard.config.ThresholdConfigurationService;
import com.intentguard.decision.GuardrailDecisionEngine;
import com.intentguard.domain.CommandEvent;
import com.intentguard.domain.ComponentId;
import com.intentguard.domain.CorrectiveAction;
import com.intentguard.domain.Decision;
import com.intentguard.domain.DivergenceResult;
import com.intentguard.domain.IntentSource;
import com.intentguard.domain.ProfileState;
import com.intentguard.domain.ScoringContext;
import com.intentguard.intent.InboundIntentResult;
import com.intentguard.intent.InboundIntentService;
import com.intentguard.intent.IntentSession;
import com.intentguard.intent.IntentSessionManager;
import com.intentguard.scoring.ScoringPipeline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Integration test that exercises the full NL Operations Assistant lifecycle:
 * query → select → confirm → closeSession.
 *
 * <p>Uses manual wiring with mocked external dependencies (same pattern as
 * {@link AssistContextLoadTest}). The following components are real:
 * <ul>
 *   <li>{@link AssistSessionManager}</li>
 *   <li>{@link GenerationBlocklist}</li>
 *   <li>{@link AssistRateLimiter}</li>
 *   <li>{@link GeminiCommandGenerator} (with a stubbed {@link AssistTextGenerator})</li>
 * </ul>
 *
 * <p><b>Property 9: Execution output capture and audit</b> (integration subset)
 * <p><b>Validates: Requirements 6.2, 6.3, 6.4, 10.2</b>
 */
@ExtendWith(MockitoExtension.class)
class AssistFlowIntegrationTest {

    private static final long FIXED_TIME = 1_700_000_000_000L;
    private static final Clock FIXED_CLOCK = Clock.fixed(
            Instant.ofEpochMilli(FIXED_TIME), ZoneOffset.UTC);
    private static final String OPERATOR_ID = "operator-1";
    private static final String INTENT_SESSION_ID = "intent-session-123";

    private static final String KNOWN_JSON_RESPONSE = """
            [{"command":"echo hello","explanation":"Print hello"},{"command":"pwd","explanation":"Print working directory"}]""";

    // External dependencies — mocked
    @Mock InboundIntentService inboundIntentService;
    @Mock IntentSessionManager intentSessionManager;
    @Mock ScoringPipeline scoringPipeline;
    @Mock GuardrailDecisionEngine guardrailDecisionEngine;
    @Mock BlastRadiusGuard blastRadiusGuard;
    @Mock ThresholdConfigurationService thresholdConfigurationService;
    @Mock GuardrailConfigService guardrailConfigService;
    @Mock AssistAuditRepository auditRepository;
    @Mock CommandExecutor commandExecutor;

    // Real components
    private AssistProperties properties;
    private GenerationBlocklist blocklist;
    private AssistRateLimiter rateLimiter;
    private AssistSessionManager sessionManager;
    private GeminiCommandGenerator commandGenerator;
    private DefaultNlAssistService service;

    @BeforeEach
    void setUp() {
        // Real infrastructure
        properties = new AssistProperties();
        blocklist = new GenerationBlocklist(properties);
        rateLimiter = new AssistRateLimiter(properties);
        sessionManager = new AssistSessionManager(properties, intentSessionManager);

        // Stub text generator: returns known JSON
        AssistTextGenerator stubTextGenerator = prompt -> KNOWN_JSON_RESPONSE;
        commandGenerator = new GeminiCommandGenerator(stubTextGenerator);

        // Wire the orchestrator with real + mocked dependencies
        service = new DefaultNlAssistService(
                commandGenerator,
                blocklist,
                sessionManager,
                rateLimiter,
                inboundIntentService,
                intentSessionManager,
                scoringPipeline,
                guardrailDecisionEngine,
                blastRadiusGuard,
                commandExecutor,
                auditRepository,
                thresholdConfigurationService,
                guardrailConfigService,
                FIXED_CLOCK);
    }

    @Test
    void fullLifecycle_querySelectConfirmClose() {
        // --- Arrange: stub external dependencies ---

        // InboundIntentService.submit() → returns an opened session
        IntentSession intentSession = new IntentSession(
                INTENT_SESSION_ID,
                OPERATOR_ID,
                "print hello",
                IntentSource.DECLARED,
                FIXED_TIME,
                null,
                true);
        when(inboundIntentService.submit(eq(OPERATOR_ID), eq("print hello"), any(), any()))
                .thenReturn(InboundIntentResult.sessionOpened(intentSession));

        // ScoringPipeline.score() → returns a DivergenceResult with composite 0.2
        DivergenceResult divergenceResult = new DivergenceResult(0.2, List.of(), Set.of());
        when(scoringPipeline.score(any(ScoringContext.class))).thenReturn(divergenceResult);

        // GuardrailDecisionEngine.decide() → returns ALLOW with score 0.2
        Decision allowDecision = new Decision(CorrectiveAction.ALLOW, 0.2, "THRESHOLD_ALLOW");
        when(guardrailDecisionEngine.decide(
                any(CommandEvent.class),
                any(DivergenceResult.class),
                any(ThresholdConfiguration.class),
                any(ProfileState.class),
                anyBoolean(),
                any()))
                .thenReturn(allowDecision);

        // BlastRadiusGuard.evaluate() → no hits
        when(blastRadiusGuard.evaluate(any(CommandEvent.class), any(GuardrailConfig.class)))
                .thenReturn(BlastRadiusResult.none());

        // ThresholdConfigurationService → returns a valid ThresholdConfiguration
        Map<ComponentId, Double> weights = Map.of(
                ComponentId.SEQUENCE_SURPRISE, 0.25,
                ComponentId.CONTEXT_MISMATCH, 0.25,
                ComponentId.BEHAVIORAL_DEVIATION, 0.25,
                ComponentId.SEMANTIC_INCONSISTENCY, 0.25);
        ThresholdConfiguration thresholdConfig = new ThresholdConfiguration(
                1, 0.4, 0.8, weights, 0.15, 200,
                5000L, 15000L, 1200L, 1000L, "system", FIXED_TIME);
        when(thresholdConfigurationService.getActiveConfig())
                .thenReturn(Optional.of(thresholdConfig));

        // GuardrailConfigService → returns defaults
        when(guardrailConfigService.getActiveConfig())
                .thenReturn(Optional.of(GuardrailConfig.defaults("system", FIXED_TIME)));

        // CommandExecutor.execute() → successful execution
        ExecutionResult executionResult = new ExecutionResult(
                "echo hello", "hello\n", "", 0, FIXED_TIME);
        when(commandExecutor.execute(eq("echo hello"), any()))
                .thenReturn(executionResult);

        // --- Act: Step 1 — query ---
        AssistRequest queryRequest = new AssistRequest("print hello", null, null);
        AssistResponse queryResponse = service.query(OPERATOR_ID, queryRequest);

        // --- Assert: query response ---
        assertThat(queryResponse).isNotNull();
        assertThat(queryResponse.sessionId()).isNotNull().isNotBlank();
        assertThat(queryResponse.queryEcho()).isEqualTo("print hello");
        assertThat(queryResponse.alternatives()).hasSize(2);
        assertThat(queryResponse.alternatives().get(0).command()).isEqualTo("echo hello");
        assertThat(queryResponse.alternatives().get(0).explanation()).isEqualTo("Print hello");
        assertThat(queryResponse.alternatives().get(1).command()).isEqualTo("pwd");
        assertThat(queryResponse.alternatives().get(1).explanation()).isEqualTo("Print working directory");

        String sessionId = queryResponse.sessionId();

        // --- Act: Step 2 — select ---
        SelectRequest selectRequest = new SelectRequest(sessionId, 0);
        SelectResponse selectResponse = service.select(OPERATOR_ID, selectRequest);

        // --- Assert: select response ---
        assertThat(selectResponse).isNotNull();
        assertThat(selectResponse.sessionId()).isEqualTo(sessionId);
        assertThat(selectResponse.command()).isEqualTo("echo hello");
        assertThat(selectResponse.score()).isEqualTo(0.2);
        assertThat(selectResponse.action()).isEqualTo("ALLOW");
        assertThat(selectResponse.blocked()).isFalse();
        assertThat(selectResponse.explanation()).contains("0.200");

        // --- Act: Step 3 — confirm ---
        ConfirmRequest confirmRequest = new ConfirmRequest(sessionId, 0);
        ConfirmResponse confirmResponse = service.confirm(OPERATOR_ID, confirmRequest);

        // --- Assert: confirm response (Req 6.2) ---
        assertThat(confirmResponse).isNotNull();
        assertThat(confirmResponse.sessionId()).isEqualTo(sessionId);
        assertThat(confirmResponse.command()).isEqualTo("echo hello");
        assertThat(confirmResponse.stdout()).isEqualTo("hello\n");
        assertThat(confirmResponse.stderr()).isEmpty();
        assertThat(confirmResponse.exitCode()).isZero();
        assertThat(confirmResponse.success()).isTrue();
        // No failure suggestion when exit code is 0 (Req 6.4)
        assertThat(confirmResponse.suggestion()).isNull();

        // --- Act: Step 4 — close session ---
        service.closeSession(OPERATOR_ID, sessionId);

        // --- Assert: Intent_Session was closed (Req 4.4) ---
        verify(intentSessionManager).close(eq(INTENT_SESSION_ID), any());

        // --- Assert: Audit entries persisted (Req 10.2) ---
        verify(auditRepository).saveQuery(eq(sessionId), eq(OPERATOR_ID), eq("print hello"), any());
        verify(auditRepository).saveSelection(
                eq(sessionId), eq("echo hello"), eq(0.2), eq("ALLOW"), eq(false));
        verify(auditRepository).saveExecution(
                eq(sessionId), eq("echo hello"), eq(0), eq("hello\n"), eq(""));
    }

    @Test
    void confirm_failedCommand_includesStderrAndSuggestion() {
        // --- Arrange ---
        IntentSession intentSession = new IntentSession(
                INTENT_SESSION_ID, OPERATOR_ID, "list files",
                IntentSource.DECLARED, FIXED_TIME, null, true);
        when(inboundIntentService.submit(eq(OPERATOR_ID), eq("list files"), any(), any()))
                .thenReturn(InboundIntentResult.sessionOpened(intentSession));

        // Text generator returns a command that will "fail"
        AssistTextGenerator failGenerator = prompt -> """
                [{"command":"ls /nonexistent","explanation":"List a nonexistent directory"}]""";
        GeminiCommandGenerator failCommandGen = new GeminiCommandGenerator(failGenerator);

        DefaultNlAssistService failService = new DefaultNlAssistService(
                failCommandGen, blocklist, sessionManager, rateLimiter,
                inboundIntentService, intentSessionManager, scoringPipeline,
                guardrailDecisionEngine, blastRadiusGuard, commandExecutor,
                auditRepository, thresholdConfigurationService, guardrailConfigService,
                FIXED_CLOCK);

        DivergenceResult divergenceResult = new DivergenceResult(0.1, List.of(), Set.of());
        when(scoringPipeline.score(any(ScoringContext.class))).thenReturn(divergenceResult);

        Decision allowDecision = new Decision(CorrectiveAction.ALLOW, 0.1, "THRESHOLD_ALLOW");
        when(guardrailDecisionEngine.decide(any(), any(), any(), any(), anyBoolean(), any()))
                .thenReturn(allowDecision);
        when(blastRadiusGuard.evaluate(any(), any())).thenReturn(BlastRadiusResult.none());

        Map<ComponentId, Double> weights = Map.of(
                ComponentId.SEQUENCE_SURPRISE, 0.25,
                ComponentId.CONTEXT_MISMATCH, 0.25,
                ComponentId.BEHAVIORAL_DEVIATION, 0.25,
                ComponentId.SEMANTIC_INCONSISTENCY, 0.25);
        ThresholdConfiguration thresholdConfig = new ThresholdConfiguration(
                1, 0.4, 0.8, weights, 0.15, 200,
                5000L, 15000L, 1200L, 1000L, "system", FIXED_TIME);
        when(thresholdConfigurationService.getActiveConfig())
                .thenReturn(Optional.of(thresholdConfig));
        when(guardrailConfigService.getActiveConfig())
                .thenReturn(Optional.of(GuardrailConfig.defaults("system", FIXED_TIME)));

        // Command execution fails with exit code 2
        ExecutionResult failedResult = new ExecutionResult(
                "ls /nonexistent", "", "ls: cannot access '/nonexistent': No such file or directory", 2, FIXED_TIME);
        when(commandExecutor.execute(eq("ls /nonexistent"), any()))
                .thenReturn(failedResult);

        // --- Act ---
        AssistResponse queryResp = failService.query(OPERATOR_ID, new AssistRequest("list files", null, null));
        String sessionId = queryResp.sessionId();

        failService.select(OPERATOR_ID, new SelectRequest(sessionId, 0));
        ConfirmResponse confirmResp = failService.confirm(OPERATOR_ID, new ConfirmRequest(sessionId, 0));

        // --- Assert: failed command response (Req 6.3, 6.4) ---
        assertThat(confirmResp.exitCode()).isEqualTo(2);
        assertThat(confirmResp.success()).isFalse();
        assertThat(confirmResp.stderr()).contains("No such file or directory");
        // Suggestion provided for failed commands (Req 6.4)
        assertThat(confirmResp.suggestion()).isNotNull().isNotBlank();

        // Audit records the failure
        verify(auditRepository).saveExecution(
                eq(sessionId), eq("ls /nonexistent"), eq(2), eq(""),
                eq("ls: cannot access '/nonexistent': No such file or directory"));
    }
}
