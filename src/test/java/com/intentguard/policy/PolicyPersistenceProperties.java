package com.intentguard.policy;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

import com.intentguard.domain.ActorType;
import com.intentguard.persistence.CommandPolicyRepository;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

/**
 * Feature: intentguard-guardrails, Property 12: CommandPolicy persistence round-trips and the
 * newest version is active.
 *
 * <p>For any accepted CommandPolicy, persisting it and reloading it (including across a simulated
 * restart) yields an equivalent policy, and reload selects the highest persisted version as the
 * active policy (Validates: Requirements 2.14).
 *
 * <p>A shared {@link InMemoryCommandPolicyRepository} stands in for the Datastore. A sequence of
 * updates is applied through one service instance; a fresh service instance over the same
 * repository then simulates an Enforcement_Engine restart — its startup load must recover the
 * highest-versioned policy, equal to the last one applied. The domain→document→domain mapping is
 * also asserted to round-trip so persistence preserves the policy exactly.
 */
class PolicyPersistenceProperties {

    @Property(tries = 200)
    void reloadRecoversHighestVersionAndPolicyRoundTrips(
            @ForAll("ruleSequences") List<PolicyRule> ruleSequence) {

        InMemoryCommandPolicyRepository repo = new InMemoryCommandPolicyRepository();
        CommandPolicyService service = new CommandPolicyService(repo);
        service.loadActive();

        CommandPolicy last = null;
        int expectedVersion = 0;
        for (PolicyRule rule : ruleSequence) {
            last = service.applyUpdate(new CommandPolicyUpdate(List.of(rule)), "admin");
            expectedVersion++;
            assertThat(last.version()).isEqualTo(expectedVersion);

            // Each accepted version round-trips through the document mapping unchanged.
            CommandPolicy roundTripped =
                    CommandPolicyRepository.toDomain(CommandPolicyRepository.toDocument(last));
            assertThat(roundTripped).isEqualTo(last);
        }

        // Simulate a restart: a brand-new service over the same Datastore loads on startup.
        CommandPolicyService afterRestart = new CommandPolicyService(repo);
        afterRestart.loadActive();

        assertThat(afterRestart.getActivePolicy()).isPresent();
        assertThat(afterRestart.getActivePolicy().orElseThrow().version()).isEqualTo(expectedVersion);
        assertThat(afterRestart.getActivePolicy()).contains(last);

        // reloadFromDatastore likewise selects the highest persisted version.
        assertThat(afterRestart.reloadFromDatastore().orElseThrow().version()).isEqualTo(expectedVersion);
    }

    @Provide
    Arbitrary<List<PolicyRule>> ruleSequences() {
        return rules().list().ofMinSize(1).ofMaxSize(5);
    }

    @Provide
    Arbitrary<PolicyRule> rules() {
        Arbitrary<String> id = Arbitraries.of("r1", "r2", "deny-x", "confirm-y");
        Arbitrary<PatternKind> kind = Arbitraries.of(PatternKind.values());
        Arbitrary<String> pattern = Arbitraries.of("rm -rf *", "kubectl delete *", "^shutdown", "curl *");
        Arbitrary<PolicyAction> action = Arbitraries.of(PolicyAction.values());
        Arbitrary<PolicyScope> scope = scopes();
        return Combinators.combine(id, kind, pattern, action, scope).as(
                (i, k, p, a, s) -> {
                    // Regex patterns must be valid; the fixed set above is compilable for both kinds.
                    return new PolicyRule(i, k, p, s, a);
                });
    }

    @Provide
    Arbitrary<PolicyScope> scopes() {
        Arbitrary<String> user = Arbitraries.of("alice", (String) null);
        Arbitrary<String> group = Arbitraries.of("ops", (String) null);
        Arbitrary<String> repo = Arbitraries.of("repo-x", (String) null);
        Arbitrary<ActorType> actorType = Arbitraries.of(ActorType.HUMAN, ActorType.AGENT, (ActorType) null);
        return Combinators.combine(user, group, repo, actorType).as(PolicyScope::new);
    }
}
