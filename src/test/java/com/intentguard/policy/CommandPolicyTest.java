package com.intentguard.policy;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.junit.jupiter.api.Test;

import com.intentguard.domain.Actor;
import com.intentguard.domain.ActorType;
import com.intentguard.domain.CommandEvent;

/**
 * Unit tests for {@link PolicyRule} / {@link CommandPolicy} construction, unique-id enforcement,
 * and the missing-pattern / missing-action / out-of-set-action rejection cases (Req 2.1, 2.12).
 */
class CommandPolicyTest {

    private static PolicyRule rule(String id, PolicyAction action) {
        return new PolicyRule(id, PatternKind.GLOB, "rm *", PolicyScope.any(), action);
    }

    private static CommandEvent event(String commandText) {
        return new CommandEvent(
                "evt-1",
                Actor.human("alice"),
                "sess-1",
                commandText,
                "/repo",
                "acme",
                Map.of(),
                0L,
                null,
                null,
                null,
                null);
    }

    // ---- valid construction --------------------------------------------------------------------

    @Test
    void constructsValidRuleAndExposesComponents() {
        PolicyRule r = new PolicyRule("r1", PatternKind.REGEX, "^rm\\s+-rf", PolicyScope.any(),
                PolicyAction.DENY);
        assertThat(r.id()).isEqualTo("r1");
        assertThat(r.kind()).isEqualTo(PatternKind.REGEX);
        assertThat(r.action()).isEqualTo(PolicyAction.DENY);
        assertThat(r.scope()).isEqualTo(PolicyScope.any());
    }

    @Test
    void constructsValidPolicyWithEmptyRules() {
        CommandPolicy policy = new CommandPolicy(1, List.of(), "admin", 0L);
        assertThat(policy.version()).isEqualTo(1);
        assertThat(policy.rules()).isEmpty();
    }

