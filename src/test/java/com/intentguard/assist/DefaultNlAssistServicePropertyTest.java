package com.intentguard.assist;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.IntRange;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.intentguard.blastradius.BlastRadiusGuard;
import com.intentguard.blastradius.BlastRadiusResult;
import com.intentguard.blastradius.GuardrailConfig;
import com.intentguard.blastradius.GuardrailConfigService;
import com.intentguard.config.ThresholdConfiguration;
import com.intentguard.config.ThresholdConfigurationService;
import com.intentguard.decision.GuardrailDecisionEngine;
import com.intentguard.domain.Actor;
import com.intentguard.domain.CorrectiveAction;
import com.intentguard.domain.Decision;
import com.intentguard.domain.DivergenceResult;
import com.intentguard.domain.IntentSource;
import com.intentguard.intent.InboundIntentResult;
import com.intentguard.intent.InboundIntentService;
import com.intentguard.intent.IntentChange;
import com.intentguard.intent.IntentSession;
import com.intentguard.intent.IntentSessionManager;
import com.intentguard.scoring.ScoringPipeline;
import com.intentguard.translation.LanguageTag;

/**
 * Property-based tests for {@link DefaultNlAssistService} orchestrator invariants.
 *
 * <p><b>Validates: Requirements 4.1, 4.2, 4.3, 5.4, 5.5, 6.1</b>
 *
 * <ul>
 *   <li>Property 5: Intent_Session lifecycle consistency — a single Intent_Session is opened per
 *       Assist_Session and reused (modified) across N queries.</li>
 *   <li>Property 8: Decision controls execution eligibility — BLOCK prevents confirm(),
 *       ALLOW/ASK allows confirm().</li>
 * </ul>
 */
class DefaultNlAssistServicePropertyTest {

    private static final long FIXED_TIME = 1_700_000_000_000L;
    private static final Clock FIXED_CLOCK = Clock.fixed(
            Instant.ofEpochMilli(FIXED_TIME), ZoneOffset.UTC);

    // ===========================================================================================
    // Property 5: Intent_Session lifecycle consistency
    // Validates: Requirements 4.1, 4.2, 4.3
    // ===========================================================================================

