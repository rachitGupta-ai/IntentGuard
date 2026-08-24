package com.intentguard.decision;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

import com.intentguard.config.ThresholdConfiguration;
import com.intentguard.domain.Actor;
import com.intentguard.domain.ActorType;
import com.intentguard.domain.AgentRiskMarkers;
import com.intentguard.domain.CommandEvent;
import com.intentguard.domain.ComponentId;
import com.intentguard.domain.CorrectiveAction;
import com.intentguard.domain.Decision;
import com.intentguard.domain.DivergenceResult;
import com.intentguard.domain.InputOrigin;
import com.intentguard.domain.IntentSource;
import com.intentguard.domain.ProfileState;
import com.intentguard.domain.SignalSource;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.DoubleRange;

/**
 * Feature: intentguard-semantic-firewall, Property 4: Tamper attempts are forced to block.
 *
 * <p>For any Command_Event classified as targeting IntentGuard configuration, process state, or the
 * Datastore, the assigned Divergence_Score is the maximum (1.0) and the resulting Corrective_Action
 * is block, regardless of the other component scores (Validates: Requirements 1.6, 13.3).
 *
 * <p>The generator builds a genuine tamper Command_Event by embedding one of the classifier's known
 * tamper fragments (the IntentGuard service name, its config/install locations, its IPC socket, or
 * the Datastore collection names) into either the command text or the working directory, surrounded
 * by arbitrary text. This guarantees {@link TamperClassifier#isTamperAttempt} fires. It then draws
 * an <em>arbitrary</em> composite {@link DivergenceResult} across the whole [0,1] range (including
 * low, allow-range scores that would otherwise be permitted), an arbitrary {@link ProfileState},
 * actor type, and human-session flag, and asserts the decision is <em>always</em> BLOCK with score
 * 1.0 and reason {@link DefaultDecisionEngine#REASON_TAMPER} — proving the tamper override
 * short-circuits every other rule regardless of the component scores.
 */
class TamperOverrideProperties {

    /**
     * The fragments {@link TamperClassifier} treats as references to IntentGuard's own control
     * surface. Drawing the injected fragment from this known set guarantees the classifier fires.
     */
    private static final List<String> TAMPER_FRAGMENTS =
            List.of(
                    "intentguard",
                    "/etc/intentguard",
                    "/var/lib/intentguard",
                    "/opt/intentguard",
                    "intentguard.sock",
                    "threshold_config",
                    "behavioral_profiles",
                    "intent_sessions",
                    "audit_history",
                    "scenario_baselines");

    private final DefaultDecisionEngine engine = new DefaultDecisionEngine(new TamperClassifier());

    @Property(tries = 200)
    void tamperAttemptsAreAlwaysForcedToBlockAtMaxScore(
            @ForAll("tamperEvents") CommandEvent event,
            // An arbitrary composite across the full range, including allow-range scores.
            @ForAll @DoubleRange(min = 0.0, max = 1.0) double composite,
            @ForAll("profileStates") ProfileState profileState,
            @ForAll boolean humanSessionOpen) {

        // Sanity: the generated event really is a tamper attempt the classifier recognizes.
        assertThat(new TamperClassifier().isTamperAttempt(event)).isTrue();

        DivergenceResult result = arbitraryResult(composite);

        Decision decision = engine.decide(event, result, config(), profileState, humanSessionOpen);

        // Regardless of the component composite, profile state, actor type, or session flag the
        // tamper override forces the maximum score and a block.
        assertThat(decision.action()).isEqualTo(CorrectiveAction.BLOCK);
        assertThat(decision.score()).isEqualTo(1.0);
        assertThat(decision.reasonCode()).isEqualTo(DefaultDecisionEngine.REASON_TAMPER);
    }

    /**
     * Generates a Command_Event guaranteed to be a tamper attempt: a known tamper fragment embedded
     * (with arbitrary surrounding text) in either the command text or the cwd, across arbitrary
     * actor types, input origins, and intent sources.
     */
    @Provide
    Arbitrary<CommandEvent> tamperEvents() {
        Arbitrary<String> fragments = Arbitraries.of(TAMPER_FRAGMENTS.toArray(new String[0]));
        // Arbitrary surrounding text: letters and spaces so the fragment is the only tamper signal.
        Arbitrary<String> surrounding =
                Arbitraries.strings().withChars("abcdefghijklmnopqrstuvwxyz /-.'\"").ofMaxLength(20);
        // Where to place the fragment: true -> command text, false -> cwd.
        Arbitrary<Boolean> inCommand = Arbitraries.of(true, false);
        Arbitrary<ActorType> actorTypes = Arbitraries.of(ActorType.HUMAN, ActorType.AGENT);
        Arbitrary<InputOrigin> origins =
                Arbitraries.of(InputOrigin.TYPED, InputOrigin.PASTED, InputOrigin.UNKNOWN);
        Arbitrary<IntentSource> intentSources =
                Arbitraries.of(IntentSource.NONE, IntentSource.DECLARED, IntentSource.INFERRED);

        return Combinators.combine(fragments, surrounding, surrounding, inCommand, actorTypes)
                .as((fragment, pre, post, placeInCommand, actorType) -> {
                    String tainted = pre + fragment + post;
                    String benign = "ls -la";
                    String commandText = placeInCommand ? tainted : benign;
                    String cwd = placeInCommand ? "/home/mallory" : tainted;
                    Actor actor =
                            actorType == ActorType.AGENT
                                    ? Actor.agent("mallory", "alice")
                                    : Actor.human("mallory");
                    return new CommandEvent(
                            "evt-tamper",
                            actor,
                            null,
                            commandText,
                            cwd,
                            null,
                            Map.of(),
                            1_000L,
                            InputOrigin.TYPED,
                            SignalSource.HOOK,
                            IntentSource.NONE,
                            AgentRiskMarkers.none());
                })
                // Layer arbitrary origin/intent over the base event to widen the input space.
                .flatMap(base -> Combinators.combine(origins, intentSources)
                        .as((origin, intentSource) -> new CommandEvent(
                                base.eventId(),
                                base.actor(),
                                base.sessionId(),
                                base.commandText(),
                                base.cwd(),
                                base.repo(),
                                base.envContext(),
                                base.timestamp(),
                                origin,
                                base.signalSource(),
                                intentSource,
                                base.agentRiskMarkers())));
    }

    @Provide
    Arbitrary<ProfileState> profileStates() {
        return Arbitraries.of(ProfileState.LEARNING, ProfileState.ACTIVE);
    }

    /** A DivergenceResult carrying the given composite; component breakdown is irrelevant here. */
    private static DivergenceResult arbitraryResult(double composite) {
        return new DivergenceResult(composite, List.of(), Set.of());
    }

    private static ThresholdConfiguration config() {
        return new ThresholdConfiguration(
                1,
                0.4,
                0.7,
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
}
