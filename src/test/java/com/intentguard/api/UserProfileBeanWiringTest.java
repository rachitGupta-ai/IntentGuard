package com.intentguard.api;

import java.lang.reflect.Constructor;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.mock;

import com.intentguard.assist.AssistAuditRepository;
import com.intentguard.persistence.AuditHistoryRepository;
import com.intentguard.persistence.BehavioralProfileRepository;
import com.intentguard.persistence.IntentSessionRepository;
import com.intentguard.translation.TranslationRecordRepository;
import com.mongodb.client.MongoDatabase;

/**
 * Verifies the bean wiring and read-only isolation invariants for the User Profiling Screen
 * backend components (Req 9.4, 11.1).
 *
 * <p>This test does <em>not</em> load a full Spring context (no MongoDB, no LLM, no socket server
 * required), keeping it fast and hermetic. Instead it exercises the invariants directly:
 *
 * <ol>
 *   <li><b>Controller wires correctly</b> — {@link UserProfileController} can be instantiated
 *       with a mock {@link UserProfileService}; its {@code listUsers()} and {@code profile()}
 *       methods dispatch to that service without throwing (Req 11.1).</li>
 *   <li><b>Service constructor references only read-only repository collaborators</b> — the
 *       constructor parameter types of {@link DefaultUserProfileService} are inspected via
 *       reflection and asserted to come exclusively from the repository packages, never from
 *       {@code scoring}, {@code decision}, {@code translation}, {@code assist} (service layer),
 *       or {@code execution} packages (Req 9.4).</li>
 *   <li><b>Service instance assembles correctly with no-op repositories</b> — a
 *       {@link DefaultUserProfileService} built from in-memory no-op repositories delegates
 *       successfully to {@code listKnownUsers()} and {@code assemble()} without invoking any
 *       forbidden collaborator (Req 9.4, 9.3).</li>
 * </ol>
 *
 * <p>Package-private. AssertJ assertions. No Lombok.
 */
class UserProfileBeanWiringTest {

    /**
     * Simple class-name fragments that must NEVER appear as a constructor parameter type in
     * {@link DefaultUserProfileService}. These represent the scoring, decision, translation
     * <em>service</em>, and execution paths that the profile service is prohibited from invoking
     * (Req 9.4).
     *
     * <p>Note: {@code TranslationRecordRepository} lives in {@code com.intentguard.translation}
     * but is a repository collaborator that the profiling service legitimately needs. The
     * forbidden list therefore uses <em>class-name</em> fragments (not package fragments) to
     * target service-layer types only, so the repository is not falsely flagged.
     */
    private static final List<String> FORBIDDEN_CLASS_NAME_FRAGMENTS = List.of(
            "ScoringPipeline",   // scoring service (DefaultScoringPipeline, ScoringPipeline)
            "ScoringComponent",  // individual scoring components
            "DecisionEngine",    // decision service (GuardrailDecisionEngine, DefaultDecisionEngine)
            "TamperClassifier",  // decision sub-component
            "BlastRadius",       // decision guardrail
            "TranslationService",    // translation service layer
            "TranslationProvider",   // translation provider implementations
            "TranslationCache",      // translation caching layer
            "NlAssistService",       // NL assistant service layer
            "CommandGenerator",      // LLM command generation
            "CommandExecutor",       // shell execution component
            "AssistSessionManager",  // assist session lifecycle
            "AssistRateLimiter"      // assist rate limiter
    );

    // -----------------------------------------------------------------------
    // 1. Controller can be wired and dispatches to the service (Req 11.1)
    // -----------------------------------------------------------------------

