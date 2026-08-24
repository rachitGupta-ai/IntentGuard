package com.intentguard.policy;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.mockito.Mockito.mock;

import com.intentguard.persistence.CommandPolicyRepository;
import com.mongodb.client.MongoDatabase;

/**
 * Deterministic, DB-free {@link CommandPolicyRepository} backed by a map keyed on {@code version},
 * mirroring the fake in {@code CommandPolicyRepositoryTest}. Overrides every query method so no live
 * Mongo collection is touched; the superclass constructor is satisfied with a mock
 * {@link MongoDatabase} whose collection is never used. Shared across the {@code com.intentguard.policy}
 * property tests so the CommandPolicyService can be exercised without live Mongo.
 */
final class InMemoryCommandPolicyRepository extends CommandPolicyRepository {

    private final Map<Integer, CommandPolicy> byVersion = new HashMap<>();
    private int saveCount = 0;

    InMemoryCommandPolicyRepository() {
        super(mock(MongoDatabase.class));
    }

    @Override
    public void save(CommandPolicy policy) {
        byVersion.put(policy.version(), policy);
        saveCount++;
    }

    @Override
    public Optional<CommandPolicy> findActive() {
        return byVersion.values().stream().max((a, b) -> Integer.compare(a.version(), b.version()));
    }

    @Override
    public Optional<CommandPolicy> findByVersion(int version) {
        return Optional.ofNullable(byVersion.get(version));
    }

    /** Number of {@link #save} calls observed — used to assert nothing was persisted on rejection. */
    int saveCount() {
        return saveCount;
    }
}