    /**
     * Property 5: Given an Assist_Session that already has an intentSessionId set, calling query()
     * N additional times (follow-up queries) should always reuse the same Intent_Session via
     * modify() rather than opening a new one. The invariant is: exactly 1 open (first query) and
     * N modify calls (follow-up queries).
     */
    @Property(tries = 50)
    void singleIntentSessionReusedAcrossMultipleQueries(
            @ForAll("queryCountsAboveOne") int totalQueries,
            @ForAll("operatorIds") String operatorId) {

        // --- Arrange ---
        String sessionId = UUID.randomUUID().toString();
        String intentSessionId = "intent-" + UUID.randomUUID();

        // Track modify calls
        AtomicInteger modifyCalls = new AtomicInteger(0);

        IntentSessionManager intentSessionManager = mock(IntentSessionManager.class);
        doNothing().when(intentSessionManager).modify(anyString(), any(IntentChange.class), any(Actor.class));
        doNothing().when(intentSessionManager).close(anyString(), any(Actor.class));

        // InboundIntentService: first call opens the session; subsequent calls also return a session
        // (the code opens a new one and closes it for follow-ups)
        InboundIntentService inboundIntentService = mock(InboundIntentService.class);
        IntentSession intentSession = new IntentSession(
                intentSessionId, operatorId, "list files",
                IntentSource.DECLARED, FIXED_TIME, null, true);
        when(inboundIntentService.submit(anyString(), anyString(), any(LanguageTag.class), any(Actor.class)))
                .thenReturn(InboundIntentResult.sessionOpened(intentSession));

        // CommandGenerator: always return valid alternatives
        CommandGenerator commandGenerator = mock(CommandGenerator.class);
        when(commandGenerator.generate(anyString(), any()))
                .thenReturn(List.of(
                        new CommandAlternative("ls -la", "List all files in long format", 0),
                        new CommandAlternative("find . -type f", "Find all files recursively", 1)));

        // GenerationBlocklist: pass-through (no blocking)
        AssistProperties props = new AssistProperties();
        props.setBlocklist(List.of());
        GenerationBlocklist blocklist = new GenerationBlocklist(props);

        // AssistSessionManager: track session state transitions
        AssistSessionManager sessionManager = mock(AssistSessionManager.class);

        // First call: session with no intentSessionId (triggers open)
        AssistSession initialSession = new AssistSession(
                sessionId, operatorId, null, List.of(), List.of(), null,
                FIXED_TIME, FIXED_TIME, true);

        // After first query: session has intentSessionId set
        AssistSession sessionWithIntent = new AssistSession(
                sessionId, operatorId, intentSessionId, List.of(), List.of(), null,
                FIXED_TIME, FIXED_TIME, true);

        // First getOrCreate returns session without intent; subsequent calls return session with intent
        AtomicInteger getOrCreateCalls = new AtomicInteger(0);
        when(sessionManager.getOrCreate(eq(sessionId), eq(operatorId)))
                .thenAnswer(inv -> {
                    int call = getOrCreateCalls.incrementAndGet();
                    if (call == 1) {
                        return initialSession;
                    }
                    return sessionWithIntent;
                });
        doNothing().when(sessionManager).update(any(AssistSession.class));

        // AssistRateLimiter: no-op (never throttle in tests)
        AssistProperties rateLimitProps = new AssistProperties();
        rateLimitProps.setRateLimitPerMinute(1000);
        AssistRateLimiter rateLimiter = new AssistRateLimiter(rateLimitProps);

        // Remaining mocks
        AssistAuditRepository auditRepository = mock(AssistAuditRepository.class);
        ThresholdConfigurationService thresholdConfigService = mock(ThresholdConfigurationService.class);
        GuardrailConfigService guardrailConfigService = mock(GuardrailConfigService.class);
        ScoringPipeline scoringPipeline = mock(ScoringPipeline.class);
        GuardrailDecisionEngine guardrailDecisionEngine = mock(GuardrailDecisionEngine.class);
        BlastRadiusGuard blastRadiusGuard = mock(BlastRadiusGuard.class);
        CommandExecutor commandExecutor = mock(CommandExecutor.class);

        DefaultNlAssistService service = new DefaultNlAssistService(
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
                thresholdConfigService,
                guardrailConfigService,
                FIXED_CLOCK);

        // --- Act ---
        // First query: opens Intent_Session
        AssistRequest firstRequest = new AssistRequest("list files in directory", null, sessionId);
        AssistResponse firstResponse = service.query(operatorId, firstRequest);
        assertThat(firstResponse.sessionId()).isEqualTo(sessionId);

        // Follow-up queries: should reuse the Intent_Session via modify()
        for (int i = 1; i < totalQueries; i++) {
            AssistRequest followUp = new AssistRequest("show disk usage", null, sessionId);
            service.query(operatorId, followUp);
        }

        // --- Assert ---
        // InboundIntentService.submit() should have been called exactly once (for the first query)
        verify(inboundIntentService, times(1))
                .submit(anyString(), anyString(), any(LanguageTag.class), any(Actor.class));

        // IntentSessionManager.modify() should have been called exactly (totalQueries - 1) times
        verify(intentSessionManager, times(totalQueries - 1))
                .modify(eq(intentSessionId), any(IntentChange.class), any(Actor.class));

        // IntentSessionManager should NOT have opened any additional sessions
        verify(intentSessionManager, never())
                .open(anyString(), anyString(), any(Actor.class));
    }

    // ===========================================================================================
    // Property 8: Decision controls execution eligibility
    // Validates: Requirements 5.4, 5.5, 6.1
    // ===========================================================================================

    /**
     * Property 8a: When the last scored decision is BLOCK, confirm() MUST throw
     * AssistBlockedException — execution is never permitted for a blocked command.
     */
    @Property(tries = 50)
    void blockDecisionPreventsExecution(
            @ForAll("operatorIds") String operatorId,
            @ForAll("blockScores") double blockScore) {

        // --- Arrange ---
        String sessionId = UUID.randomUUID().toString();

        Decision blockDecision = new Decision(CorrectiveAction.BLOCK, blockScore, "THRESHOLD_BLOCK");

        List<CommandAlternative> alternatives = List.of(
                new CommandAlternative("rm -rf /tmp/test", "Remove test directory", 0),
                new CommandAlternative("find /tmp -delete", "Delete all files in tmp", 1));

        AssistSession session = new AssistSession(
                sessionId, operatorId, "intent-123", List.of(), alternatives, blockDecision,
                FIXED_TIME, FIXED_TIME, true);

        AssistSessionManager sessionManager = mock(AssistSessionManager.class);
        when(sessionManager.get(sessionId)).thenReturn(Optional.of(session));

        DefaultNlAssistService service = buildServiceForConfirmTests(sessionManager);

        // --- Act + Assert ---
        ConfirmRequest request = new ConfirmRequest(sessionId, 0);
        assertThatThrownBy(() -> service.confirm(operatorId, request))
                .isInstanceOf(AssistBlockedException.class);
    }

