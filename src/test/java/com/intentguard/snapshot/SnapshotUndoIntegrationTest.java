package com.intentguard.snapshot;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
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
 * Integration-level coverage of the Snapshot/Undo capability (Requirement 15) exercising the full
 * capture -&gt; persist -&gt; restore -&gt; audit flow across <b>real files on disk</b>.
 *
 * <p>Unlike {@code SnapshotServiceTest} (which uses the filesystem-free {@link InMemorySnapshotStore}
 * fake to unit-test the flag/scoring/persistence branches), this test wires {@link SnapshotService}
 * with a small filesystem-backed {@link SnapshotStore} that copies the affected files into a
 * {@link TempDir} backup and restores them. That genuinely verifies:
 *
 * <ul>
 *   <li>Req 15.1 - a Snapshot of the affected state is captured <em>before</em> the ask/block-scored
 *       Command_Event would proceed (the backup exists and still holds the original content while
 *       the live file is later mutated by the "executed" destructive command).</li>
 *   <li>Req 15.2 - the Snapshot undo metadata is persisted and re-readable
 *       ({@code targetPaths}/{@code backupLocation}/{@code undoStrategy}/{@code undone=false}).</li>
 *   <li>Req 15.3 - on Administrator undo the original file content is restored and an {@code UNDO}
 *       record is written to the Audit_History.</li>
 * </ul>
 *
 * <p>No live MongoDB is required: the repositories are in-memory fakes subclassing the real
 * repositories with a mocked {@link MongoDatabase}, matching the fakes pattern used across the
 * codebase. The store is filesystem-backed only inside this test and does not touch main source.
 */
class SnapshotUndoIntegrationTest {

    private static final String ORIGINAL_CONTENT = "important production config\nkey=value\n";
    private static final String MUTATED_CONTENT = "";

    private final FakeSnapshotRepository snapshots = new FakeSnapshotRepository();
    private final FakeAuditHistoryRepository audit = new FakeAuditHistoryRepository();

    @Test
    void endToEndCapturePersistRestoreAcrossRealFiles(@TempDir Path workspace, @TempDir Path backupRoot)
            throws IOException {
        // Arrange: a real file with original content that the destructive command targets.
        Path targetFile = workspace.resolve("config.yaml");
        Files.writeString(targetFile, ORIGINAL_CONTENT);

        FilesystemSnapshotStore store = new FilesystemSnapshotStore(backupRoot);
        SnapshotService service = new SnapshotService(snapshots, audit, store, true);
        CommandEvent event = destructiveEvent(workspace.toString(), targetFile.toString());

        // --- Req 15.1: pre-execution capture ---
        Optional<SnapshotDocument> captured = service.captureIfNeeded(event, CorrectiveAction.BLOCK);

        assertThat(captured).isPresent();
        SnapshotDocument snapshot = captured.get();
        assertThat(snapshot.getTargetPaths()).contains(targetFile.toString());
        assertThat(snapshot.getBackupLocation()).isNotBlank();
        assertThat(snapshot.getUndoStrategy()).isEqualTo(UndoStrategy.FILE_RESTORE.name());
        // The affected file was backed up before the command proceeds, and the backup holds the
        // original state even though nothing has executed yet.
        assertThat(store.backupContentFor(event.eventId(), targetFile)).isEqualTo(ORIGINAL_CONTENT);
        assertThat(Files.readString(targetFile)).isEqualTo(ORIGINAL_CONTENT);

        // --- Req 15.2: undo metadata persisted and re-readable ---
        Optional<SnapshotDocument> persisted = snapshots.findByEventId(event.eventId());
        assertThat(persisted).isPresent();
        assertThat(persisted.get().getTargetPaths()).contains(targetFile.toString());
        assertThat(persisted.get().getBackupLocation()).isEqualTo(snapshot.getBackupLocation());
        assertThat(persisted.get().getUndoStrategy()).isEqualTo(UndoStrategy.FILE_RESTORE.name());
        assertThat(persisted.get().isUndone()).isFalse();
        assertThat(persisted.get().getUndoneAt()).isNull();

        // Now the destructive command "executes" and mutates the live file.
        Files.writeString(targetFile, MUTATED_CONTENT);
        assertThat(Files.readString(targetFile)).isEqualTo(MUTATED_CONTENT);

        // --- Req 15.3: Administrator undo restores state and records an UNDO audit entry ---
        Actor administrator = Actor.human("root-admin");
        Optional<SnapshotDocument> restored = service.restore(event.eventId(), administrator);

        assertThat(restored).isPresent();
        assertThat(restored.get().isUndone()).isTrue();
        assertThat(restored.get().getUndoneAt()).isNotNull();

        // The real file content is restored from the pre-execution backup.
        assertThat(Files.readString(targetFile)).isEqualTo(ORIGINAL_CONTENT);

        // The persisted Snapshot reflects the undo.
        assertThat(snapshots.findByEventId(event.eventId()).orElseThrow().isUndone()).isTrue();

        // An UNDO record was appended to the Audit_History.
        List<AuditHistoryDocument> records = audit.saved();
        assertThat(records).hasSize(1);
        AuditHistoryDocument undoRecord = records.get(0);
        assertThat(undoRecord.getRecordType()).isEqualTo("UNDO");
        assertThat(undoRecord.getEventId()).isEqualTo(event.eventId());
        assertThat(undoRecord.getUserId()).isEqualTo("root-admin");
        assertThat(undoRecord.getReasonCode()).isEqualTo("ADMIN_UNDO");
        assertThat(undoRecord.getExplanation()).isNotBlank();
    }

