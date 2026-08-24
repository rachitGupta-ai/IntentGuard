package com.intentguard.ingest;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.junit.jupiter.api.Test;

import com.intentguard.domain.Actor;
import com.intentguard.domain.CommandEvent;
import com.intentguard.domain.InputOrigin;
import com.intentguard.domain.IntentSource;
import com.intentguard.domain.RawShellSignal;
import com.intentguard.domain.SignalSource;

/**
 * Unit tests for {@link ShellSignalNormalizer}: field-preserving normalization of a Shell_Hook
 * signal into a {@link CommandEvent}, with a missing typed-vs-pasted indicator recorded as
 * UNKNOWN while processing continues (Req 2.2, 2.4).
 */
class ShellSignalNormalizerTest {

    private final ShellSignalNormalizer normalizer =
            new ShellSignalNormalizer(() -> "fixed-event-id");

    private static RawShellSignal signal(Map<String, String> env, InputOrigin origin) {
        return new RawShellSignal(
                Actor.human("alice"), "git status", "/home/alice/project", env, 1_710_000_000_000L, origin);
    }

    @Test
    void preservesAllProvidedFields() {
        Map<String, String> env = Map.of("PATH", "/usr/bin", "HOME", "/home/alice");
        Actor actor = Actor.agent("agent-1", "alice");
        RawShellSignal raw =
                new RawShellSignal(actor, "kubectl delete pod x", "/srv/app", env, 42L, InputOrigin.TYPED);

        CommandEvent event = normalizer.normalize(raw);

        assertThat(event.actor()).isEqualTo(actor);
        assertThat(event.commandText()).isEqualTo("kubectl delete pod x");
        assertThat(event.cwd()).isEqualTo("/srv/app");
        assertThat(event.timestamp()).isEqualTo(42L);
        assertThat(event.inputOrigin()).isEqualTo(InputOrigin.TYPED);
        assertThat(event.envContext()).containsExactlyInAnyOrderEntriesOf(env);
    }

    @Test
    void missingInputOriginRecordedAsUnknownAndProcessingContinues() {
        RawShellSignal raw = signal(Map.of(), null);

        CommandEvent event = normalizer.normalize(raw);

        assertThat(event).isNotNull();
        assertThat(event.inputOrigin()).isEqualTo(InputOrigin.UNKNOWN);
        assertThat(event.commandText()).isEqualTo("git status");
    }

    @Test
    void envContextIsPreservedAndDefensivelyCopied() {
        Map<String, String> mutable = new HashMap<>();
        mutable.put("KEY", "value");
        RawShellSignal raw = signal(mutable, InputOrigin.PASTED);

        CommandEvent event = normalizer.normalize(raw);

        assertThat(event.envContext()).containsEntry("KEY", "value");
        // Mutating the original map after normalization must not affect the event.
        mutable.put("KEY", "tampered");
        mutable.put("NEW", "added");
        assertThat(event.envContext()).containsExactlyInAnyOrderEntriesOf(Map.of("KEY", "value"));
        // The event's env context must itself be immutable.
        assertThatThrownBy(() -> event.envContext().put("x", "y"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void generatesNonNullEventId() {
        CommandEvent event = normalizer.normalize(signal(Map.of(), InputOrigin.TYPED));

        assertThat(event.eventId()).isEqualTo("fixed-event-id");
    }

    @Test
    void defaultConstructorGeneratesUniqueNonNullEventIds() {
        ShellSignalNormalizer uuidNormalizer = new ShellSignalNormalizer();

        CommandEvent first = uuidNormalizer.normalize(signal(Map.of(), InputOrigin.TYPED));
        CommandEvent second = uuidNormalizer.normalize(signal(Map.of(), InputOrigin.TYPED));

        assertThat(first.eventId()).isNotNull().isNotEqualTo(second.eventId());
    }

    @Test
    void signalSourceIsHook() {
        CommandEvent event = normalizer.normalize(signal(Map.of(), InputOrigin.TYPED));

        assertThat(event.signalSource()).isEqualTo(SignalSource.HOOK);
    }

    @Test
    void deferredFieldsAreUnsetForLaterResolution() {
        CommandEvent event = normalizer.normalize(signal(Map.of(), InputOrigin.TYPED));

        assertThat(event.sessionId()).isNull();
        assertThat(event.repo()).isNull();
        assertThat(event.intentSource()).isEqualTo(IntentSource.NONE);
        assertThat(event.agentRiskMarkers().any()).isFalse();
    }
}
