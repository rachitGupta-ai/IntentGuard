package com.intentguard.persistence;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import org.bson.BsonDocument;
import org.bson.BsonDocumentReader;
import org.bson.BsonDocumentWriter;
import org.bson.RawBsonDocument;
import org.bson.codecs.Codec;
import org.bson.codecs.DecoderContext;
import org.bson.codecs.EncoderContext;
import org.bson.codecs.configuration.CodecRegistry;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

/**
 * Feature: intentguard-semantic-firewall, Property 11: Persistence round-trips preserve data
 *
 * <p>For any Behavioral_Profile, Intent_Session, or Audit_History record, persisting it to the
 * Datastore and reloading it (including across a simulated restart) yields an equivalent object.
 *
 * <p>Validates: Requirements 3.5, 4.5, 11.2
 *
 * <p>No live MongoDB is available in this environment, so the round-trip is driven through the
 * configured POJO {@link CodecRegistry} (the exact codec the Datastore uses): each document is
 * encoded to BSON, serialized to raw bytes, and decoded again through a <em>separately
 * constructed</em> registry to simulate a process restart with a fresh driver. Equivalence is
 * asserted by comparing the canonical BSON of the original against the canonical BSON of the
 * reloaded object (the document POJOs do not override {@code equals}, so field-by-field
 * equivalence is established via their BSON representation, which is order-insensitive for maps).
 */
class PersistenceRoundTripProperties {

    /** Encode-time registry: mirrors the write path of the running application. */
    private final CodecRegistry writeRegistry = new MongoConfig().intentGuardCodecRegistry();

    /** Decode-time registry: a fresh instance simulating a restarted process reloading the data. */
    private final CodecRegistry readRegistry = new MongoConfig().intentGuardCodecRegistry();

    /** Canonical BSON produced by the write path for a document. */
    private <T> BsonDocument encode(T value, Class<T> type) {
        Codec<T> codec = writeRegistry.get(type);
        BsonDocument bson = new BsonDocument();
        codec.encode(new BsonDocumentWriter(bson), value, EncoderContext.builder().build());
        return bson;
    }

    /**
     * Persist-then-reload across a simulated restart: encode with the write registry, serialize
     * to raw BSON bytes and back (emulating what actually crosses the wire / is written to disk,
     * while preserving exact BSON types), decode with the freshly constructed read registry, then
     * re-encode the reloaded object to canonical BSON for equivalence comparison.
     */
    private <T> BsonDocument persistAndReload(T value, Class<T> type) {
        BsonDocument stored = encode(value, type);
        // Round-trip through raw bytes to faithfully emulate storage + reload across a restart.
        RawBsonDocument raw = new RawBsonDocument(stored, writeRegistry.get(BsonDocument.class));
        Codec<T> readCodec = readRegistry.get(type);
        T reloaded = readCodec.decode(new BsonDocumentReader(raw), DecoderContext.builder().build());
        return encode(reloaded, type);
    }

    private <T> void assertRoundTripPreservesData(T value, Class<T> type) {
        BsonDocument original = encode(value, type);
        BsonDocument reloaded = persistAndReload(value, type);
        assertThat(reloaded).isEqualTo(original);
    }

    @Property(tries = 200)
    void behavioralProfileRoundTripsPreserveData(@ForAll("behavioralProfiles") BehavioralProfileDocument profile) {
        assertRoundTripPreservesData(profile, BehavioralProfileDocument.class);
    }

    @Property(tries = 200)
    void intentSessionRoundTripsPreserveData(@ForAll("intentSessions") IntentSessionDocument session) {
        assertRoundTripPreservesData(session, IntentSessionDocument.class);
    }

    @Property(tries = 200)
    void auditHistoryRoundTripsPreserveData(@ForAll("auditHistories") AuditHistoryDocument audit) {
        assertRoundTripPreservesData(audit, AuditHistoryDocument.class);
    }

    // ----- Generators ---------------------------------------------------------------------------

    /** Business-key friendly identifiers: non-empty, no BSON-reserved dot characters. */
    private Arbitrary<String> keys() {
        return Arbitraries.strings().alpha().numeric().ofMinLength(1).ofMaxLength(12);
    }

    /** Free text that may be empty (command text, intent, explanations). */
    private Arbitrary<String> text() {
        return Arbitraries.strings().ofMaxLength(40);
    }

    /** Finite doubles in the unit interval, matching the score/ratio/weight domain. */
    private Arbitrary<Double> unitDoubles() {
        return Arbitraries.doubles().between(0.0, 1.0);
    }

