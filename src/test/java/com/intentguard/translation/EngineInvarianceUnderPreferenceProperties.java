// Feature: indian-language-translation, Property 9: Engine analysis is invariant under Language_Preference
package com.intentguard.translation;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.intentguard.config.ThresholdConfiguration;
import com.intentguard.decision.DefaultDecisionEngine;
import com.intentguard.decision.TamperClassifier;
import com.intentguard.domain.Actor;
import com.intentguard.domain.AgentRiskMarkers;
import com.intentguard.domain.CommandEvent;
import com.intentguard.domain.ComponentId;
import com.intentguard.domain.Decision;
import com.intentguard.domain.DivergenceResult;
import com.intentguard.domain.InputOrigin;
import com.intentguard.domain.IntentSource;
import com.intentguard.domain.ProfileState;
import com.intentguard.domain.ScoringConfig;
import com.intentguard.domain.ScoringContext;
import com.intentguard.domain.SignalSource;
import com.intentguard.intent.DefaultIntentSessionManager;
import com.intentguard.intent.IntentSession;
import com.intentguard.intent.IntentSessionManager;
import com.intentguard.llm.LlmService;
import com.intentguard.persistence.AuditHistoryDocument;
import com.intentguard.persistence.AuditHistoryRepository;
import com.intentguard.persistence.IntentSessionDocument;
import com.intentguard.persistence.IntentSessionRepository;
import com.intentguard.scoring.BehavioralDeviationComponent;
import com.intentguard.scoring.ContextMismatchComponent;
import com.intentguard.scoring.DefaultScoringPipeline;
import com.intentguard.scoring.DivergenceComponent;
import com.intentguard.scoring.ProfileSnapshotProvider;
import com.intentguard.scoring.ScoringPipeline;
import com.intentguard.scoring.SemanticInconsistencyComponent;
import com.intentguard.scoring.SequenceSurpriseComponent;
import com.mongodb.client.MongoDatabase;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

/**
 * Feature: indian-language-translation, Property 9: Engine analysis is invariant under
 * Language_Preference.
 *
 * <p>For any Command_Event and any Operator Language_Preference, the command text the
 * Enforcement_Engine scores and the values it persists to Audit_History are the original
 * Engine_Language (English) values, identical across all preferences and independent of any
 * Translation_Record (Req 7.2, 7.3).
 *
 * <h2>What this exercises</h2>
 * <p>The engine reads the intent it scores from {@link IntentSession#declaredIntent()} — the
 * Engine_Language (English) text — exactly as {@code PipelineDecisionProvider} does
 * ({@code session.map(IntentSession::declaredIntent)}), and audits {@link CommandEvent#commandText()}
 * (English). Neither the {@link IntentSession#originalDeclaredIntent()} Source_Text, the
 * {@link IntentSession#declaredIntentLanguageTag()}, nor the Operator's Language_Preference (held in
 * {@link LanguagePreferenceService}) is ever consulted by scoring or audit.
 *
 * <p>The test holds the English {@code declaredIntent} and the {@link CommandEvent} fixed while
 * varying, across a set of operators, all three preference-carrying dimensions:
 * <ul>
 *   <li>the operator's saved Language_Preference (via {@link LanguagePreferenceService});</li>
 *   <li>the session's {@code originalDeclaredIntent} (the untranslated Source_Text); and</li>
 *   <li>the session's {@code declaredIntentLanguageTag}.</li>
 * </ul>
 * It then opens a real {@link IntentSession} for each operator through
 * {@link DefaultIntentSessionManager}, runs the real {@link ScoringPipeline} and
 * {@link DefaultDecisionEngine} over the intent text the engine would resolve, and builds the
 * Engine_Language audit values the pipeline would persist. It asserts every one of these is
 * byte-for-byte the fixed English baseline and identical across all preference variations.
 *
 * <p>A deterministic {@link LlmService} stub scores Semantic_Inconsistency from a hash of the
 * English command text and English intent text only, so a preference-dependent divergence could
 * only arise if scoring read the preference — which it must not.
 *
 * <p>Validates: Requirements 7.2, 7.3.
 */
class EngineInvarianceUnderPreferenceProperties {

    /** The Engine_Language: sessions are always opened and scored on English text (Req 7.2). */
    private static final LanguageTag ENGLISH = SupportedLanguages.ENGLISH;

    private static final double ASK_THRESHOLD = 0.4;
    private static final double BLOCK_THRESHOLD = 0.7;

    private final SupportedLanguages supportedLanguages = SupportedLanguages.defaults();

    // ---- the property ----------------------------------------------------------------------------

