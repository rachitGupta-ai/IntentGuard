package com.intentguard.persistence;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.mock;

import com.mongodb.client.MongoDatabase;

/**
 * Unit tests for the bounded {@link AuditWriteAheadBuffer} that guarantees no decision (especially
 * no block) is lost on a transient Datastore write failure (Req 11.1, 13.2).
 */
class AuditWriteAheadBufferTest {

    @Test
    void writesStraightThroughWhenDatastoreIsHealthy() {
        FakeRepo repo = new FakeRepo();
        AuditWriteAheadBuffer buffer = new AuditWriteAheadBuffer(repo, 10);

        boolean persisted = buffer.write(record("e1"));

        assertThat(persisted).isTrue();
        assertThat(buffer.bufferedCount()).isZero();
        assertThat(repo.saved).extracting(AuditHistoryDocument::getEventId).containsExactly("e1");
    }

    @Test
    void retainsRecordInBufferOnTransientFailureAndFlushesOnRecovery() {
        FakeRepo repo = new FakeRepo();
        AuditWriteAheadBuffer buffer = new AuditWriteAheadBuffer(repo, 10);

        repo.failing = true;
        boolean persisted = buffer.write(record("blocked-event"));

        assertThat(persisted).isFalse();
        assertThat(buffer.bufferedCount()).isEqualTo(1);
        assertThat(repo.saved).isEmpty();

        repo.failing = false;
        int flushed = buffer.flush();

        assertThat(flushed).isEqualTo(1);
        assertThat(buffer.bufferedCount()).isZero();
        assertThat(repo.saved).extracting(AuditHistoryDocument::getEventId).containsExactly("blocked-event");
    }

    @Test
    void preservesInsertionOrderWhenDrainingBufferedBacklog() {
        FakeRepo repo = new FakeRepo();
        AuditWriteAheadBuffer buffer = new AuditWriteAheadBuffer(repo, 10);

        repo.failing = true;
        buffer.write(record("a"));
        buffer.write(record("b"));
        assertThat(buffer.bufferedCount()).isEqualTo(2);

        // A later write while healthy drains the backlog first, then persists the new record last.
        repo.failing = false;
        buffer.write(record("c"));

        assertThat(buffer.bufferedCount()).isZero();
        assertThat(repo.saved).extracting(AuditHistoryDocument::getEventId).containsExactly("a", "b", "c");
    }

    @Test
    void boundedCapacityEvictsOldestButRetainsNewestUnderProlongedOutage() {
        FakeRepo repo = new FakeRepo();
        AuditWriteAheadBuffer buffer = new AuditWriteAheadBuffer(repo, 2);

        repo.failing = true;
        buffer.write(record("old"));
        buffer.write(record("mid"));
        buffer.write(record("new")); // capacity 2 -> "old" evicted

        assertThat(buffer.bufferedCount()).isEqualTo(2);
        assertThat(buffer.bufferedRecords())
                .extracting(AuditHistoryDocument::getEventId)
                .containsExactly("mid", "new");
    }

    private static AuditHistoryDocument record(String eventId) {
        AuditHistoryDocument doc = new AuditHistoryDocument();
        doc.setEventId(eventId);
        doc.setCorrectiveAction("BLOCK");
        doc.setRecordType("DECISION");
        return doc;
    }

    /** In-memory repository with a controllable transient-failure toggle. */
    private static final class FakeRepo extends AuditHistoryRepository {
        final List<AuditHistoryDocument> saved = new ArrayList<>();
        boolean failing;

        FakeRepo() {
            super(mock(MongoDatabase.class));
        }

        @Override
        public void save(AuditHistoryDocument record) {
            if (failing) {
                throw new IllegalStateException("simulated outage");
            }
            saved.add(record);
        }
    }
}
