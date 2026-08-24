package com.intentguard.scoring;

import java.util.Map;

import com.intentguard.domain.Actor;
import com.intentguard.domain.AgentRiskMarkers;
import com.intentguard.domain.CommandEvent;
import com.intentguard.domain.ComponentId;
import com.intentguard.domain.InputOrigin;
import com.intentguard.domain.IntentSource;
import com.intentguard.domain.ProfileState;
import com.intentguard.domain.ScoringConfig;
import com.intentguard.domain.ScoringContext;
import com.intentguard.domain.SignalSource;

/**
 * Shared builders for the deterministic-component unit tests: a default {@link ScoringConfig} whose
 * component weights match the design's example, plus helpers to build {@link CommandEvent}s and
 * {@link ScoringContext}s with only the fields a test cares about.
 */
final class ScoringTestSupport {

    static final ScoringConfig DEFAULT_CONFIG = new ScoringConfig(
            Map.of(
                    ComponentId.SEQUENCE_SURPRISE, 0.25,
                    ComponentId.CONTEXT_MISMATCH, 0.20,
                    ComponentId.BEHAVIORAL_DEVIATION, 0.25,
                    ComponentId.SEMANTIC_INCONSISTENCY, 0.30),
            0.15);

    private ScoringTestSupport() {
    }

    /** A command event with the given command text, cwd/repo, and input origin. */
    static CommandEvent event(String commandText, String cwd, String repo, InputOrigin origin) {
        return new CommandEvent(
                "evt-" + Integer.toHexString(commandText.hashCode()),
                Actor.human("alice"),
                null,
                commandText,
                cwd,
                repo,
                Map.of(),
                1_710_000_000_000L,
                origin,
                SignalSource.HOOK,
                IntentSource.NONE,
                AgentRiskMarkers.none());
    }

    /** A typed command event in a plain home directory (no repo). */
    static CommandEvent typed(String commandText) {
        return event(commandText, "/home/alice", null, InputOrigin.TYPED);
    }

    static ScoringContext context(CommandEvent event) {
        return new ScoringContext(event, null, IntentSource.NONE, ProfileState.ACTIVE, DEFAULT_CONFIG);
    }
}
