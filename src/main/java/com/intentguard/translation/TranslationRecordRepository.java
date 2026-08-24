package com.intentguard.translation;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Repository;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import static com.mongodb.client.model.Filters.eq;
import com.mongodb.client.model.Sorts;

/**
 * Repository for the {@code translation_records} collection (Req 10.1, 10.3). Every successful
 * translation, recognition, or synthesis is persisted here as a {@link TranslationRecordDocument}
 * and survives Enforcement_Engine restarts.
 *
 * <p>Translation_Records are append-only: each successful operation writes a new record, so no
 * in-memory caching is applied and reads are historical queries. This follows the same Mongo POJO
 * document + repository convention as {@link com.intentguard.persistence.AuditHistoryRepository}: a
 * {@link MongoCollection} typed to the document POJO, an inserting {@code save}, and time-ordered
 * {@code find} reads.
 *
 * <p>The {@link TranslationRecord} domain record and its {@link TranslationRecordDocument} persisted
 * shape are bridged by {@link #toDocument(TranslationRecord)} / {@link #toDomain(TranslationRecordDocument)},
 * storing the {@code Supported_Language} tags as their BCP-47 string values and the
 * {@link TranslationRecordKind} as its {@code name()} string.
 */
@Repository
public class TranslationRecordRepository {

    static final String COLLECTION = TranslationRecordDocument.COLLECTION;

    private final MongoCollection<TranslationRecordDocument> collection;

    public TranslationRecordRepository(MongoDatabase database) {
        this.collection = database.getCollection(COLLECTION, TranslationRecordDocument.class);
    }

    /**
     * Persists a Translation_Record capturing the full provenance of a successful operation
     * (Req 10.1). A persistence failure surfaces as a runtime exception to the caller, which the
     * {@code TranslationService} catches so that a failed write never blocks presenting the
     * Translated_Text (Req 10.2).
     */
    public void save(TranslationRecord record) {
        collection.insertOne(toDocument(record));
    }

    /** Returns all Translation_Records, ordered oldest-first. */
    public List<TranslationRecord> findAll() {
        List<TranslationRecordDocument> documents = new ArrayList<>();
        collection.find().sort(Sorts.ascending("timestamp")).into(documents);
        return toDomainList(documents);
    }

    /**
     * Returns the Translation_Records for the given operation {@link TranslationRecordKind},
     * ordered oldest-first.
     */
    public List<TranslationRecord> findByKind(TranslationRecordKind kind) {
        List<TranslationRecordDocument> documents = new ArrayList<>();
        collection.find(eq("kind", kind == null ? null : kind.name()))
                .sort(Sorts.ascending("timestamp"))
                .into(documents);
        return toDomainList(documents);
    }

    private static List<TranslationRecord> toDomainList(List<TranslationRecordDocument> documents) {
        List<TranslationRecord> records = new ArrayList<>(documents.size());
        for (TranslationRecordDocument document : documents) {
            records.add(toDomain(document));
        }
        return records;
    }

    /** Maps a {@link TranslationRecord} domain record to its persisted {@link TranslationRecordDocument}. */
    static TranslationRecordDocument toDocument(TranslationRecord record) {
        TranslationRecordDocument document = new TranslationRecordDocument();
        document.setSourceText(record.sourceText());
        document.setTranslatedText(record.translatedText());
        document.setSourceLanguageTag(
                record.sourceLanguageTag() == null ? null : record.sourceLanguageTag().value());
        document.setTargetLanguageTag(
                record.targetLanguageTag() == null ? null : record.targetLanguageTag().value());
        document.setProviderId(record.providerId());
        document.setKind(record.kind() == null ? null : record.kind().name());
        document.setTimestamp(record.timestamp());
        return document;
    }

    /** Reconstructs a {@link TranslationRecord} from its persisted {@link TranslationRecordDocument}. */
    static TranslationRecord toDomain(TranslationRecordDocument document) {
        return new TranslationRecord(
                document.getSourceText(),
                document.getTranslatedText(),
                new LanguageTag(document.getSourceLanguageTag()),
                new LanguageTag(document.getTargetLanguageTag()),
                document.getProviderId(),
                TranslationRecordKind.valueOf(document.getKind()),
                document.getTimestamp());
    }
}
