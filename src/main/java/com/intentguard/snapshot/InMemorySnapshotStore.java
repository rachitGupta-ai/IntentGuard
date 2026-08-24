package com.intentguard.snapshot;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

import com.intentguard.persistence.SnapshotDocument;

/**
 * Default, filesystem-free {@link SnapshotStore} used by the prototype. It records what would be
 * captured/restored in memory and synthesizes a deterministic backup-location descriptor, so the
 * Snapshot/Undo logic (flag gating, scoring range, persistence, undo audit) is exercisable without
 * a live filesystem or git repository.
 *
 * <p>A production deployment would replace this bean with an implementation that actually copies
 * the affected files to a backup directory (FILE_RESTORE) or creates/applies a git stash
 * (GIT_STASH).
 */
@Component
public class InMemorySnapshotStore implements SnapshotStore {

    /** Backup location descriptor per captured event, for inspection/restore. */
    private final Map<String, List<String>> capturedPaths = new ConcurrentHashMap<>();
    /** Events for which {@link #restore(SnapshotDocument)} has been invoked. */
    private final Set<String> restoredEvents = ConcurrentHashMap.newKeySet();

    @Override
    public String capture(String eventId, List<String> targetPaths, UndoStrategy strategy) {
        capturedPaths.put(eventId, List.copyOf(targetPaths));
        return "intentguard-backup/" + strategy.name().toLowerCase() + "/" + eventId;
    }

    @Override
    public void restore(SnapshotDocument snapshot) {
        restoredEvents.add(snapshot.getEventId());
    }

    /** True if a backup was captured for the given event. */
    public boolean hasCapture(String eventId) {
        return capturedPaths.containsKey(eventId);
    }

    /** True if {@link #restore(SnapshotDocument)} was invoked for the given event. */
    public boolean wasRestored(String eventId) {
        return restoredEvents.contains(eventId);
    }
}