    /**
     * {@link UserProfileController} is constructible with a mock service and its two handler
     * methods invoke the service without throwing (Req 11.1). This mirrors the bean wiring that
     * Spring performs at startup without requiring a Spring context.
     */
    @Test
    void controllerIsWirableAndDispatchesToService() {
        UserProfileService serviceStub = mock(UserProfileService.class);

        // Mock returns for the two entry points.
        org.mockito.Mockito.when(serviceStub.listKnownUsers())
                .thenReturn(KnownUsersView.from(List.of("alice")));
        ActiveWindow window = ActiveWindow.of(
                System.currentTimeMillis() - 3 * 86_400_000L,
                System.currentTimeMillis());
        org.mockito.Mockito.when(serviceStub.resolveWindow("alice", 3, false))
                .thenReturn(window);
        org.mockito.Mockito.when(serviceStub.assemble(
                        org.mockito.ArgumentMatchers.eq("alice"),
                        org.mockito.ArgumentMatchers.any(ActiveWindow.class),
                        org.mockito.ArgumentMatchers.anyBoolean()))
                .thenReturn(new UserProfileView(
                        "alice",
                        window.start(), window.end(),
                        false, false, false,
                        CategoryView.of(List.of(), false, 0),
                        CategoryView.of(List.of(), false, 0),
                        CategoryView.of(List.of(), false, 0),
                        CategoryView.of(List.of(), false, 0),
                        BehavioralProfileView.absent(),
                        RiskStats.absent(List.of())));

        UserProfileController controller = new UserProfileController(serviceStub);

        // Verify listUsers() dispatches to service.listKnownUsers().
        KnownUsersView users = controller.listUsers();
        assertThat(users).isNotNull();
        assertThat(users.users()).containsExactly("alice");

        // Verify profile() dispatches to service.resolveWindow() then service.assemble().
        UserProfileView view = controller.profile("alice", 3, false);
        assertThat(view).isNotNull();
        assertThat(view.userId()).isEqualTo("alice");

        // Confirm the service was actually called (not bypassed by the controller).
        org.mockito.Mockito.verify(serviceStub).listKnownUsers();
        org.mockito.Mockito.verify(serviceStub).resolveWindow("alice", 3, false);
        org.mockito.Mockito.verify(serviceStub).assemble(
                org.mockito.ArgumentMatchers.eq("alice"),
                org.mockito.ArgumentMatchers.any(ActiveWindow.class),
                org.mockito.ArgumentMatchers.eq(false));
    }

    // -----------------------------------------------------------------------
    // 2. DefaultUserProfileService constructor uses only repository collaborators (Req 9.4)
    // -----------------------------------------------------------------------

    /**
     * Inspects the constructor parameter types of {@link DefaultUserProfileService} via
     * reflection and asserts that none are scoring, decision, translation <em>service</em>,
     * or execution/assist types.
     *
     * <p>This is a structural guard: if a future change accidentally injects a forbidden
     * collaborator through the constructor, this test will fail, catching the violation before
     * runtime (Req 9.4).
     */
    @Test
    void defaultUserProfileServiceConstructorHasNoForbiddenCollaborators() {
        Constructor<?>[] constructors = DefaultUserProfileService.class.getDeclaredConstructors();

        // Collect all parameter simple class names across all constructors.
        List<String> parameterClassNames = new ArrayList<>();
        for (Constructor<?> ctor : constructors) {
            for (Parameter param : ctor.getParameters()) {
                Class<?> type = param.getType();
                parameterClassNames.add(type.getSimpleName());
            }
        }

        // Assert that no parameter type's simple name contains a forbidden class-name fragment.
        for (String forbidden : FORBIDDEN_CLASS_NAME_FRAGMENTS) {
            for (String simpleName : parameterClassNames) {
                assertThat(simpleName)
                        .as("DefaultUserProfileService must not depend on '%s' type (Req 9.4). "
                                + "Found parameter with simple name: %s", forbidden, simpleName)
                        .doesNotContain(forbidden);
            }
        }
    }

    /**
     * Supplements the static constructor check: confirms that only repository types are present as
     * constructor parameters. Each parameter must be a subtype of one of the five known repository
     * types.
     *
     * <p>The five repositories are: {@link AuditHistoryRepository},
     * {@link IntentSessionRepository}, {@link BehavioralProfileRepository},
     * {@link AssistAuditRepository}, {@link TranslationRecordRepository}.
     */
    @Test
    void defaultUserProfileServiceConstructorAcceptsOnlyRepositoryTypes() {
        // Find the principal (widest) constructor — the one with the most parameters.
        Constructor<?>[] constructors = DefaultUserProfileService.class.getDeclaredConstructors();
        Constructor<?> principal = constructors[0];
        for (Constructor<?> ctor : constructors) {
            if (ctor.getParameterCount() > principal.getParameterCount()) {
                principal = ctor;
            }
        }

        // Every parameter type must be one of the five repository types.
        for (Parameter param : principal.getParameters()) {
            Class<?> type = param.getType();
            boolean isRepository =
                    AuditHistoryRepository.class.isAssignableFrom(type) ||
                    IntentSessionRepository.class.isAssignableFrom(type) ||
                    BehavioralProfileRepository.class.isAssignableFrom(type) ||
                    AssistAuditRepository.class.isAssignableFrom(type) ||
                    TranslationRecordRepository.class.isAssignableFrom(type);

            assertThat(isRepository)
                    .as("Constructor parameter '%s' of type '%s' is not a known repository type. "
                                    + "DefaultUserProfileService must only depend on repositories (Req 9.4).",
                            param.getName(), type.getName())
                    .isTrue();
        }
    }

    // -----------------------------------------------------------------------
    // 3. Service assembles with no-op repositories without calling forbidden paths (Req 9.3, 9.4)
    // -----------------------------------------------------------------------

