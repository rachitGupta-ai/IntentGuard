package com.intentguard.dualcontrol;

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

import com.intentguard.persistence.MongoConfig;
import com.intentguard.persistence.PendingApprovalDocument;
import com.intentguard.persistence.PendingApprovalRepository;
import com.mongodb.client.MongoDatabase;

/**
 * Verifies the {@code pending_approvals} persistence: that {@link PendingApprovalDocument}
 * round-trips through the configured POJO codec registry (including its nullable {@code approverId}
 * and {@code resolvedAt} fields and its status stored as a {@code name()} string), and that the
 * {@link PendingApprovalRepository} maps to and from the {@link PendingApproval} domain type and
 * supports the PENDING query used by the timeout sweep. No live MongoDB connection is required.
 */
class PendingApprovalRepositoryTest {

    private final CodecRegistry registry = new MongoConfig().intentGuardCodecRegistry();

    private <T> T roundTrip(T value, Class<T> type) {
        Codec<T> codec = registry.get(type);
        BsonDocument bson = new BsonDocument();
        codec.encode(new BsonDocumentWriter(bson), value, EncoderContext.builder().build());
        return codec.decode(new BsonDocumentReader(bson), DecoderContext.builder().build());
    }

    @Test
    void pendingApprovalDocumentRoundTripsWithNullableFields() {
        PendingApprovalDocument doc = new PendingApprovalDocument();
        doc.setEventId("evt-1");
        doc.setRequesterId("alice");
        doc.setApproverId(null);
        doc.setStatus(ApprovalStatus.PENDING.name());
        doc.setStepUpRequired(true);
        doc.setRaisedAt(1_710_000_000_000L);
        doc.setExpiresAt(1_710_000_300_000L);
        doc.setResolvedAt(null);

        PendingApprovalDocument out = roundTrip(doc, PendingApprovalDocument.class);

        assertThat(out.getEventId()).isEqualTo("evt-1");
        assertThat(out.getRequesterId()).isEqualTo("alice");
        assertThat(out.getApproverId()).isNull();
        assertThat(out.getStatus()).isEqualTo("PENDING");
        assertThat(out.isStepUpRequired()).isTrue();
        assertThat(out.getRaisedAt()).isEqualTo(1_710_000_000_000L);
        assertThat(out.getExpiresAt()).isEqualTo(1_710_000_300_000L);
        assertThat(out.getResolvedAt()).isNull();
    }

    @Test
    void confirmedApprovalDocumentRoundTripsWithResolvedFields() {
        PendingApprovalDocument doc = new PendingApprovalDocument();
        doc.setEventId("evt-2");
        doc.setRequesterId("alice");
        doc.setApproverId("bob");
        doc.setStatus(ApprovalStatus.CONFIRMED.name());
        doc.setStepUpRequired(false);
        doc.setRaisedAt(1_710_000_000_000L);
        doc.setExpiresAt(1_710_000_300_000L);
        doc.setResolvedAt(1_710_000_100_000L);

        PendingApprovalDocument out = roundTrip(doc, PendingApprovalDocument.class);

        assertThat(out.getApproverId()).isEqualTo("bob");
        assertThat(out.getStatus()).isEqualTo("CONFIRMED");
        assertThat(out.isStepUpRequired()).isFalse();
        assertThat(out.getResolvedAt()).isEqualTo(1_710_000_100_000L);
    }

    @Test
    void repositoryUpsertsFindsAndQueriesPendingViaDomainMapping() {
        InMemoryPendingApprovalRepository repo = new InMemoryPendingApprovalRepository();

        PendingApproval pending = new PendingApproval(
                "evt-1", "alice", null, ApprovalStatus.PENDING, true,
                1_000L, 301_000L, null);
        PendingApproval confirmed = new PendingApproval(
                "evt-2", "carol", "dave", ApprovalStatus.CONFIRMED, false,
                2_000L, 302_000L, 2_500L);
        repo.save(pending);
        repo.save(confirmed);

        Optional<PendingApproval> found = repo.findByEventId("evt-1");
        assertThat(found).isPresent();
        assertThat(found.get().requesterId()).isEqualTo("alice");
        assertThat(found.get().approverId()).isNull();
        assertThat(found.get().status()).isEqualTo(ApprovalStatus.PENDING);
        assertThat(found.get().resolvedAt()).isNull();

        List<PendingApproval> stillPending = repo.findPending();
        assertThat(stillPending).extracting(PendingApproval::eventId).containsExactly("evt-1");

        // Resolving the pending approval upserts by eventId and removes it from the PENDING query.
        repo.save(new PendingApproval(
                "evt-1", "alice", "bob", ApprovalStatus.CONFIRMED, true,
                1_000L, 301_000L, 1_200L));
        assertThat(repo.findPending()).isEmpty();
        assertThat(repo.findByEventId("evt-1").orElseThrow().approverId()).isEqualTo("bob");
        assertThat(repo.findByStatus(ApprovalStatus.CONFIRMED))
                .extracting(PendingApproval::eventId)
                .containsExactlyInAnyOrder("evt-1", "evt-2");
    }

    /**
     * Deterministic, DB-free {@link PendingApprovalRepository} backed by a map keyed on
     * {@code eventId}. Overrides every method so no live Mongo collection is touched; the superclass
     * constructor is satisfied with a mock {@link MongoDatabase} whose collection is never used.
     */
    private static final class InMemoryPendingApprovalRepository extends PendingApprovalRepository {

        private final Map<String, PendingApproval> byEventId = new HashMap<>();

        InMemoryPendingApprovalRepository() {
            super(mock(MongoDatabase.class));
        }

        @Override
        public void save(PendingApproval approval) {
            byEventId.put(approval.eventId(), approval);
        }

        @Override
        public Optional<PendingApproval> findByEventId(String eventId) {
            return Optional.ofNullable(byEventId.get(eventId));
        }

        @Override
        public List<PendingApproval> findByStatus(ApprovalStatus status) {
            return byEventId.values().stream()
                    .filter(a -> a.status() == status)
                    .sorted((a, b) -> Long.compare(a.raisedAt(), b.raisedAt()))
                    .toList();
        }
    }
}
