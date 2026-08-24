package com.intentguard.translation;

import java.util.ArrayList;
import java.util.List;

import static org.mockito.Mockito.mock;

import com.mongodb.client.MongoDatabase;

/**
 * Deterministic, DB-free {@link TranslationRecordRepository} that captures every saved
 * {@link TranslationRecord} into an in-memory list, mirroring the
 * {@link InMemoryLanguagePreferenceRepository} pattern used by the other translation tests.
 *
 * <p>{@link #save(TranslationRecord)} is overridden to record the persisted provenance without
 * touching a live Mongo collection; the superclass constructor is satisfied with a mock
 * {@link MongoDatabase} whose collection is never used. Used by the Translation_Record provenance
 * property test so {@link DefaultTranslationService} can be exercised end-to-end without live Mongo.
 */
class InMemoryTranslationRecordRepository extends TranslationRecordRepository {

    private final List<TranslationRecord> saved = new ArrayList<>();

    InMemoryTranslationRecordRepository() {
        super(mock(MongoDatabase.class));
    }

    @Override
    public void save(TranslationRecord record) {
        saved.add(record);
    }

    /** All Translation_Records captured by {@link #save}, in insertion order. */
    List<TranslationRecord> saved() {
        return saved;
    }
}
