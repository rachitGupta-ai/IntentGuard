/**
 * Snapshot and Undo capability (Requirement 15, stretch). Behind the
 * {@code intentguard.snapshot.enabled} feature flag, {@link com.intentguard.snapshot.SnapshotService}
 * captures a Snapshot of affected state before an ask/block-scored Command_Event proceeds
 * (Req 15.1), persists the undo metadata via the {@code snapshots} repository (Req 15.2), and
 * restores the captured state with an Audit_History undo record on Administrator undo (Req 15.3).
 *
 * <p>The actual backup/restore mechanism is abstracted behind
 * {@link com.intentguard.snapshot.SnapshotStore} (default
 * {@link com.intentguard.snapshot.InMemorySnapshotStore}) so the capability is isolated and
 * testable without a live filesystem, keeping the core shippable if the stretch feature is dropped.
 */
package com.intentguard.snapshot;
