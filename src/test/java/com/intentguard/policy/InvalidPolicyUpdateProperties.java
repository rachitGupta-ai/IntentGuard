package com.intentguard.policy;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

/**
 * Feature: intentguard-guardrails, Property 11: Invalid policy updates are rejected and the
 * last-known-good is retained.
 *
 * <p>For any active CommandPolicy and any invalid update — a rule with a missing pattern, a missing
 * action, an action outside {DENY, REQUIRE_CONFIRM, ALLOW}, or duplicate rule ids — {@code
 * applyUpdate} throws {@link InvalidCommandPolicyException}, persists nothing, and leaves the active
 * policy unchanged (Validates: Requirements 2.12, 2.13, 9.5).
 *
 * <p>Because {@link PolicyRule} is valid-by-construction, a missing pattern / missing action can
 * never be embedded in a {@link CommandPolicyUpdate} — those cases are rejected at rule
 * construction and asserted here directly. The invalid update that can reach {@code applyUpdate} is
 * a rules list carrying duplicate ids, which the {@link CommandPolicy} constructor rejects; the
 * service must therefore throw, persist nothing, and retain the previously active policy.
 */
class InvalidPolicyUpdateProperties {

    @Property(tries = 200)
    void invalidUpdatesAreRejectedAndLastKnownGoodIsRetained(
            @ForAll("goodRuleIds") String goodId,
            @ForAll("dupRuleIds") String dupId,
            @ForAll("goodActions") PolicyAction goodAction) {

        InMemoryCommandPolicyRepository repo = new InMemoryCommandPolicyRepository();
        CommandPolicyService service = new CommandPolicyService(repo);
        service.loadActive();

        // Establish a valid last-known-good active policy (version 1).
        CommandPolicy good = service.applyUpdate(
                new CommandPolicyUpdate(List.of(new PolicyRule(goodId, PatternKind.GLOB, "curl *",
                        PolicyScope.any(), goodAction))),
                "admin");
        int savesAfterGood = repo.saveCount();

        // Missing pattern / missing action are rejected at rule construction, so they can never
        // enter an update (Req 2.12).
        assertThatThrownBy(() -> new PolicyRule("r", PatternKind.GLOB, null, PolicyScope.any(), PolicyAction.DENY))
                .isInstanceOf(InvalidCommandPolicyException.class);
        assertThatThrownBy(() -> new PolicyRule("r", PatternKind.GLOB, "  ", PolicyScope.any(), PolicyAction.DENY))
                .isInstanceOf(InvalidCommandPolicyException.class);
        assertThatThrownBy(() -> new PolicyRule("r", PatternKind.GLOB, "ls *", PolicyScope.any(), null))
                .isInstanceOf(InvalidCommandPolicyException.class);

        // A duplicate-id rules list is the invalid update that reaches applyUpdate (Req 2.12).
        List<PolicyRule> duplicateIds = new ArrayList<>();
        duplicateIds.add(new PolicyRule(dupId, PatternKind.GLOB, "rm -rf *", PolicyScope.any(), PolicyAction.DENY));
        duplicateIds.add(new PolicyRule(dupId, PatternKind.REGEX, "^shutdown", PolicyScope.any(), PolicyAction.ALLOW));
        CommandPolicyUpdate invalid = new CommandPolicyUpdate(duplicateIds);

        assertThatThrownBy(() -> service.applyUpdate(invalid, "attacker"))
                .isInstanceOf(InvalidCommandPolicyException.class);

        // Nothing was persisted for the rejected update, and the active policy is unchanged (Req 2.13).
        assertThat(repo.saveCount()).isEqualTo(savesAfterGood);
        assertThat(service.getActivePolicy()).contains(good);
        assertThat(service.getActivePolicy().orElseThrow().version()).isEqualTo(1);
    }

    @Provide
    Arbitrary<String> goodRuleIds() {
        // Non-blank ids over a large domain so generation stays randomized (>= 100 tries).
        return Arbitraries.strings().alpha().ofMinLength(1).ofMaxLength(12).map(s -> "good-" + s);
    }

    @Provide
    Arbitrary<String> dupRuleIds() {
        return Arbitraries.strings().alpha().ofMinLength(1).ofMaxLength(12).map(s -> "dup-" + s);
    }

    @Provide
    Arbitrary<PolicyAction> goodActions() {
        return Arbitraries.of(PolicyAction.values());
    }
}