    @Test
    void policyRulesAreImmutableAndDefensivelyCopied() {
        PolicyRule r = rule("r1", PolicyAction.DENY);
        java.util.List<PolicyRule> source = new java.util.ArrayList<>(List.of(r));
        CommandPolicy policy = new CommandPolicy(1, source, "admin", 0L);
        source.clear();
        assertThat(policy.rules()).containsExactly(r);
        assertThatThrownBy(() -> policy.rules().add(rule("r2", PolicyAction.ALLOW)))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    // ---- unique-id enforcement -----------------------------------------------------------------

    @Test
    void rejectsDuplicateRuleIds() {
        assertThatThrownBy(() -> new CommandPolicy(
                1, List.of(rule("dup", PolicyAction.DENY), rule("dup", PolicyAction.ALLOW)),
                "admin", 0L))
                .isInstanceOf(InvalidCommandPolicyException.class)
                .hasMessageContaining("dup");
    }

    @Test
    void acceptsDistinctRuleIds() {
        assertThatCode(() -> new CommandPolicy(
                1, List.of(rule("a", PolicyAction.DENY), rule("b", PolicyAction.ALLOW)),
                "admin", 0L))
                .doesNotThrowAnyException();
    }

    // ---- missing / invalid pattern -------------------------------------------------------------

    @Test
    void rejectsNullPattern() {
        assertThatThrownBy(() -> new PolicyRule("r", PatternKind.GLOB, null, PolicyScope.any(),
                PolicyAction.DENY))
                .isInstanceOf(InvalidCommandPolicyException.class)
                .hasMessageContaining("pattern");
    }

    @Test
    void rejectsBlankPattern() {
        assertThatThrownBy(() -> new PolicyRule("r", PatternKind.GLOB, "   ", PolicyScope.any(),
                PolicyAction.DENY))
                .isInstanceOf(InvalidCommandPolicyException.class)
                .hasMessageContaining("pattern");
    }

    @Test
    void rejectsNonCompilableRegexPattern() {
        assertThatThrownBy(() -> new PolicyRule("r", PatternKind.REGEX, "[unclosed",
                PolicyScope.any(), PolicyAction.DENY))
                .isInstanceOf(InvalidCommandPolicyException.class)
                .hasMessageContaining("compilable");
    }

    // ---- missing action / out-of-set action ----------------------------------------------------

    @Test
    void rejectsNullAction() {
        assertThatThrownBy(() -> new PolicyRule("r", PatternKind.GLOB, "rm *", PolicyScope.any(),
                null))
                .isInstanceOf(InvalidCommandPolicyException.class)
                .hasMessageContaining("action");
    }

    @Test
    void policyActionSetIsExactlyDenyRequireConfirmAllow() {
        // The action is a closed enum; any value outside this set is not representable, which is
        // how "an action outside {DENY, REQUIRE_CONFIRM, ALLOW}" is rejected structurally.
        assertThat(PolicyAction.values())
                .containsExactly(PolicyAction.DENY, PolicyAction.REQUIRE_CONFIRM, PolicyAction.ALLOW);
    }

    // ---- other structural validation -----------------------------------------------------------

    @Test
    void rejectsBlankRuleId() {
        assertThatThrownBy(() -> new PolicyRule("  ", PatternKind.GLOB, "rm *", PolicyScope.any(),
                PolicyAction.DENY))
                .isInstanceOf(InvalidCommandPolicyException.class)
                .hasMessageContaining("id");
    }

    @Test
    void rejectsNullScope() {
        assertThatThrownBy(() -> new PolicyRule("r", PatternKind.GLOB, "rm *", null,
                PolicyAction.DENY))
                .isInstanceOf(InvalidCommandPolicyException.class)
                .hasMessageContaining("scope");
    }

    @Test
    void rejectsNullPatternKind() {
        assertThatThrownBy(() -> new PolicyRule("r", null, "rm *", PolicyScope.any(),
                PolicyAction.DENY))
                .isInstanceOf(InvalidCommandPolicyException.class);
    }

    @Test
    void rejectsVersionBelowOne() {
        assertThatThrownBy(() -> new CommandPolicy(0, List.of(), "admin", 0L))
                .isInstanceOf(InvalidCommandPolicyException.class)
                .hasMessageContaining("version");
    }

    @Test
    void rejectsNullRulesList() {
        assertThatThrownBy(() -> new CommandPolicy(1, null, "admin", 0L))
                .isInstanceOf(InvalidCommandPolicyException.class)
                .hasMessageContaining("rules");
    }

    // ---- first-match structure -----------------------------------------------------------------

    @Test
    void firstMatchSelectsLowestIndexMatchingRuleInListOrder() {
        PolicyRule first = new PolicyRule("first", PatternKind.GLOB, "rm *", PolicyScope.any(),
                PolicyAction.REQUIRE_CONFIRM);
        PolicyRule second = new PolicyRule("second", PatternKind.GLOB, "rm *", PolicyScope.any(),
                PolicyAction.DENY);
        CommandPolicy policy = new CommandPolicy(1, List.of(first, second), "admin", 0L);

        assertThat(policy.firstMatch(event("rm -rf /"), null)).contains(first);
    }

    @Test
    void firstMatchReturnsEmptyWhenNoRuleMatches() {
        CommandPolicy policy = new CommandPolicy(1, List.of(rule("r", PolicyAction.DENY)),
                "admin", 0L);
        assertThat(policy.firstMatch(event("ls -la"), null)).isEmpty();
    }

    @Test
    void ruleMatchesUseNormalizedCommandTextAndArguments() {
        PolicyRule glob = new PolicyRule("g", PatternKind.GLOB, "git push*", PolicyScope.any(),
                PolicyAction.REQUIRE_CONFIRM);
        // Extra/leading whitespace and case are normalized away before matching.
        assertThat(glob.matches(event("  GIT   push origin main "), null)).isTrue();
        assertThat(glob.matches(event("git pull"), null)).isFalse();
    }

    @Test
    void scopeRestrictsRuleToMatchingFacets() {
        PolicyRule scoped = new PolicyRule("s", PatternKind.GLOB, "rm *",
                new PolicyScope("bob", null, null, ActorType.AGENT), PolicyAction.DENY);
        CommandEvent aliceHuman = event("rm -rf /");
        assertThat(scoped.matches(aliceHuman, null)).isFalse();

        CommandEvent bobAgent = new CommandEvent("e", new Actor(ActorType.AGENT, "bob", "alice"),
                "s", "rm -rf /", "/repo", "acme", Map.of(), 0L, null, null, null, null);
        assertThat(scoped.matches(bobAgent, null)).isTrue();
    }
}