    @Property(tries = 200)
    void engineScoringAndAuditAreInvariantUnderLanguagePreference(
            @ForAll("englishIntents") String englishIntent,
            @ForAll("commands") Command command,
            @ForAll("preferenceVariations") List<Variation> variations) {

        // Fixed Engine_Language (English) Command_Event scored by the engine, identical for every
        // preference variation below.
        CommandEvent event = commandEvent(command);

        ThresholdConfiguration cfg = config();
        ScoringConfig scoringConfig = cfg.toScoringConfig();
        ScoringPipeline pipeline = newPipeline();
        DefaultDecisionEngine decisionEngine = new DefaultDecisionEngine(new TamperClassifier());

        EngineAnalysis baseline = null;
        for (Variation variation : variations) {
            String operatorId = variation.operatorId();

            // Fresh service per operator so preferences do not leak between variations; set the
            // operator's Language_Preference to the variation's (possibly non-English) tag.
            LanguagePreferenceService preferences = newPreferenceService();
            LanguagePreferenceUpdate update = preferences.setPreference(operatorId, variation.preference());
            assertThat(update.accepted()).isTrue();
            assertThat(preferences.getPreference(operatorId)).isEqualTo(variation.preference());

            // Open a real Intent_Session recording the SAME English declaredIntent but a DIFFERENT
            // original Source_Text and language tag per variation (Req 3.2, 10.4).
            IntentSessionManager sessions = newSessionManager();
            IntentSession session = sessions.open(
                    operatorId,
                    englishIntent,
                    variation.originalDeclaredIntent(),
                    variation.preference().value(),
                    Actor.human(operatorId));

            // Resolve exactly what the engine scores: session.declaredIntent() (English), NOT the
            // original Source_Text and NOT the Language_Preference (Req 7.2).
            String scoredIntentText = session.declaredIntent();
            IntentSource intentSource = session.intentSource();

            ScoringContext ctx = new ScoringContext(
                    event, scoredIntentText, intentSource, ProfileState.ACTIVE, scoringConfig);
            DivergenceResult result = pipeline.score(ctx);
            Decision decision = decisionEngine.decide(event, result, cfg, ProfileState.ACTIVE, true);

            // The Engine_Language values the pipeline persists to Audit_History (Req 7.3): the
            // original English command text and the composite/decision score.
            AuditHistoryDocument audit = auditValues(event, decision);

            EngineAnalysis analysis = new EngineAnalysis(
                    scoredIntentText,
                    result.composite(),
                    decision.score(),
                    decision.action().name(),
                    decision.reasonCode(),
                    audit.getCommandText(),
                    audit.getDivergenceScore());

            // Whatever the preference, the scored intent text is the fixed English declaredIntent
            // and the audited command text is the fixed English command text (Req 7.2, 7.3).
            assertThat(scoredIntentText).isEqualTo(englishIntent);
            assertThat(session.declaredIntentLanguageTag()).isEqualTo(variation.preference().value());
            assertThat(audit.getCommandText()).isEqualTo(command.text());

            if (baseline == null) {
                baseline = analysis;
            } else {
                // Identical across all preferences: the engine analysis does not vary with the
                // Operator's Language_Preference, the original Source_Text, or the language tag.
                assertThat(analysis).isEqualTo(baseline);
            }
        }
    }

    // ---- worked example: three operators, three preferences, one English intent ------------------

