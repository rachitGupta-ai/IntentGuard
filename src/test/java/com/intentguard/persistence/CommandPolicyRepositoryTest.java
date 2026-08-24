package com.intentguard.persistence;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import org.bson.BsonDocument;
import org.bson.BsonDocumentReader;
import org.bson.BsonDocumentWriter;
import org.bson.codecs.Codec;
import org.bson.codecs.DecoderContext;
import org.bson.codecs.EncoderContext;
import org.bson.codecs.configuration.CodecRegistry;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.mock;

import com.intentguard.domain.ActorType;
import com.intentguard.policy.CommandPolicy;
import com.intentguard.policy.PatternKind;
import com.intentguard.policy.PolicyAction;
import com.intentguard.policy.PolicyRule;
import com.intentguard.policy.PolicyScope;
import com.mongodb.client.MongoDatabase;

/**
 * Verifies the {@code command_policies} persistence: that {@link CommandPolicyDocument} (with its
 * embedded {@link PolicyRuleDocument} / {@link PolicyScopeDocument} entries, enums stored as
 * {@code name()} strings, and nullable scope facets) round-trips through the POJO codec registry
 * configured in {@link MongoConfig}, and that {@link CommandPolicyRepository} maps to and from the
 * {@link CommandPolicy} domain record, keeps the highest version active, and finds by version. No
 * live MongoDB connection is required.
 */
class CommandPolicyRepositoryTest {

    private final CodecRegistry registry = new MongoConfig().intentGuardCodecRegistry();

    private <T> T roundTrip(T value, Class<T> type) {
        Codec<T> codec = registry.get(type);
        BsonDocument bson = new BsonDocument();
        codec.encode(new BsonDocumentWriter(bson), value, EncoderContext.builder().build());
        return codec.decode(new BsonDocumentReader(bson), DecoderContext.builder().build());
    }

    @Test
    void commandPolicyDocumentRoundTripsWithEmbeddedRulesAndScopes() {
        PolicyScopeDocument scope = new PolicyScopeDocument();
        scope.setUser(null);
        scope.setGroup("ops");
        scope.setRepo("service-repo");
        scope.setActorType(ActorType.AGENT.name());

        PolicyRuleDocument denyRule = new PolicyRuleDocument();
        denyRule.setId("deny-rm-rf-root");
        denyRule.setKind(PatternKind.REGEX.name());
        denyRule.setPattern("^rm\\s+-rf\\s+/");
        denyRule.setScope(new PolicyScopeDocument()); // all-null facets = any
        denyRule.setAction(PolicyAction.DENY.name());

        PolicyRuleDocument confirmRule = new PolicyRuleDocument();
        confirmRule.setId("confirm-kubectl-delete-ns");
        confirmRule.setKind(PatternKind.GLOB.name());
        confirmRule.setPattern("kubectl delete ns *");
        confirmRule.setScope(scope);
        confirmRule.setAction(PolicyAction.REQUIRE_CONFIRM.name());

        CommandPolicyDocument doc = new CommandPolicyDocument();
        doc.setVersion(3);
        doc.setRules(new ArrayList<>(List.of(denyRule, confirmRule)));
        doc.setUpdatedBy("admin");
        doc.setUpdatedAt(1_710_000_000_000L);

        CommandPolicyDocument out = roundTrip(doc, CommandPolicyDocument.class);

        assertThat(out.getVersion()).isEqualTo(3);
        assertThat(out.getUpdatedBy()).isEqualTo("admin");
        assertThat(out.getUpdatedAt()).isEqualTo(1_710_000_000_000L);
        assertThat(out.getRules()).hasSize(2);

        PolicyRuleDocument outDeny = out.getRules().get(0);
        assertThat(outDeny.getId()).isEqualTo("deny-rm-rf-root");
        assertThat(outDeny.getKind()).isEqualTo("REGEX");
        assertThat(outDeny.getAction()).isEqualTo("DENY");
        assertThat(outDeny.getScope().getUser()).isNull();
        assertThat(outDeny.getScope().getActorType()).isNull();

        PolicyRuleDocument outConfirm = out.getRules().get(1);
        assertThat(outConfirm.getKind()).isEqualTo("GLOB");
        assertThat(outConfirm.getAction()).isEqualTo("REQUIRE_CONFIRM");
        assertThat(outConfirm.getScope().getGroup()).isEqualTo("ops");
        assertThat(outConfirm.getScope().getRepo()).isEqualTo("service-repo");
        assertThat(outConfirm.getScope().getActorType()).isEqualTo("AGENT");
    }

