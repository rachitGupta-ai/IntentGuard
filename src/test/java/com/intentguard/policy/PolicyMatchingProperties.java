package com.intentguard.policy;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

import com.intentguard.domain.Actor;
import com.intentguard.domain.ActorType;
import com.intentguard.domain.CommandEvent;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

/**
 * Feature: intentguard-guardrails, Property 7: Policy matching uses normalized text, honors scope,
 * and selects the first match.
 *
 * <p>For any Command_Event and any ordered list of PolicyRules, {@link CommandPolicy#firstMatch}
 * returns the lowest-index rule whose pattern matches the normalized command text and arguments
 * <em>and</em> whose every non-null Scope facet (user, group, cwd/repo, actor type) equals the
 * event's corresponding facet, or empty when none match (Validates: Requirements 2.4, 2.5, 2.6).
 *
 * <p>The result is checked against an independent reference oracle that re-derives the match with a
 * freshly compiled pattern and facet-by-facet scope comparison, and is cross-checked by asserting
 * the selected rule actually matches while every earlier rule does not.
 */
class PolicyMatchingProperties {

    private static final List<String> TOKENS =
            List.of("rm", "-rf", "kubectl", "delete", "ns", "curl", "ls", "http", "prod");

    @Property(tries = 300)
    void firstMatchSelectsLowestIndexRuleThatMatchesScopeAndNormalizedPattern(
            @ForAll("events") CommandEvent event,
            @ForAll("groups") String group,
            @ForAll("ruleLists") List<PolicyRule> rules) {

        CommandPolicy policy = new CommandPolicy(1, rules, "admin", 1_000L);

        Optional<PolicyRule> actual = policy.firstMatch(event, group);
        Optional<Integer> expectedIndex = oracleFirstMatchIndex(event, group, rules);

        assertThat(actual.isPresent()).isEqualTo(expectedIndex.isPresent());

        if (expectedIndex.isPresent()) {
            int idx = expectedIndex.get();
            assertThat(actual).containsSame(rules.get(idx));
            // The selected rule truly matches...
            assertThat(rules.get(idx).matches(event, group)).isTrue();
            // ...and no earlier rule matches (it is the FIRST match).
            for (int i = 0; i < idx; i++) {
                assertThat(rules.get(i).matches(event, group))
                        .as("earlier rule at index %s must not match", i)
                        .isFalse();
            }
        }
    }

    // --- Independent reference oracle -----------------------------------------------------------

    private static Optional<Integer> oracleFirstMatchIndex(
            CommandEvent event, String group, List<PolicyRule> rules) {
        String normalized = event.commandText().strip().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
        for (int i = 0; i < rules.size(); i++) {
            PolicyRule rule = rules.get(i);
            if (scopeMatches(rule.scope(), event, group) && patternMatches(rule, normalized)) {
                return Optional.of(i);
            }
        }
        return Optional.empty();
    }

    private static boolean scopeMatches(PolicyScope scope, CommandEvent event, String group) {
        if (scope.user() != null && !scope.user().equals(event.userId())) {
            return false;
        }
        if (scope.group() != null && !scope.group().equals(group)) {
            return false;
        }
        if (scope.repo() != null
                && !scope.repo().equals(event.repo())
                && !scope.repo().equals(event.cwd())) {
            return false;
        }
        return scope.actorType() == null || scope.actorType() == event.actorType();
    }

    private static boolean patternMatches(PolicyRule rule, String normalized) {
        String regex = rule.kind() == PatternKind.GLOB ? globToRegex(rule.pattern()) : rule.pattern();
        Pattern compiled = Pattern.compile(regex, Pattern.CASE_INSENSITIVE);
        return rule.kind() == PatternKind.GLOB
                ? compiled.matcher(normalized).matches()
                : compiled.matcher(normalized).find();
    }

    private static String globToRegex(String glob) {
        StringBuilder sb = new StringBuilder(glob.length() * 2);
        for (int i = 0; i < glob.length(); i++) {
            char c = glob.charAt(i);
            switch (c) {
                case '*' -> sb.append(".*");
                case '?' -> sb.append('.');
                case '.', '\\', '+', '(', ')', '[', ']', '{', '}', '^', '$', '|' ->
                        sb.append('\\').append(c);
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }

    // --- Generators -----------------------------------------------------------------------------

    @Provide
    Arbitrary<CommandEvent> events() {
        Arbitrary<String> commandText = Arbitraries.of(TOKENS).list().ofMinSize(1).ofMaxSize(4)
                .map(tokens -> String.join(" ", tokens));
        Arbitrary<String> userId = Arbitraries.of("alice", "bob");
        Arbitrary<String> repo = Arbitraries.of("repo-x", "repo-y", (String) null);
        Arbitrary<String> cwd = Arbitraries.of("/srv/app", "/home/alice");
        Arbitrary<ActorType> actorType = Arbitraries.of(ActorType.HUMAN, ActorType.AGENT);

        return Combinators.combine(commandText, userId, repo, cwd, actorType).as(
                (text, user, r, c, type) -> new CommandEvent(
                        "evt", new Actor(type, user, type == ActorType.AGENT ? "principal" : null),
                        null, text, c, r, null, 1_000L, null, null, null, null));
    }

    @Provide
    Arbitrary<String> groups() {
        return Arbitraries.of("ops", "dev", (String) null);
    }

    @Provide
    Arbitrary<List<PolicyRule>> ruleLists() {
        return rules().list().ofMinSize(0).ofMaxSize(5).map(list -> {
            // Give every rule a unique id so the CommandPolicy constructor accepts the list.
            List<PolicyRule> withUniqueIds = new ArrayList<>(list.size());
            for (int i = 0; i < list.size(); i++) {
                PolicyRule r = list.get(i);
                withUniqueIds.add(new PolicyRule("rule-" + i, r.kind(), r.pattern(), r.scope(), r.action()));
            }
            return withUniqueIds;
        });
    }

    @Provide
    Arbitrary<PolicyRule> rules() {
        // GLOB patterns built from tokens and wildcards so matches occur across the input space.
        Arbitrary<String> globPattern = Arbitraries.of(TOKENS).list().ofMinSize(1).ofMaxSize(3)
                .map(tokens -> String.join(" ", tokens))
                .map(base -> base + " *");
        Arbitrary<PolicyScope> scope = scopes();
        Arbitrary<PolicyAction> action = Arbitraries.of(PolicyAction.values());

        return Combinators.combine(globPattern, scope, action).as(
                (pattern, sc, act) -> new PolicyRule("rule", PatternKind.GLOB, pattern, sc, act));
    }

    @Provide
    Arbitrary<PolicyScope> scopes() {
        Arbitrary<String> user = Arbitraries.of("alice", "bob", (String) null);
        Arbitrary<String> group = Arbitraries.of("ops", "dev", (String) null);
        Arbitrary<String> repo = Arbitraries.of("repo-x", "/srv/app", (String) null);
        Arbitrary<ActorType> actorType = Arbitraries.of(ActorType.HUMAN, ActorType.AGENT, (ActorType) null);
        return Combinators.combine(user, group, repo, actorType).as(PolicyScope::new);
    }
}
