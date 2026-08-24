package com.intentguard.snapshot;

/**
 * How a captured Snapshot is restored on Administrator undo (Req 15.1, 15.3).
 *
 * <ul>
 *   <li>{@code FILE_RESTORE} - copy the affected files back from the backup location.</li>
 *   <li>{@code GIT_STASH} - restore via a git stash entry when the affected state is inside a
 *       repository working tree.</li>
 * </ul>
 */
public enum UndoStrategy {
    FILE_RESTORE,
    GIT_STASH
}
