package com.intentguard.policy;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

import com.intentguard.domain.Actor;
import com.intentguard.domain.CommandEvent;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

/**
 * Feature: intentguard-guardrails, Property 6: A hot-reloaded CommandPolicy takes effect on
 * subsequent events.
 *
 * <p>For any sequence of valid CommandPolicy updates, after each accepted update the active version
 * is exactly one greater than the previous and {@code evaluate} applied to any subsequent
 * Command_Event reflects only the most recently applied policy, without a restart (Validates:
 * Requirements 2.2).
 *
 * <p>Each update swaps the single rule's action for the probe command between DENY and
 * REQUIRE_CONFIRM (and an empty policy that matches nothing), so the {@link PolicyDecision} for the
 * probe event changes to whatever the latest applied update prescribes — demonstrating hot-reload.
 */
class HotReloadPolicyProperties {

    private static final CommandEvent PROBE = new CommandEvent(
            "evt-probe", Actor.human("alice"), null, "rm -rf /tmp/data", "/srv/app", "repo-x",
            null, 1_000L, null, null, null, null);

    @Property(tries = 200)
    void eachAcceptedUpdateBumpsVersionByOneAndTakesEffectImmediately(
            @ForAll("actionSequences") List<PolicyAction> actions) {

        CommandPolicyService service = new CommandPolicyService(new InMemoryCommandPolicyRepository());
        service.loadActive();

        int previousVersion = 0;
        for (PolicyAction action : actions) {
            CommandPolicyUpdate update = new CommandPolicyUpdate(
                    List.of(new PolicyRule("probe", PatternKind.GLOB, "rm -rf *",
                            PolicyScope.any(), action)));

            CommandPolicy applied = service.applyUpdate(update, "admin");

            // Version is exactly one greater than the previous accepted version.
            assertThat(applied.version()).isEqualTo(previousVersion + 1);
            previousVersion = applied.version();

            // evaluate reflects ONLY the most recently applied policy, with no restart.
            PolicyDecision decision = service.evaluate(PROBE);
            assertThat(decision.action()).contains(action);
            assertThat(service.getActivePolicy().orElseThrow().version()).isEqualTo(applied.version());
        }
    }

    @Provide
    Arbitrary<List<PolicyAction>> actionSequences() {
        return Arbitraries.of(PolicyAction.values()).list().ofMinSize(1).ofMaxSize(6);
    }
}
