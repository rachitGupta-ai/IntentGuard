package com.intentguard.translation;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.mock;

import com.mongodb.client.MongoDatabase;

/**
 * Integration-style test for Translation_Record durability across a restart-equivalent reload
 * (Req 10.3): the Datastore SHALL retain Translation_Records across Enforcement_Engine restarts.
 *
 * <p>No live MongoDB is available in this environment, so the durable {@code translation_records}
 * collection is modelled by a shared, out-of-process-equivalent store of
 * {@link TranslationRecordDocument} persisted shapes that outlives any single repository instance.
 * This mirrors the established repository-test convention in the codebase (e.g.
 * {@code InMemoryLanguagePreferenceRepository}, {@code SelfDefenseGuardTest}'s in-memory audit
 * repository): subclass the real {@link TranslationRecordRepository} over a mocked
 * {@link MongoDatabase} whose collection is never touched, and back the overridden methods with an
 * in-memory store.
 *
 * <p>Crucially, the shared store holds the persisted {@link TranslationRecordDocument} shape and the
 * fake routes every write/read through the real
 * {@link TranslationRecordRepository#toDocument(TranslationRecord)} /
 * {@link TranslationRecordRepository#toDomain(TranslationRecordDocument)} mapping. A "restart" is
 * simulated by discarding the first repository instance and constructing a brand-new one against the
 * same durable store — exactly what a real Enforcement_Engine restart against the same Mongo
 * collection would do. Records written before the restart must survive it with every provenance
 * field intact.
 */
class TranslationRecordDurabilityTest {

    /**
     * A restart-equivalent {@link TranslationRecordRepository} whose persisted documents live in an
     * externally-owned {@code durableStore}. Multiple instances constructed over the same store see
     * the same records, modelling a datastore that survives restarts. Writes and reads pass through
     * the real domain/document mapping so provenance-field preservation is genuinely exercised.
     */
    private static final class ReloadableTranslationRecordRepository extends TranslationRecordRepository {

        private final List<TranslationRecordDocument> durableStore;

        ReloadableTranslationRecordRepository(List<TranslationRecordDocument> durableStore) {
            super(mock(MongoDatabase.class));
            this.durableStore = durableStore;
        }

        @Override
        public void save(TranslationRecord record) {
            durableStore.add(TranslationRecordRepository.toDocument(record));
        }

        @Override
        public List<TranslationRecord> findAll() {
            List<TranslationRecord> records = new ArrayList<>(durableStore.size());
            durableStore.stream()
                    .sorted(java.util.Comparator.comparingLong(TranslationRecordDocument::getTimestamp))
                    .forEach(d -> records.add(TranslationRecordRepository.toDomain(d)));
            return records;
        }

        @Override
        public List<TranslationRecord> findByKind(TranslationRecordKind kind) {
            List<TranslationRecord> records = new ArrayList<>();
            durableStore.stream()
                    .filter(d -> kind != null && kind.name().equals(d.getKind()))
                    .sorted(java.util.Comparator.comparingLong(TranslationRecordDocument::getTimestamp))
                    .forEach(d -> records.add(TranslationRecordRepository.toDomain(d)));
            return records;
        }
    }

