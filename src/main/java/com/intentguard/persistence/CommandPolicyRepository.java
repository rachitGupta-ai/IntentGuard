package com.intentguard.persistence;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Repository;

import com.intentguard.domain.ActorType;
import com.intentguard.policy.CommandPolicy;
import com.intentguard.policy.PatternKind;
import com.intentguard.policy.PolicyAction;
import com.intentguard.policy.PolicyRule;
import com.intentguard.policy.PolicyScope;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import static com.mongodb.client.model.Filters.eq;
import com.mongodb.client.model.ReplaceOptions;
import com.mongodb.client.model.Sorts;

/**
 * Repository for the {@code command_policies} collection (Req 2.14). CommandPolicies are versioned;
 * the highest-versioned document is the active policy, reloaded across Enforcement_Engine restarts.
 *
 * <p>Mirrors {@link ThresholdConfigRepository}: the active policy is read through a
 * {@link LastKnownGoodCache} so a transient Datastore read failure does not leave the guardrail
 * path without a policy — the last-known-good policy is served instead, and writes refresh the
 * cache. The stored shape is a {@link CommandPolicyDocument} JavaBean (POJO codec), mapped to and
 * from the immutable {@link CommandPolicy} domain record at the repository boundary
 * (mirroring {@link PendingApprovalRepository}). The mapping methods are exposed as {@code static}
 * so callers that hold a {@link CommandPolicyDocument} can convert without a repository instance.
 */
@Repository
public class CommandPolicyRepository {

    static final String COLLECTION = "command_policies";
    private static final String ACTIVE_KEY = "active";

    private final MongoCollection<CommandPolicyDocument> collection;
    private final LastKnownGoodCache<String, CommandPolicy> cache = new LastKnownGoodCache<>();

    public CommandPolicyRepository(MongoDatabase database) {
        this.collection = database.getCollection(COLLECTION, CommandPolicyDocument.class);
    }

    /**
     * Returns the active (highest-versioned) policy, caching it and falling back to the
     * last-known-good policy on a transient read failure. Returns {@link Optional#empty()} when no
     * policy exists yet (and none is cached).
     */
    public Optional<CommandPolicy> findActive() {
        CommandPolicy policy = cache.load(ACTIVE_KEY, () -> {
            CommandPolicyDocument doc = collection.find().sort(Sorts.descending("version")).first();
            return doc == null ? null : toDomain(doc);
        });
        return Optional.ofNullable(policy);
    }

    /** Looks up a specific policy version. */
    public Optional<CommandPolicy> findByVersion(int version) {
        return Optional.ofNullable(collection.find(eq("version", version)).first())
                .map(CommandPolicyRepository::toDomain);
    }

    /**
     * Upserts a policy by {@code version} (Req 2.14). If the saved version is the newest, the
     * active-policy cache is refreshed so subsequent reads reflect it.
     */
    public void save(CommandPolicy policy) {
        collection.replaceOne(
                eq("version", policy.version()),
                toDocument(policy),
                new ReplaceOptions().upsert(true));
        boolean newerThanCached = cache.peek(ACTIVE_KEY)
                .map(current -> policy.version() >= current.version())
                .orElse(true);
        if (newerThanCached) {
            cache.put(ACTIVE_KEY, policy);
        }
    }

    // --- Domain <-> document mapping -----------------------------------------------------------

    /** Maps a {@link CommandPolicy} domain record to its persisted {@link CommandPolicyDocument}. */
    public static CommandPolicyDocument toDocument(CommandPolicy policy) {
        CommandPolicyDocument doc = new CommandPolicyDocument();
        doc.setVersion(policy.version());
        List<PolicyRuleDocument> ruleDocs = new ArrayList<>(policy.rules().size());
        for (PolicyRule rule : policy.rules()) {
            ruleDocs.add(toDocument(rule));
        }
        doc.setRules(ruleDocs);
        doc.setUpdatedBy(policy.updatedBy());
        doc.setUpdatedAt(policy.updatedAt());
        return doc;
    }

    /**
     * Maps a persisted {@link CommandPolicyDocument} back to the immutable {@link CommandPolicy}
     * domain record, which re-validates it by construction.
     */
    public static CommandPolicy toDomain(CommandPolicyDocument doc) {
        List<PolicyRuleDocument> ruleDocs = doc.getRules() == null ? List.of() : doc.getRules();
        List<PolicyRule> rules = new ArrayList<>(ruleDocs.size());
        for (PolicyRuleDocument ruleDoc : ruleDocs) {
            rules.add(toDomain(ruleDoc));
        }
        return new CommandPolicy(doc.getVersion(), rules, doc.getUpdatedBy(), doc.getUpdatedAt());
    }

    static PolicyRuleDocument toDocument(PolicyRule rule) {
        PolicyRuleDocument doc = new PolicyRuleDocument();
        doc.setId(rule.id());
        doc.setKind(rule.kind() == null ? null : rule.kind().name());
        doc.setPattern(rule.pattern());
        doc.setScope(toDocument(rule.scope()));
        doc.setAction(rule.action() == null ? null : rule.action().name());
        return doc;
    }

    static PolicyRule toDomain(PolicyRuleDocument doc) {
        return new PolicyRule(
                doc.getId(),
                doc.getKind() == null ? null : PatternKind.valueOf(doc.getKind()),
                doc.getPattern(),
                doc.getScope() == null ? PolicyScope.any() : toDomain(doc.getScope()),
                doc.getAction() == null ? null : PolicyAction.valueOf(doc.getAction()));
    }

    static PolicyScopeDocument toDocument(PolicyScope scope) {
        PolicyScopeDocument doc = new PolicyScopeDocument();
        doc.setUser(scope.user());
        doc.setGroup(scope.group());
        doc.setRepo(scope.repo());
        doc.setActorType(scope.actorType() == null ? null : scope.actorType().name());
        return doc;
    }

    static PolicyScope toDomain(PolicyScopeDocument doc) {
        return new PolicyScope(
                doc.getUser(),
                doc.getGroup(),
                doc.getRepo(),
                doc.getActorType() == null ? null : ActorType.valueOf(doc.getActorType()));
    }
}
