package com.intentguard.snapshot;

import java.util.List;

import com.intentguard.persistence.SnapshotDocument;

/**
 * Abstraction over the actual filesystem/VCS operation that backs up and restores affected state
 * for the Snapshot/Undo capability (Req 15.1, 15.3).
 *
 * <p>The real capture/restore mechanism (copying files to a backup directory, creating/applying a
 * git stash) is deliberately hidden behind this interface so that {@link SnapshotService} contains
 * only the flag/scoring/persistence logic and can be unit-tested with an in-memory fake instead of
 * touching a live filesystem. The default bean is {@link InMemorySnapshotStore}.
 */
public interface SnapshotStore {

    /**
     * Captures a backup of {@code targetPaths} for the given event before the command proceeds and
     * returns an opaque backup-location identifier recorded in the Snapshot undo metadata.
     *
     * @param eventId     the Command_Event being snapshotted
     * @param targetPaths the affected paths to back up
     * @param strategy    the restore strategy to use later
     * @return the backup location descriptor (e.g. a directory path or stash ref)
     */
    String capture(String eventId, List<String> targetPaths, UndoStrategy strategy);

    /**
     * Restores the state captured for the given Snapshot (Req 15.3). Invoked on Administrator undo
     * before the Snapshot is marked undone and the undo is recorded in the Audit_History.
     */
    void restore(SnapshotDocument snapshot);
}
