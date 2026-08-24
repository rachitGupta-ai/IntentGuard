package com.intentguard.persistence;

import java.util.ArrayList;
import java.util.List;

/**
 * Persisted Snapshot undo metadata for the {@code snapshots} collection (Req 15.2). A Snapshot is
 * captured before an ask/block-scored Command_Event proceeds (Req 15.1) and records where the
 * affected state was backed up so that an Administrator can later restore it (Req 15.3).
 *
 * <p>The {@code undoStrategy} is stored as its {@code name()} string
 * ({@code FILE_RESTORE}/{@code GIT_STASH}) to keep the POJO codec mapping simple and
 * forward-compatible. {@code undoneAt} is a nullable epoch-millis timestamp so the codec can
 * distinguish "never undone" from an actual undo time.
 *
 * <p>Mutable JavaBean shape with a no-arg constructor for the MongoDB POJO codec.
 */
public class SnapshotDocument {

    private String eventId;
    private long capturedAt;
    private List<String> targetPaths = new ArrayList<>();
    private String backupLocation;
    private String undoStrategy;
    private boolean undone;
    private Long undoneAt;

    public SnapshotDocument() {
    }

    public String getEventId() {
        return eventId;
    }

    public void setEventId(String eventId) {
        this.eventId = eventId;
    }

    public long getCapturedAt() {
        return capturedAt;
    }

    public void setCapturedAt(long capturedAt) {
        this.capturedAt = capturedAt;
    }

    public List<String> getTargetPaths() {
        return targetPaths;
    }

    public void setTargetPaths(List<String> targetPaths) {
        this.targetPaths = targetPaths;
    }

    public String getBackupLocation() {
        return backupLocation;
    }

    public void setBackupLocation(String backupLocation) {
        this.backupLocation = backupLocation;
    }

    public String getUndoStrategy() {
        return undoStrategy;
    }

    public void setUndoStrategy(String undoStrategy) {
        this.undoStrategy = undoStrategy;
    }

    public boolean isUndone() {
        return undone;
    }

    public void setUndone(boolean undone) {
        this.undone = undone;
    }

    public Long getUndoneAt() {
        return undoneAt;
    }

    public void setUndoneAt(Long undoneAt) {
        this.undoneAt = undoneAt;
    }
}