    @Test
    void restoreRecoversMultipleAffectedFilesFromBackup(@TempDir Path workspace, @TempDir Path backupRoot)
            throws IOException {
        Path fileA = workspace.resolve("a.txt");
        Path fileB = workspace.resolve("b.txt");
        Files.writeString(fileA, "content-A");
        Files.writeString(fileB, "content-B");

        FilesystemSnapshotStore store = new FilesystemSnapshotStore(backupRoot);
        SnapshotService service = new SnapshotService(snapshots, audit, store, true);
        CommandEvent event = new CommandEvent(
                "evt-multi",
                Actor.human("alice"),
                null,
                "rm -f " + fileA + " " + fileB,
                workspace.toString(),
                null,
                Map.of(),
                1_000L,
                InputOrigin.TYPED,
                SignalSource.HOOK,
                IntentSource.NONE,
                null);

        service.captureIfNeeded(event, CorrectiveAction.ASK);

        // Both files are destroyed by the executing command.
        Files.delete(fileA);
        Files.delete(fileB);
        assertThat(Files.exists(fileA)).isFalse();
        assertThat(Files.exists(fileB)).isFalse();

        service.restore("evt-multi", Actor.human("root-admin"));

        assertThat(Files.readString(fileA)).isEqualTo("content-A");
        assertThat(Files.readString(fileB)).isEqualTo("content-B");
        assertThat(audit.saved()).extracting(AuditHistoryDocument::getRecordType).containsExactly("UNDO");
    }

    private static CommandEvent destructiveEvent(String cwd, String targetPath) {
        return new CommandEvent(
                "evt-1",
                Actor.human("alice"),
                null,
                "rm -f " + targetPath,
                cwd,
                null,
                Map.of(),
                1_000L,
                InputOrigin.TYPED,
                SignalSource.HOOK,
                IntentSource.NONE,
                null);
    }

    /**
     * Filesystem-backed {@link SnapshotStore} used only by this integration test. On capture it
     * copies each affected <em>regular file</em> into a per-event backup directory under the temp
     * backup root; on restore it copies the backed-up files back to their original locations. This
     * exercises the genuine "capture pre-execution state" / "restore captured state" behaviour that
     * the in-memory production default only simulates.
     */
    private static final class FilesystemSnapshotStore implements SnapshotStore {

        private final Path backupRoot;
        /** eventId -&gt; list of {original, backup} path pairs captured for that event. */
        private final Map<String, List<Path[]>> backups = new HashMap<>();

        FilesystemSnapshotStore(Path backupRoot) {
            this.backupRoot = backupRoot;
        }

        @Override
        public String capture(String eventId, List<String> targetPaths, UndoStrategy strategy) {
            Path eventBackupDir = backupRoot.resolve(eventId);
            try {
                Files.createDirectories(eventBackupDir);
                List<Path[]> mapping = new ArrayList<>();
                int index = 0;
                for (String pathText : targetPaths) {
                    Path original = Path.of(pathText);
                    if (Files.isRegularFile(original)) {
                        Path backup = eventBackupDir.resolve(index++ + "-" + original.getFileName());
                        Files.copy(original, backup, StandardCopyOption.REPLACE_EXISTING);
                        mapping.add(new Path[] {original, backup});
                    }
                }
                backups.put(eventId, mapping);
                return eventBackupDir.toString();
            } catch (IOException e) {
                throw new UncheckedIOException("capture failed for " + eventId, e);
            }
        }

        @Override
        public void restore(SnapshotDocument snapshot) {
            List<Path[]> mapping = backups.get(snapshot.getEventId());
            if (mapping == null) {
                return;
            }
            try {
                for (Path[] pair : mapping) {
                    Files.copy(pair[1], pair[0], StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (IOException e) {
                throw new UncheckedIOException("restore failed for " + snapshot.getEventId(), e);
            }
        }

        /** Reads the backed-up content captured for {@code original} under {@code eventId}. */
        String backupContentFor(String eventId, Path original) throws IOException {
            for (Path[] pair : backups.getOrDefault(eventId, List.of())) {
                if (pair[0].equals(original)) {
                    return Files.readString(pair[1]);
                }
            }
            throw new IllegalStateException("no backup captured for " + original);
        }
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
    }

    /** In-memory {@link AuditHistoryRepository} recording saved records. */
    private static final class FakeAuditHistoryRepository extends AuditHistoryRepository {
        private final List<AuditHistoryDocument> records = new ArrayList<>();

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