    @Test
    void domainMappingRoundTripsThroughDocument() {
        CommandPolicy policy = new CommandPolicy(
                5,
                List.of(
                        new PolicyRule("deny-a", PatternKind.REGEX, "^shutdown",
                                PolicyScope.any(), PolicyAction.DENY),
                        new PolicyRule("confirm-b", PatternKind.GLOB, "kubectl delete *",
                                new PolicyScope("alice", null, "repo-x", ActorType.HUMAN),
                                PolicyAction.REQUIRE_CONFIRM)),
                "admin",
                1_710_000_000_000L);

        CommandPolicy out =
                CommandPolicyRepository.toDomain(CommandPolicyRepository.toDocument(policy));

        assertThat(out.version()).isEqualTo(5);
        assertThat(out.updatedBy()).isEqualTo("admin");
        assertThat(out.updatedAt()).isEqualTo(1_710_000_000_000L);
        assertThat(out.rules()).hasSize(2);

        PolicyRule confirm = out.rules().get(1);
        assertThat(confirm.id()).isEqualTo("confirm-b");
        assertThat(confirm.kind()).isEqualTo(PatternKind.GLOB);
        assertThat(confirm.action()).isEqualTo(PolicyAction.REQUIRE_CONFIRM);
        assertThat(confirm.scope().user()).isEqualTo("alice");
        assertThat(confirm.scope().group()).isNull();
        assertThat(confirm.scope().repo()).isEqualTo("repo-x");
        assertThat(confirm.scope().actorType()).isEqualTo(ActorType.HUMAN);
    }

    @Test
    void repositorySavesFindsByVersionAndKeepsHighestVersionActive() {
        InMemoryCommandPolicyRepository repo = new InMemoryCommandPolicyRepository();
        assertThat(repo.findActive()).isEmpty();

        CommandPolicy v1 = new CommandPolicy(
                1,
                List.of(new PolicyRule("r1", PatternKind.GLOB, "curl *",
                        PolicyScope.any(), PolicyAction.REQUIRE_CONFIRM)),
                "admin", 1_000L);
        CommandPolicy v2 = new CommandPolicy(
                2,
                List.of(new PolicyRule("r1", PatternKind.REGEX, "^curl\\s",
                        PolicyScope.any(), PolicyAction.DENY)),
                "admin", 2_000L);
        repo.save(v1);
        repo.save(v2);

        // Highest version is active.
        Optional<CommandPolicy> active = repo.findActive();
        assertThat(active).isPresent();
        assertThat(active.get().version()).isEqualTo(2);
        assertThat(active.get().rules().get(0).action()).isEqualTo(PolicyAction.DENY);

        // Both versions are individually retrievable.
        assertThat(repo.findByVersion(1).orElseThrow().rules().get(0).action())
                .isEqualTo(PolicyAction.REQUIRE_CONFIRM);
        assertThat(repo.findByVersion(2).orElseThrow().version()).isEqualTo(2);
        assertThat(repo.findByVersion(99)).isEmpty();
    }

    /**
     * Deterministic, DB-free {@link CommandPolicyRepository} backed by a map keyed on
     * {@code version}. Overrides every query method so no live Mongo collection is touched; the
     * superclass constructor is satisfied with a mock {@link MongoDatabase} whose collection is
     * never used.
     */
    private static final class InMemoryCommandPolicyRepository extends CommandPolicyRepository {

        private final Map<Integer, CommandPolicy> byVersion = new HashMap<>();

        InMemoryCommandPolicyRepository() {
            super(mock(MongoDatabase.class));
        }

        @Override
        public void save(CommandPolicy policy) {
            byVersion.put(policy.version(), policy);
        }

        @Override
        public Optional<CommandPolicy> findActive() {
            return byVersion.values().stream().max((a, b) -> Integer.compare(a.version(), b.version()));
        }

        @Override
        public Optional<CommandPolicy> findByVersion(int version) {
            return Optional.ofNullable(byVersion.get(version));
        }
    }
}