    @Example
    void hindiBengaliTamilPreferencesYieldIdenticalEnglishAnalysis() {
        String englishIntent = "clean up stale build artifacts in the workspace";
        Command command = new Command("rm -rf /tmp/build-cache", "/home/op", null, InputOrigin.TYPED);
        CommandEvent event = commandEvent(command);

        ThresholdConfiguration cfg = config();
        ScoringPipeline pipeline = newPipeline();
        DefaultDecisionEngine decisionEngine = new DefaultDecisionEngine(new TamperClassifier());

        List<Variation> variations = List.of(
                new Variation("op-hi", LanguageTag.of("hi"), "कार्यक्षेत्र में पुराने बिल्ड आर्टिफैक्ट साफ़ करें"),
                new Variation("op-bn", LanguageTag.of("bn"), "ওয়ার্কস্পেসে পুরনো বিল্ড আর্টিফ্যাক্ট পরিষ্কার করুন"),
                new Variation("op-ta", LanguageTag.of("ta"), "பணியிடத்தில் பழைய பில்ட் கோப்புகளை அழிக்கவும்"),
                new Variation("op-en", ENGLISH, null));

        EngineAnalysis baseline = null;
        for (Variation variation : variations) {
            LanguagePreferenceService preferences = newPreferenceService();
            preferences.setPreference(variation.operatorId(), variation.preference());

            IntentSessionManager sessions = newSessionManager();
            IntentSession session = sessions.open(
                    variation.operatorId(),
                    englishIntent,
                    variation.originalDeclaredIntent(),
                    variation.preference().value(),
                    Actor.human(variation.operatorId()));

            ScoringContext ctx = new ScoringContext(
                    event, session.declaredIntent(), session.intentSource(),
                    ProfileState.ACTIVE, cfg.toScoringConfig());
            DivergenceResult result = pipeline.score(ctx);
            Decision decision = decisionEngine.decide(event, result, cfg, ProfileState.ACTIVE, true);
            AuditHistoryDocument audit = auditValues(event, decision);

            assertThat(session.declaredIntent()).isEqualTo(englishIntent);
            assertThat(audit.getCommandText()).isEqualTo(command.text());

            EngineAnalysis analysis = new EngineAnalysis(
                    session.declaredIntent(), result.composite(), decision.score(),
                    decision.action().name(), decision.reasonCode(),
                    audit.getCommandText(), audit.getDivergenceScore());
            if (baseline == null) {
                baseline = analysis;
            } else {
                assertThat(analysis).isEqualTo(baseline);
            }
        }
    }

    // ---- engine wiring (mirrors PipelineDecisionProvider's scoring/decision seam) -----------------

    /**
     * Builds the real scoring pipeline with the deterministic components plus a Semantic component
     * driven by a deterministic English-only LLM stub, so a preference-dependent score is impossible
     * unless the code under test reads the preference.
     */
    private ScoringPipeline newPipeline() {
        ProfileSnapshotProvider profiles = ProfileSnapshotProvider.empty();
        List<DivergenceComponent> components = new ArrayList<>();
        components.add(new SequenceSurpriseComponent(profiles));
        components.add(new ContextMismatchComponent(profiles));
        components.add(new BehavioralDeviationComponent(profiles));
        components.add(new SemanticInconsistencyComponent(new DeterministicEnglishLlm()));
        return new DefaultScoringPipeline(components);
    }

    private LanguagePreferenceService newPreferenceService() {
        return new LanguagePreferenceService(new InMemoryLanguagePreferenceRepository(), supportedLanguages);
    }

    private IntentSessionManager newSessionManager() {
        return new DefaultIntentSessionManager(
                new InMemoryIntentSessionRepository(), new InMemoryAuditHistoryRepository());
    }

    /** The Engine_Language audit values the pipeline persists (Req 7.3): command text + score. */
    private static AuditHistoryDocument auditValues(CommandEvent event, Decision decision) {
        AuditHistoryDocument record = new AuditHistoryDocument();
        record.setCommandText(event.commandText());
        record.setDivergenceScore(decision.score());
        return record;
    }

    private static CommandEvent commandEvent(Command command) {
        return new CommandEvent(
                "evt-fixed",
                Actor.human("engine-user"),
                "session-fixed",
                command.text(),
                command.cwd(),
                command.repo(),
                Map.of(),
                1_710_000_000_000L,
                command.origin(),
                SignalSource.HOOK,
                IntentSource.DECLARED,
                AgentRiskMarkers.none());
    }

    private static ThresholdConfiguration config() {
        return new ThresholdConfiguration(
                1,
                ASK_THRESHOLD,
                BLOCK_THRESHOLD,
                Map.of(
                        ComponentId.SEQUENCE_SURPRISE, 0.25,
                        ComponentId.CONTEXT_MISMATCH, 0.20,
                        ComponentId.BEHAVIORAL_DEVIATION, 0.25,
                        ComponentId.SEMANTIC_INCONSISTENCY, 0.30),
                0.15,
                200,
                5_000L,
                15_000L,
                1_200L,
                1_000L,
                "admin",
                1_000L);
    }

    // ---- deterministic English-only LLM stub -----------------------------------------------------

    /**
     * Deterministic {@link LlmService}: the Semantic_Inconsistency score is a stable function of the
     * English command text and English intent text only. It never observes any Language_Preference,
     * so any preference-dependent result would have to come from the code under test.
     */
    private static final class DeterministicEnglishLlm implements LlmService {
        @Override
        public OptionalDouble semanticInconsistency(CommandEvent event, String intentText) {
            int h = (event.commandText() + "\u0000" + intentText).hashCode();
            double score = (Math.floorMod(h, 1000)) / 1000.0; // stable value in [0,1)
            return OptionalDouble.of(score);
        }