    /**
     * Property 8b: When the last scored decision is ALLOW, confirm() succeeds — the command
     * is executed and a result is returned.
     */
    @Property(tries = 50)
    void allowDecisionPermitsExecution(
            @ForAll("operatorIds") String operatorId,
            @ForAll("allowScores") double allowScore) {

        // --- Arrange ---
        String sessionId = UUID.randomUUID().toString();

        Decision allowDecision = new Decision(CorrectiveAction.ALLOW, allowScore, "THRESHOLD_ALLOW");

        List<CommandAlternative> alternatives = List.of(
                new CommandAlternative("echo hello", "Print hello", 0),
                new CommandAlternative("date", "Show current date", 1));

        AssistSession session = new AssistSession(
                sessionId, operatorId, "intent-123", List.of(), alternatives, allowDecision,
                FIXED_TIME, FIXED_TIME, true);

        AssistSessionManager sessionManager = mock(AssistSessionManager.class);
        when(sessionManager.get(sessionId)).thenReturn(Optional.of(session));
        doNothing().when(sessionManager).update(any(AssistSession.class));

        CommandExecutor commandExecutor = mock(CommandExecutor.class);
        when(commandExecutor.execute(anyString(), any()))
                .thenReturn(new ExecutionResult("echo hello", "hello\n", "", 0, FIXED_TIME));

        DefaultNlAssistService service = buildServiceForConfirmTests(sessionManager, commandExecutor);

        // --- Act ---
        ConfirmRequest request = new ConfirmRequest(sessionId, 0);
        ConfirmResponse response = service.confirm(operatorId, request);

        // --- Assert ---
        assertThat(response).isNotNull();
        assertThat(response.command()).isEqualTo("echo hello");
        assertThat(response.success()).isTrue();
        assertThat(response.exitCode()).isEqualTo(0);
    }

    /**
     * Property 8c: When the last scored decision is ASK, confirm() succeeds — ASK means the
     * operator was prompted and has now confirmed, so execution should proceed.
     */
    @Property(tries = 50)
    void askDecisionPermitsExecution(
            @ForAll("operatorIds") String operatorId,
            @ForAll("askScores") double askScore) {

        // --- Arrange ---
        String sessionId = UUID.randomUUID().toString();

        Decision askDecision = new Decision(CorrectiveAction.ASK, askScore, "THRESHOLD_ASK");

        List<CommandAlternative> alternatives = List.of(
                new CommandAlternative("systemctl restart nginx", "Restart nginx service", 0));

        AssistSession session = new AssistSession(
                sessionId, operatorId, "intent-456", List.of(), alternatives, askDecision,
                FIXED_TIME, FIXED_TIME, true);

        AssistSessionManager sessionManager = mock(AssistSessionManager.class);
        when(sessionManager.get(sessionId)).thenReturn(Optional.of(session));
        doNothing().when(sessionManager).update(any(AssistSession.class));

        CommandExecutor commandExecutor = mock(CommandExecutor.class);
        when(commandExecutor.execute(anyString(), any()))
                .thenReturn(new ExecutionResult("systemctl restart nginx", "", "", 0, FIXED_TIME));

        DefaultNlAssistService service = buildServiceForConfirmTests(sessionManager, commandExecutor);

        // --- Act ---
        ConfirmRequest request = new ConfirmRequest(sessionId, 0);
        ConfirmResponse response = service.confirm(operatorId, request);

        // --- Assert ---
        assertThat(response).isNotNull();
        assertThat(response.command()).isEqualTo("systemctl restart nginx");
        assertThat(response.success()).isTrue();
    }