    @Provide
    Arbitrary<BehavioralProfileDocument> behavioralProfiles() {
        Arbitrary<Map<String, Integer>> vocab =
                Arbitraries.maps(keys(), Arbitraries.integers().between(0, 10_000)).ofMaxSize(6);
        Arbitrary<Map<String, Integer>> seq =
                Arbitraries.maps(keys(), Arbitraries.integers().between(0, 10_000)).ofMaxSize(6);
        Arbitrary<Map<String, Double>> ratios = Arbitraries.maps(keys(), unitDoubles()).ofMaxSize(6);
        Arbitrary<Map<String, List<String>>> context =
                Arbitraries.maps(keys(), keys().list().ofMaxSize(4)).ofMaxSize(6);
        Arbitrary<TimingPatternsDocument> timing = Combinators.combine(
                        Arbitraries.integers().between(0, 500).list().ofMinSize(0).ofMaxSize(24),
                        Arbitraries.longs().between(0, 3_600_000L))
                .as(TimingPatternsDocument::new);

        return Combinators.combine(
                        keys(),
                        Arbitraries.longs().between(0, 1_000_000L),
                        Arbitraries.of("LEARNING", "ACTIVE"),
                        vocab,
                        seq,
                        ratios,
                        timing,
                        context)
                .as((userId, eventCount, state, vocabulary, sequenceStats, ratio, timingPatterns, ctx) -> {
                    BehavioralProfileDocument doc = new BehavioralProfileDocument();
                    doc.setUserId(userId);
                    doc.setEventCount(eventCount);
                    doc.setState(state);
                    doc.setVocabulary(vocabulary);
                    doc.setSequenceStats(sequenceStats);
                    doc.setTypedPastedRatioByCategory(ratio);
                    doc.setTimingPatterns(timingPatterns);
                    doc.setContextAssociations(ctx);
                    doc.setUpdatedAt(1_700_000_000_000L);
                    return doc;
                });
    }

    @Provide
    Arbitrary<IntentSessionDocument> intentSessions() {
        return Combinators.combine(
                        keys(),
                        keys(),
                        text(),
                        Arbitraries.of("DECLARED", "INFERRED", "NONE"),
                        Arbitraries.longs().between(0, 2_000_000_000_000L),
                        Arbitraries.longs().between(0, 2_000_000_000_000L).injectNull(0.3),
                        Arbitraries.of(true, false))
                .as((sessionId, userId, intent, source, startedAt, endedAt, open) -> {
                    IntentSessionDocument doc = new IntentSessionDocument();
                    doc.setSessionId(sessionId);
                    doc.setUserId(userId);
                    doc.setDeclaredIntent(intent);
                    doc.setIntentSource(source);
                    doc.setStartedAt(startedAt);
                    doc.setEndedAt(endedAt);
                    doc.setOpen(open);
                    return doc;
                });
    }

    private Arbitrary<ComponentScoreDocument> componentScores() {
        return Combinators.combine(
                        Arbitraries.of(
                                "SEQUENCE_SURPRISE",
                                "CONTEXT_MISMATCH",
                                "BEHAVIORAL_DEVIATION",
                                "SEMANTIC_INCONSISTENCY"),
                        unitDoubles().injectNull(0.3),
                        unitDoubles(),
                        text().injectNull(0.5))
                .as(ComponentScoreDocument::new);
    }

    @Provide
    Arbitrary<AuditHistoryDocument> auditHistories() {
        Arbitrary<List<ComponentScoreDocument>> components = componentScores().list().ofMaxSize(4);
        Arbitrary<List<String>> excluded = Arbitraries.of(
                        "SEQUENCE_SURPRISE",
                        "CONTEXT_MISMATCH",
                        "BEHAVIORAL_DEVIATION",
                        "SEMANTIC_INCONSISTENCY")
                .list()
                .ofMaxSize(4);
        Arbitrary<Map<String, String>> env = Arbitraries.maps(keys(), text()).ofMaxSize(5);

        // Combinators.combine tops out at 8 args, so build in two stages.
        Arbitrary<AuditHistoryDocument> base = Combinators.combine(
                        keys(),
                        keys(),
                        Arbitraries.of("HUMAN", "AGENT"),
                        keys().injectNull(0.3),
                        keys().injectNull(0.3),
                        text(),
                        env,
                        Arbitraries.longs().between(0, 2_000_000_000_000L))
                .as((eventId, userId, actorType, humanPrincipalId, sessionId, commandText, envContext, ts) -> {
                    AuditHistoryDocument doc = new AuditHistoryDocument();
                    doc.setEventId(eventId);
                    doc.setUserId(userId);
                    doc.setActorType(actorType);
                    doc.setHumanPrincipalId(humanPrincipalId);
                    doc.setSessionId(sessionId);
                    doc.setCommandText(commandText);
                    doc.setEnvContext(envContext);
                    doc.setTimestamp(ts);
                    return doc;
                });

        return Combinators.combine(
                        base,
                        components,
                        excluded,
                        unitDoubles(),
                        Arbitraries.of("ALLOW", "ASK", "BLOCK"),
                        Arbitraries.of("TYPED", "PASTED", "UNKNOWN"),
                        Arbitraries.of("HOOK", "AUDIT", "CORRELATED"),
                        Arbitraries.of(true, false))
                .as((doc, comps, excl, score, action, origin, signal, intentPresent) -> {
                    doc.setComponents(comps);
                    doc.setExcludedComponents(new ArrayList<>(excl));
                    doc.setDivergenceScore(score);
                    doc.setCorrectiveAction(action);
                    doc.setInputOrigin(origin);
                    doc.setSignalSource(signal);
                    doc.setIntentPresent(intentPresent);
                    doc.setIntentSource(intentPresent ? "DECLARED" : "NONE");
                    doc.setReasonCode("THRESHOLD_" + action);
                    doc.setExplanation("generated explanation");
                    doc.setProfileState("ACTIVE");
                    doc.setRecordType("DECISION");
                    doc.setCwd("/home/user");
                    doc.setRepo("repo");
                    return doc;
                });
    }
}