        @Override
        public Optional<String> explain(CommandEvent event, DivergenceResult result, Decision decision) {
            return Optional.empty();
        }
    }

    // ---- in-memory repositories (DB-free, mirroring InMemoryLanguagePreferenceRepository) ---------

    /** DB-free {@link IntentSessionRepository} backed by maps; no live Mongo is touched. */
    private static final class InMemoryIntentSessionRepository extends IntentSessionRepository {
        private final Map<String, IntentSessionDocument> bySessionId = new HashMap<>();

        InMemoryIntentSessionRepository() {
            super(mock(MongoDatabase.class));
        }

        @Override
        public void save(IntentSessionDocument session) {
            bySessionId.put(session.getSessionId(), session);
        }

        @Override
        public Optional<IntentSessionDocument> findBySessionId(String sessionId) {
            return Optional.ofNullable(bySessionId.get(sessionId));
        }

        @Override
        public Optional<IntentSessionDocument> findOpenByUserId(String userId) {
            return bySessionId.values().stream()
                    .filter(doc -> userId.equals(doc.getUserId()) && doc.isOpen())
                    .findFirst();
        }
    }

    /** DB-free {@link AuditHistoryRepository}; the session manager only calls {@code save}. */
    private static final class InMemoryAuditHistoryRepository extends AuditHistoryRepository {
        private final List<AuditHistoryDocument> records = new ArrayList<>();

        InMemoryAuditHistoryRepository() {
            super(mock(MongoDatabase.class));
        }

        @Override
        public void save(AuditHistoryDocument record) {
            records.add(record);
        }
    }

    // ---- generators ------------------------------------------------------------------------------

    @Provide
    Arbitrary<String> englishIntents() {
        return Arbitraries.of(
                "deploy the payments service to staging",
                "clean up stale build artifacts in the workspace",
                "rotate the database credentials for the api tier",
                "investigate the failing integration tests",
                "back up the customer records before the migration");
    }

    @Provide
    Arbitrary<Command> commands() {
        Arbitrary<String> text = Arbitraries.of(
                "git commit -m 'wip'",
                "kubectl apply -f deploy/prod.yaml",
                "rm -rf /tmp/build-cache",
                "curl https://example.com/health",
                "ls -la /var/log",
                "npm install --save left-pad@1.3.0");
        Arbitrary<String> cwd = Arbitraries.of("/home/op", "/tmp", "/var/log", "/opt/app");
        Arbitrary<String> repo = Arbitraries.of("acme/api", (String) null);
        Arbitrary<InputOrigin> origin = Arbitraries.of(InputOrigin.TYPED, InputOrigin.PASTED, InputOrigin.UNKNOWN);
        return Combinators.combine(text, cwd, repo, origin).as(Command::new);
    }

    /**
     * A non-empty set of preference variations over distinct operators. Each variation pairs a
     * Supported_Language preference (including English) with an untranslated Source_Text (null for
     * English) so the property compares the engine analysis across genuinely different preferences.
     */
    @Provide
    Arbitrary<List<Variation>> preferenceVariations() {
        Arbitrary<LanguageTag> tags =
                Arbitraries.of("en", "hi", "bn", "te", "mr", "ta", "gu", "kn", "ml", "pa", "or")
                        .map(LanguageTag::of);
        Arbitrary<String> originals = Arbitraries.of(
                "पुराने आर्टिफैक्ट साफ़ करें",
                "সার্ভিস স্থাপন করুন",
                "సేవను అమలు చేయండి",
                "சேவையை நிறுவவும்",
                "ಸೇವೆಯನ್ನು ನಿಯೋಜಿಸಿ",
                null);
        Arbitrary<Variation> variation = Combinators.combine(
                        Arbitraries.strings().alpha().numeric().ofMinLength(1).ofMaxLength(12),
                        tags,
                        originals)
                .as((op, tag, original) -> new Variation(
                        op, tag, tag.equals(ENGLISH) ? null : original));
        return variation.list().ofMinSize(2).ofMaxSize(6);
    }

    // ---- value types -----------------------------------------------------------------------------

    /** A generated Engine_Language Command_Event's raw fields. */
    record Command(String text, String cwd, String repo, InputOrigin origin) {}

    /** One preference variation: a distinct operator, their preference, and an original Source_Text. */
    record Variation(String operatorId, LanguageTag preference, String originalDeclaredIntent) {}

    /** The engine's analysis outputs asserted invariant across preference variations. */
    record EngineAnalysis(
            String scoredIntentText,
            double composite,
            double decisionScore,
            String action,
            String reasonCode,
            String auditCommandText,
            double auditDivergenceScore) {}
}