    /**
     * Property 8d: When lastScoredDecision is null (no prior /select call), confirm() throws
     * AssistBlockedException — the command cannot be executed without being scored first.
     */
    @Property(tries = 30)
    void nullDecisionPreventsExecution(
            @ForAll("operatorIds") String operatorId) {

        // --- Arrange ---
        String sessionId = UUID.randomUUID().toString();

        List<CommandAlternative> alternatives = List.of(
                new CommandAlternative("whoami", "Show current user", 0));

        // lastScoredDecision is null
        AssistSession session = new AssistSession(
                sessionId, operatorId, "intent-789", List.of(), alternatives, null,
                FIXED_TIME, FIXED_TIME, true);

        AssistSessionManager sessionManager = mock(AssistSessionManager.class);
        when(sessionManager.get(sessionId)).thenReturn(Optional.of(session));

        DefaultNlAssistService service = buildServiceForConfirmTests(sessionManager);

        // --- Act + Assert ---
        ConfirmRequest request = new ConfirmRequest(sessionId, 0);
        assertThatThrownBy(() -> service.confirm(operatorId, request))
                .isInstanceOf(AssistBlockedException.class);
    }

    // ===========================================================================================
    // Helpers
    // ===========================================================================================

    /**
     * Builds a DefaultNlAssistService wired for confirm()-only testing with a given
     * session manager and a no-op CommandExecutor.
     */
    private DefaultNlAssistService buildServiceForConfirmTests(AssistSessionManager sessionManager) {
        CommandExecutor commandExecutor = mock(CommandExecutor.class);
        return buildServiceForConfirmTests(sessionManager, commandExecutor);
    }

    /**
     * Builds a DefaultNlAssistService wired for confirm()-only testing with specific
     * session manager and command executor.
     */
    private DefaultNlAssistService buildServiceForConfirmTests(
            AssistSessionManager sessionManager, CommandExecutor commandExecutor) {

        CommandGenerator commandGenerator = mock(CommandGenerator.class);
        AssistProperties props = new AssistProperties();
        props.setBlocklist(List.of());
        GenerationBlocklist blocklist = new GenerationBlocklist(props);
        AssistRateLimiter rateLimiter = mock(AssistRateLimiter.class);
        InboundIntentService inboundIntentService = mock(InboundIntentService.class);
        IntentSessionManager intentSessionManager = mock(IntentSessionManager.class);
        ScoringPipeline scoringPipeline = mock(ScoringPipeline.class);
        GuardrailDecisionEngine guardrailDecisionEngine = mock(GuardrailDecisionEngine.class);
        BlastRadiusGuard blastRadiusGuard = mock(BlastRadiusGuard.class);
        AssistAuditRepository auditRepository = mock(AssistAuditRepository.class);
        ThresholdConfigurationService thresholdConfigService = mock(ThresholdConfigurationService.class);
        GuardrailConfigService guardrailConfigService = mock(GuardrailConfigService.class);

        return new DefaultNlAssistService(
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
                thresholdConfigService,
                guardrailConfigService,
                FIXED_CLOCK);
    }

    // ===========================================================================================
    // Providers
    // ===========================================================================================

    @Provide
    Arbitrary<String> operatorIds() {
        return Arbitraries.strings()
                .ofMinLength(3)
                .ofMaxLength(15)
                .alpha()
                .map(s -> "op-" + s);
    }

    @Provide
    Arbitrary<Integer> queryCountsAboveOne() {
        return Arbitraries.integers().between(2, 8);
    }

    /**
     * Scores in the BLOCK range: [0.7, 1.0] — the exact threshold depends on config,
     * but any score in this range is plausible for a BLOCK decision.
     */
    @Provide
    Arbitrary<Double> blockScores() {
        return Arbitraries.doubles().between(0.7, 1.0);
    }

    /**
     * Scores in the ALLOW range: [0.0, 0.3] — low divergence scores.
     */
    @Provide
    Arbitrary<Double> allowScores() {
        return Arbitraries.doubles().between(0.0, 0.3);
    }

    /**
     * Scores in the ASK range: [0.3, 0.7] — intermediate divergence scores.
     */
    @Provide
    Arbitrary<Double> askScores() {
        return Arbitraries.doubles().between(0.3, 0.7);
    }
}
