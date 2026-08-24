package com.intentguard.snapshot;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.mock;

import com.intentguard.domain.Actor;
import com.intentguard.domain.CommandEvent;
import com.intentguard.domain.CorrectiveAction;
import com.intentguard.domain.InputOrigin;
import com.intentguard.domain.IntentSource;
import com.intentguard.domain.SignalSource;
import com.intentguard.persistence.AuditHistoryDocument;
import com.intentguard.persistence.AuditHistoryRepository;
import com.intentguard.persistence.SnapshotDocument;
import com.intentguard.persistence.SnapshotRepository;
import com.mongodb.client.MongoDatabase;

/**
 * Unit tests for {@link SnapshotService} covering the Snapshot/Undo capability (Requirement 15,
 * stretch): flag gating (Req 15.1), capture with persisted undo metadata (Req 15.1, 15.2), and
 * restore-with-audit-record on Administrator undo (Req 15.3).
 *
 * <p>No live MongoDB is used: the repositories are in-memory fakes subclassing the real
 * repositories with a mocked {@link MongoDatabase}, and the backup/restore operation is a
 * {@link SnapshotStore} fake, matching the fakes pattern used across the codebase.
 */
class SnapshotServiceTest {

    private final FakeSnapshotRepository snapshots = new FakeSnapshotRepository();
    private final FakeAuditHistoryRepository audit = new FakeAuditHistoryRepository();
    private final InMemorySnapshotStore store = new InMemorySnapshotStore();

    private SnapshotService serviceWithFlag(boolean enabled) {
        return new SnapshotService(snapshots, audit, store, enabled);
    }

    @Test
    void flagOffCapturesNoSnapshotEvenForBlock() {
        SnapshotService service = serviceWithFlag(false);

        Optional<SnapshotDocument> captured =
                service.captureIfNeeded(event("rm -rf /tmp/x"), CorrectiveAction.BLOCK);

        assertThat(service.isEnabled()).isFalse();
        assertThat(captured).isEmpty();
        assertThat(snapshots.all()).isEmpty();
        assertThat(store.hasCapture("evt-1")).isFalse();
    }

    @Test
    void flagOnAskCapturesSnapshotWithPersistedUndoMetadata() {
        SnapshotService service = serviceWithFlag(true);

        Optional<SnapshotDocument> captured =
                service.captureIfNeeded(event("rm -rf /tmp/x"), CorrectiveAction.ASK);

        assertThat(captured).isPresent();
        SnapshotDocument snapshot = captured.get();
        assertThat(snapshot.getEventId()).isEqualTo("evt-1");
        assertThat(snapshot.getTargetPaths()).contains("/home/alice", "/tmp/x");
        assertThat(snapshot.getBackupLocation()).isNotBlank();
        assertThat(snapshot.getUndoStrategy()).isEqualTo(UndoStrategy.FILE_RESTORE.name());
        assertThat(snapshot.isUndone()).isFalse();
        assertThat(snapshot.getUndoneAt()).isNull();
        assertThat(snapshot.getCapturedAt()).isPositive();

        // Undo metadata is persisted (Req 15.2) and the backup was captured.
        assertThat(snapshots.findByEventId("evt-1")).isPresent();
        assertThat(store.hasCapture("evt-1")).isTrue();
    }

    @Test
    void flagOnBlockInsideRepoUsesGitStashStrategy() {
        SnapshotService service = serviceWithFlag(true);

        Optional<SnapshotDocument> captured =
                service.captureIfNeeded(eventInRepo("git reset --hard"), CorrectiveAction.BLOCK);

        assertThat(captured).isPresent();
        assertThat(captured.get().getUndoStrategy()).isEqualTo(UndoStrategy.GIT_STASH.name());
    }

    @Test
    void flagOnAllowCapturesNoSnapshot() {
        SnapshotService service = serviceWithFlag(true);

        Optional<SnapshotDocument> captured =
                service.captureIfNeeded(event("ls -la"), CorrectiveAction.ALLOW);

        assertThat(captured).isEmpty();
        assertThat(snapshots.all()).isEmpty();
        assertThat(store.hasCapture("evt-1")).isFalse();
    }

