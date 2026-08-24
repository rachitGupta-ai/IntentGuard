package com.intentguard.translation;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.mockito.Mockito.mock;

import com.mongodb.client.MongoDatabase;

/**
 * Deterministic, DB-free {@link LanguagePreferenceRepository} backed by a map keyed on
 * {@code operatorId}, mirroring {@code InMemoryCommandPolicyRepository} in the policy tests.
 *
 * <p>Overrides the two query methods so no live Mongo collection is touched; the superclass
 * constructor is satisfied with a mock {@link MongoDatabase} whose collection is never used. Used by
 * the {@code Language_Preference} property tests so {@link LanguagePreferenceService} can be
 * exercised without live Mongo.
 */
class InMemoryLanguagePreferenceRepository extends LanguagePreferenceRepository {

    private final Map<String, LanguagePreferenceDocument> byOperator = new HashMap<>();
    private int saveCount = 0;

    InMemoryLanguagePreferenceRepository() {
        super(mock(MongoDatabase.class));
    }

    @Override
    public Optional<LanguagePreferenceDocument> findByOperatorId(String operatorId) {
        return Optional.ofNullable(byOperator.get(operatorId));
    }

    @Override
    public void save(LanguagePreferenceDocument preference) {
        // Upsert by operatorId, mirroring the real repository's replaceOne(upsert=true).
        byOperator.put(preference.getOperatorId(), preference);
        saveCount++;
    }

    /** Number of {@link #save} calls observed — used to assert nothing was persisted on rejection. */
    int saveCount() {
        return saveCount;
    }
}
