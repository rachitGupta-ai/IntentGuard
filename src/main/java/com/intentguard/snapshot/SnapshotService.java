package com.intentguard.snapshot;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.intentguard.domain.Actor;
import com.intentguard.domain.CommandEvent;
import com.intentguard.domain.CorrectiveAction;
import com.intentguard.persistence.AuditHistoryDocument;
import com.intentguard.persistence.AuditHistoryRepository;
import com.intentguard.persistence.SnapshotDocument;
import com.intentguard.persistence.SnapshotRepository;

/**
 * Snapshot and Undo capability (Requirement 15, stretch). Behind the
 * {@code intentguard.snapshot.enabled} feature flag (default {@code false}), it captures a Snapshot
 * of the affected state before an ask/block-scored Command_Event proceeds (Req 15.1), persists the
 * undo metadata to the Datastore (Req 15.2), and restores the captured state with an
 * Audit_History undo record on Administrator undo (Req 15.3).
 *
 * <p>The actual backup/restore mechanism is delegated to an injectable {@link SnapshotStore} so
 * this service holds only the flag/scoring/persistence logic and stays testable without touching a
 * live filesystem. The service is standalone: wiring it into the live pre-execution path is left
 * to the pipeline and is optional for the prototype.
 */
@Service
public class SnapshotService {

    private final SnapshotRepository snapshotRepository;
    private final AuditHistoryRepository auditHistoryRepository;
    private final SnapshotStore snapshotStore;
    private final boolean enabled;

    public SnapshotService(
            SnapshotRepository snapshotRepository,
            AuditHistoryRepository auditHistoryRepository,
            SnapshotStore snapshotStore,
            @Value("${intentguard.snapshot.enabled:false}") boolean enabled) {
        this.snapshotRepository = snapshotRepository;
        this.auditHistoryRepository = auditHistoryRepository;
        this.snapshotStore = snapshotStore;
        this.enabled = enabled;
    }

    /** Whether the Snapshot/Undo capability is enabled (Req 15.1). */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Captures a Snapshot of the affected state before a Command_Event proceeds, when the feature is
     * enabled AND the event scored in the ask/block range (Req 15.1). The undo metadata is persisted
     * (Req 15.2). Returns the captured Snapshot, or {@link Optional#empty()} when the feature is
     * disabled or the action is {@code ALLOW} (no capture).
     */
    public Optional<SnapshotDocument> captureIfNeeded(CommandEvent event, CorrectiveAction action) {
        if (!enabled) {
            return Optional.empty();
        }
        if (action != CorrectiveAction.ASK && action != CorrectiveAction.BLOCK) {
            return Optional.empty();
        }

        List<String> targetPaths = deriveTargetPaths(event);
        UndoStrategy strategy = event.repo() != null ? UndoStrategy.GIT_STASH : UndoStrategy.FILE_RESTORE;
        String backupLocation = snapshotStore.capture(event.eventId(), targetPaths, strategy);

        SnapshotDocument snapshot = new SnapshotDocument();
        snapshot.setEventId(event.eventId());
        snapshot.setCapturedAt(now());
        snapshot.setTargetPaths(targetPaths);
        snapshot.setBackupLocation(backupLocation);
        snapshot.setUndoStrategy(strategy.name());
        snapshot.setUndone(false);
        snapshot.setUndoneAt(null);

        snapshotRepository.save(snapshot);
        return Optional.of(snapshot);
    }

    /**
     * Restores the captured state for a Command_Event on Administrator undo (Req 15.3): invokes the
     * restore operation, marks the Snapshot undone, and records an {@code UNDO} record in the
     * Audit_History. Returns the undone Snapshot, or {@link Optional#empty()} when no Snapshot exists
     * for the event.
     */
    public Optional<SnapshotDocument> restore(String eventId, Actor administrator) {
        Optional<SnapshotDocument> found = snapshotRepository.findByEventId(eventId);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        SnapshotDocument snapshot = found.get();

        snapshotStore.restore(snapshot);

        long undoneAt = now();
        Optional<SnapshotDocument> undone = snapshotRepository.markUndone(eventId, undoneAt);
        SnapshotDocument result = undone.orElse(snapshot);

        auditHistoryRepository.save(buildUndoRecord(snapshot, administrator, undoneAt));
        return Optional.of(result);
    }

    /**
     * Derives the affected paths for a Command_Event. For the prototype this is the working
     * directory plus any path-like tokens in the command text; if none are present the working
     * directory alone is used.
     */
    private static List<String> deriveTargetPaths(CommandEvent event) {
        List<String> paths = new ArrayList<>();
        if (event.cwd() != null && !event.cwd().isBlank()) {
            paths.add(event.cwd());
        }
        for (String token : event.commandText().split("\\s+")) {
            if (looksLikePath(token) && !paths.contains(token)) {
                paths.add(token);
            }
        }
        if (paths.isEmpty()) {
            paths.add(event.cwd() == null ? "." : event.cwd());
        }
        return paths;
    }

    private static boolean looksLikePath(String token) {
        return token.startsWith("/") || token.startsWith("./") || token.startsWith("../")
                || token.startsWith("~/");
    }

    private AuditHistoryDocument buildUndoRecord(
            SnapshotDocument snapshot, Actor administrator, long undoneAt) {
        AuditHistoryDocument record = new AuditHistoryDocument();
        record.setEventId(snapshot.getEventId());
        record.setUserId(administrator == null ? null : administrator.userId());
        record.setActorType(administrator == null ? null : administrator.type().name());
        record.setTimestamp(undoneAt);
        record.setRecordType("UNDO");
        record.setReasonCode("ADMIN_UNDO");
        record.setExplanation("Administrator undo restored the captured Snapshot ("
                + snapshot.getUndoStrategy() + ") from " + snapshot.getBackupLocation() + ".");
        return record;
    }

    private long now() {
        return System.currentTimeMillis();
    }
}