    @Test
    void undoRestoresStateMarksSnapshotUndoneAndWritesUndoAuditRecord() {
        SnapshotService service = serviceWithFlag(true);
        service.captureIfNeeded(event("rm -rf /tmp/x"), CorrectiveAction.BLOCK);

        Actor admin = Actor.human("root-admin");
        Optional<SnapshotDocument> restored = service.restore("evt-1", admin);

        // Snapshot is marked undone (Req 15.3).
        assertThat(restored).isPresent();
        assertThat(restored.get().isUndone()).isTrue();
        assertThat(restored.get().getUndoneAt()).isNotNull();
        assertThat(snapshots.findByEventId("evt-1").orElseThrow().isUndone()).isTrue();

        // Restore operation was invoked.
        assertThat(store.wasRestored("evt-1")).isTrue();

        // An UNDO record was written to the Audit_History (Req 15.3).
        List<AuditHistoryDocument> records = audit.saved();
        assertThat(records).hasSize(1);
        AuditHistoryDocument undoRecord = records.get(0);
        assertThat(undoRecord.getRecordType()).isEqualTo("UNDO");
        assertThat(undoRecord.getEventId()).isEqualTo("evt-1");
        assertThat(undoRecord.getUserId()).isEqualTo("root-admin");
        assertThat(undoRecord.getExplanation()).isNotBlank();
    }

    @Test
    void undoWithNoSnapshotIsNoOpAndWritesNoAuditRecord() {
        SnapshotService service = serviceWithFlag(true);

        Optional<SnapshotDocument> restored = service.restore("missing-event", Actor.human("root-admin"));

        assertThat(restored).isEmpty();
        assertThat(audit.saved()).isEmpty();
        assertThat(store.wasRestored("missing-event")).isFalse();
    }

    private static CommandEvent event(String commandText) {
        return new CommandEvent(
                "evt-1",
                Actor.human("alice"),
                null,
                commandText,
                "/home/alice",
                null,
                Map.of(),
                1_000L,
                InputOrigin.TYPED,
                SignalSource.HOOK,
                IntentSource.NONE,
                null);
    }

    private static CommandEvent eventInRepo(String commandText) {
        return new CommandEvent(
                "evt-1",
                Actor.human("alice"),
                null,
                commandText,
                "/home/alice/repo",
                "repo",
                Map.of(),
                1_000L,
                InputOrigin.TYPED,
                SignalSource.HOOK,
                IntentSource.NONE,
                null);
    }

    /** In-memory {@link SnapshotRepository} backed by a map keyed on eventId. */
    private static final class FakeSnapshotRepository extends SnapshotRepository {
        private final Map<String, SnapshotDocument> byEvent = new HashMap<>();

        FakeSnapshotRepository() {
            super(mock(MongoDatabase.class));
        }

        @Override
        public void save(SnapshotDocument snapshot) {
            byEvent.put(snapshot.getEventId(), snapshot);
        }

        @Override
        public Optional<SnapshotDocument> findByEventId(String eventId) {
            return Optional.ofNullable(byEvent.get(eventId));
        }

        @Override
        public Optional<SnapshotDocument> markUndone(String eventId, long undoneAtMs) {
            SnapshotDocument existing = byEvent.get(eventId);
            if (existing == null) {
                return Optional.empty();
            }
            existing.setUndone(true);
            existing.setUndoneAt(undoneAtMs);
            return Optional.of(existing);
        }

        Map<String, SnapshotDocument> all() {
            return byEvent;
        }
    }

    /** In-memory {@link AuditHistoryRepository} recording saved records. */
    private static final class FakeAuditHistoryRepository extends AuditHistoryRepository {
        private final java.util.List<AuditHistoryDocument> records = new java.util.ArrayList<>();

        FakeAuditHistoryRepository() {
            super(mock(MongoDatabase.class));
        }

        @Override
        public void save(AuditHistoryDocument record) {
            records.add(record);
        }

        List<AuditHistoryDocument> saved() {
            return records;
        }
    }
}