    @Test
    void recordsSurviveRestartWithFullProvenanceIntact() {
        List<TranslationRecordDocument> durableStore = new ArrayList<>();

        // --- Engine "run 1": persist a few Translation_Records through one repository instance. ---
        TranslationRecordRepository beforeRestart = new ReloadableTranslationRecordRepository(durableStore);

        TranslationRecord outbound = new TranslationRecord(
                "Anomalous session risk score exceeded 0.85 for rm -rf /var/data",
                "\u0938\u0924\u094d\u0930 \u0915\u093e \u091c\u094b\u0916\u093f\u092e rm -rf /var/data",
                new LanguageTag("en"),
                new LanguageTag("hi"),
                "bhashini",
                TranslationRecordKind.OUTBOUND_CONTENT,
                1_000L);
        TranslationRecord inbound = new TranslationRecord(
                "\u0938\u0930\u094d\u0935\u0930 \u092a\u0941\u0928\u0930\u093e\u0930\u0902\u092d \u0915\u0930\u0947\u0902",
                "restart the server",
                new LanguageTag("hi"),
                new LanguageTag("en"),
                "cloud",
                TranslationRecordKind.INBOUND_INTENT,
                2_000L);
        TranslationRecord tts = new TranslationRecord(
                "Playback of alert for host db-primary-01",
                "\u0b9a\u0bcd\u0b9f\u0bcb\u0bb0\u0bc7\u0b9c\u0bcd host db-primary-01",
                new LanguageTag("en"),
                new LanguageTag("ta"),
                "bhashini",
                TranslationRecordKind.TTS,
                3_000L);

        beforeRestart.save(outbound);
        beforeRestart.save(inbound);
        beforeRestart.save(tts);

        assertThat(beforeRestart.findAll()).hasSize(3);

        // --- Engine "restart": drop the old instance, construct a new one over the same store. ---
        TranslationRecordRepository afterRestart = new ReloadableTranslationRecordRepository(durableStore);

        List<TranslationRecord> reloaded = afterRestart.findAll();

        // All records survive the restart, oldest-first by timestamp.
        assertThat(reloaded)
                .as("all persisted records survive a restart-equivalent reload")
                .containsExactly(outbound, inbound, tts);

        // Every provenance field of the first record is preserved byte-for-byte across the reload.
        TranslationRecord reloadedOutbound = reloaded.get(0);
        assertThat(reloadedOutbound.sourceText()).isEqualTo(outbound.sourceText());
        assertThat(reloadedOutbound.translatedText()).isEqualTo(outbound.translatedText());
        assertThat(reloadedOutbound.sourceLanguageTag()).isEqualTo(outbound.sourceLanguageTag());
        assertThat(reloadedOutbound.targetLanguageTag()).isEqualTo(outbound.targetLanguageTag());
        assertThat(reloadedOutbound.providerId()).isEqualTo(outbound.providerId());
        assertThat(reloadedOutbound.kind()).isEqualTo(outbound.kind());
        assertThat(reloadedOutbound.timestamp()).isEqualTo(outbound.timestamp());
    }

    @Test
    void findByKindReturnsPreviouslySavedRecordsAfterReload() {
        List<TranslationRecordDocument> durableStore = new ArrayList<>();

        TranslationRecordRepository beforeRestart = new ReloadableTranslationRecordRepository(durableStore);

        TranslationRecord outboundOne = new TranslationRecord(
                "score 0.42", "\u0938\u094d\u0915\u094b\u0930 0.42",
                new LanguageTag("en"), new LanguageTag("hi"),
                "bhashini", TranslationRecordKind.OUTBOUND_CONTENT, 10L);
        TranslationRecord outboundTwo = new TranslationRecord(
                "score 0.91", "\u0938\u094d\u0915\u094b\u0930 0.91",
                new LanguageTag("en"), new LanguageTag("hi"),
                "bhashini", TranslationRecordKind.OUTBOUND_CONTENT, 30L);
        TranslationRecord stt = new TranslationRecord(
                "\u0916\u093e\u0924\u093e \u0939\u091f\u093e\u090f\u0902", "delete the account",
                new LanguageTag("hi"), new LanguageTag("en"),
                "cloud", TranslationRecordKind.STT, 20L);

        beforeRestart.save(outboundOne);
        beforeRestart.save(stt);
        beforeRestart.save(outboundTwo);

        // Restart-equivalent reload against the same durable store.
        TranslationRecordRepository afterRestart = new ReloadableTranslationRecordRepository(durableStore);

        // Kind filtering survives the reload and remains oldest-first.
        assertThat(afterRestart.findByKind(TranslationRecordKind.OUTBOUND_CONTENT))
                .containsExactly(outboundOne, outboundTwo);
        assertThat(afterRestart.findByKind(TranslationRecordKind.STT))
                .containsExactly(stt);
        assertThat(afterRestart.findByKind(TranslationRecordKind.TTS))
                .isEmpty();
    }
}