    /**
     * A {@link DefaultUserProfileService} wired with no-op repository stubs completes
     * {@code listKnownUsers()} and {@code assemble()} successfully. Because the no-op stubs
     * are the only collaborators and they perform no writes, this verifies the read-only contract
     * without needing Mockito verify-no-interactions on external components (Req 9.3, 9.4).
     */
    @Test
    void serviceAssemblesWithNoOpRepositoriesWithoutError() {
        MongoDatabase db = mock(MongoDatabase.class);

        AuditHistoryRepository auditRepo = new NoOpAuditHistoryRepository(db);
        IntentSessionRepository sessionRepo = new NoOpIntentSessionRepository(db);
        BehavioralProfileRepository profileRepo = new NoOpBehavioralProfileRepository(db);
        AssistAuditRepository assistRepo = new NoOpAssistAuditRepository(db);
        TranslationRecordRepository translationRepo = new NoOpTranslationRecordRepository(db);

        DefaultUserProfileService service = new DefaultUserProfileService(
                auditRepo, sessionRepo, profileRepo, assistRepo, translationRepo);

        // listKnownUsers must not throw and must return a valid view.
        KnownUsersView users = service.listKnownUsers();
        assertThat(users).isNotNull();
        assertThat(users.users()).isEmpty();

        // resolveWindow must return a concrete window for a valid day count.
        ActiveWindow window = service.resolveWindow("alice", 7, false);
        assertThat(window).isNotNull();
        assertThat(window.empty()).isFalse();

        // assemble must not throw and must return a non-null view.
        UserProfileView view = service.assemble("alice", window, false);
        assertThat(view).isNotNull();
        assertThat(view.userId()).isEqualTo("alice");
        // With no-op repos every category is OK and empty; profileLoadFailed must be false.
        assertThat(view.profileLoadFailed()).isFalse();
        assertThat(view.commandTimeline().status()).isEqualTo(CategoryStatus.OK);
        assertThat(view.multilingual().status()).isEqualTo(CategoryStatus.OK);
        assertThat(view.assistQueries().status()).isEqualTo(CategoryStatus.OK);
        assertThat(view.translations().status()).isEqualTo(CategoryStatus.OK);

        service.shutdown();
    }

    // -----------------------------------------------------------------------
    // No-op repository helpers (package-private inner classes)
    // -----------------------------------------------------------------------

    static final class NoOpAuditHistoryRepository extends AuditHistoryRepository {
        NoOpAuditHistoryRepository(MongoDatabase db) { super(db); }

        @Override
        public java.util.List<com.intentguard.persistence.AuditHistoryDocument>
                queryByUserAndTimeRange(String userId, long from, long to) { return List.of(); }

        @Override
        public java.util.List<String> distinctUserIds() { return List.of(); }

        @Override
        public java.util.Optional<Long> earliestTimestampForUser(String userId) {
            return java.util.Optional.empty();
        }
    }

    static final class NoOpIntentSessionRepository extends IntentSessionRepository {
        NoOpIntentSessionRepository(MongoDatabase db) { super(db); }

        @Override
        public java.util.List<com.intentguard.persistence.IntentSessionDocument>
                findByUserIdAndTimeRange(String userId, long from, long to) { return List.of(); }

        @Override
        public java.util.List<String> distinctUserIds() { return List.of(); }

        @Override
        public java.util.Optional<Long> earliestStartedAtForUser(String userId) {
            return java.util.Optional.empty();
        }
    }

    static final class NoOpBehavioralProfileRepository extends BehavioralProfileRepository {
        NoOpBehavioralProfileRepository(MongoDatabase db) { super(db); }

        @Override
        public java.util.Optional<com.intentguard.persistence.BehavioralProfileDocument>
                findByUserId(String userId) { return java.util.Optional.empty(); }

        @Override
        public java.util.List<String> distinctUserIds() { return List.of(); }
    }

    static final class NoOpAssistAuditRepository extends AssistAuditRepository {
        NoOpAssistAuditRepository(MongoDatabase db) { super(db); }

        @Override
        public java.util.List<com.intentguard.assist.AssistAuditDocument>
                findQueriesByOperatorAndTimeRange(String operatorId, long from, long to) {
            return List.of();
        }

        @Override
        public java.util.List<String> distinctOperatorIds() { return List.of(); }

        @Override
        public java.util.Optional<Long> earliestQueryTimestampForOperator(String operatorId) {
            return java.util.Optional.empty();
        }
    }

    static final class NoOpTranslationRecordRepository extends TranslationRecordRepository {
        NoOpTranslationRecordRepository(MongoDatabase db) { super(db); }

        @Override
        public java.util.List<com.intentguard.translation.TranslationRecord>
                findByTimeRange(long from, long to) { return List.of(); }
    }
}
