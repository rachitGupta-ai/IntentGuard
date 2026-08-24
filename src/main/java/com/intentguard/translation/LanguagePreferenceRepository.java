package com.intentguard.translation;

import java.util.Optional;

import org.springframework.stereotype.Repository;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import static com.mongodb.client.model.Filters.eq;
import com.mongodb.client.model.ReplaceOptions;

/**
 * Repository for the {@code language_preferences} collection (Req 1.4). Each operator's
 * {@code Language_Preference} is keyed by {@code operatorId} and upserted in place, so changing a
 * preference replaces the prior document rather than duplicating it, and the selection survives
 * Control_Tower sessions and engine restarts.
 *
 * <p>Follows the same Mongo POJO document + repository convention as
 * {@link com.intentguard.persistence.AuditHistoryRepository} and
 * {@link com.intentguard.persistence.BehavioralProfileRepository}: a {@link MongoCollection} typed
 * to the document POJO, a keyed {@code findBy...} read, and an upserting {@code save}.
 */
@Repository
public class LanguagePreferenceRepository {

    static final String COLLECTION = LanguagePreferenceDocument.COLLECTION;

    private final MongoCollection<LanguagePreferenceDocument> collection;

    public LanguagePreferenceRepository(MongoDatabase database) {
        this.collection = database.getCollection(COLLECTION, LanguagePreferenceDocument.class);
    }

    /**
     * Loads an operator's saved {@code Language_Preference}, or empty when none is saved (Req 1.3).
     *
     * @param operatorId the operator whose preference to load
     * @return the persisted preference document, or empty when the operator has no saved preference
     */
    public Optional<LanguagePreferenceDocument> findByOperatorId(String operatorId) {
        return Optional.ofNullable(collection.find(eq("operatorId", operatorId)).first());
    }

    /** Upserts an operator's {@code Language_Preference} by {@code operatorId} (Req 1.4). */
    public void save(LanguagePreferenceDocument preference) {
        collection.replaceOne(
                eq("operatorId", preference.getOperatorId()),
                preference,
                new ReplaceOptions().upsert(true));
    }
}
