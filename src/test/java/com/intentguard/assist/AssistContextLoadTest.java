package com.intentguard.assist;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.intentguard.blastradius.BlastRadiusGuard;
import com.intentguard.blastradius.GuardrailConfigService;
import com.intentguard.config.ThresholdConfigurationService;
import com.intentguard.decision.GuardrailDecisionEngine;
import com.intentguard.intent.InboundIntentService;
import com.intentguard.intent.IntentSessionManager;
import com.intentguard.scoring.ScoringPipeline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Verifies that all components in the {@code com.intentguard.assist} package can be instantiated
 * and wired together without circular dependencies or missing constructor parameters.
 *
 * <p>This test does NOT start the full Spring context (which requires MongoDB, Gemini API key,
 * Unix domain socket, etc.). Instead it manually instantiates each {@code @Component/@Service}
 * bean with mocked external dependencies, proving:
 * <ul>
 *   <li>No circular dependencies in the assist package</li>
 *   <li>All constructor parameters are satisfiable</li>
 *   <li>The full dependency graph is acyclic</li>
 * </ul>
 *
 * <p><b>Validates: Requirements All (task 10.1)</b>
 */
@ExtendWith(MockitoExtension.class)
class AssistContextLoadTest {

    private static final Clock FIXED_CLOCK = Clock.fixed(
            Instant.ofEpochMilli(1_700_000_000_000L), ZoneOffset.UTC);

    // External dependencies (from other packages) — mocked
    @Mock InboundIntentService inboundIntentService;
    @Mock IntentSessionManager intentSessionManager;
    @Mock ScoringPipeline scoringPipeline;
    @Mock GuardrailDecisionEngine guardrailDecisionEngine;
    @Mock BlastRadiusGuard blastRadiusGuard;
    @Mock ThresholdConfigurationService thresholdConfigurationService;
    @Mock GuardrailConfigService guardrailConfigService;
    @Mock AssistAuditRepository auditRepository;

    @Test
    void allAssistComponentsCanBeInstantiated() {
        // --- Layer 1: Configuration (no intra-package dependencies) ---
        AssistProperties properties = new AssistProperties();
        assertThat(properties.getSessionTimeoutMs()).isEqualTo(300_000);
        assertThat(properties.getRateLimitPerMinute()).isEqualTo(10);
        assertThat(properties.getExecutionTimeoutMs()).isEqualTo(30_000);
        assertThat(properties.getBlocklist()).isNotEmpty();

        // --- Layer 2: Infrastructure services (depend only on properties/external) ---
        GenerationBlocklist blocklist = new GenerationBlocklist(properties);
        assertThat(blocklist).isNotNull();

        AssistRateLimiter rateLimiter = new AssistRateLimiter(properties);
        assertThat(rateLimiter).isNotNull();

        CommandExecutor commandExecutor = new CommandExecutor(properties);
        assertThat(commandExecutor).isNotNull();

        AssistSessionManager sessionManager = new AssistSessionManager(properties, intentSessionManager);
        assertThat(sessionManager).isNotNull();

        // --- Layer 3: LLM integration (depends on AssistTextGenerator) ---
        AssistTextGenerator stubTextGenerator = prompt -> "[]"; // minimal stub
        GeminiCommandGenerator commandGenerator = new GeminiCommandGenerator(stubTextGenerator);
        assertThat(commandGenerator).isNotNull();

        // --- Layer 4: Orchestrator (depends on all of the above) ---
        DefaultNlAssistService orchestrator = new DefaultNlAssistService(
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
        assertThat(orchestrator).isNotNull();

        // --- Layer 5: REST controller (depends on orchestrator) ---
        AssistController controller = new AssistController(orchestrator);
        assertThat(controller).isNotNull();
    }

    @Test
    void assistPropertiesDefaultsAreValid() {
        // Verify the configuration properties have sensible defaults that pass @Validated constraints
        AssistProperties properties = new AssistProperties();

        assertThat(properties.getSessionTimeoutMs()).isGreaterThan(0);
        assertThat(properties.getRateLimitPerMinute()).isGreaterThan(0);
        assertThat(properties.getExecutionTimeoutMs()).isGreaterThan(0);
        assertThat(properties.getBlocklist()).isNotNull();
        assertThat(properties.getBlocklist()).hasSize(4);
    }

    @Test
    void noNullPointerExceptionDuringWiring() {
        // Verify that constructors with Objects.requireNonNull don't throw when all deps provided
        AssistProperties properties = new AssistProperties();

        assertThatCode(() -> {
            GenerationBlocklist blocklist = new GenerationBlocklist(properties);
            AssistRateLimiter rateLimiter = new AssistRateLimiter(properties);
            CommandExecutor commandExecutor = new CommandExecutor(properties);
            AssistSessionManager sessionManager = new AssistSessionManager(properties, intentSessionManager);
            AssistTextGenerator stubTextGenerator = prompt -> "[]";
            GeminiCommandGenerator commandGenerator = new GeminiCommandGenerator(stubTextGenerator);

            new DefaultNlAssistService(
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
        }).doesNotThrowAnyException();
    }

    @Test
    void enableConfigurationPropertiesIsPresent() {
        // Verify that AssistConfig has @EnableConfigurationProperties(AssistProperties.class)
        // by checking the annotation exists on the class
        var annotations = AssistConfig.class.getAnnotations();
        boolean hasEnableConfigProps = false;
        for (var annotation : annotations) {
            if (annotation.annotationType().getSimpleName().equals("EnableConfigurationProperties")) {
                hasEnableConfigProps = true;
                break;
            }
        }
        assertThat(hasEnableConfigProps)
                .as("AssistConfig must have @EnableConfigurationProperties(AssistProperties.class)")
                .isTrue();
    }
}
