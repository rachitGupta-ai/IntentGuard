package com.intentguard.persistence;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * A bounded write-ahead buffer in front of the {@link AuditHistoryRepository} so that no decision —
 * and in particular no {@code BLOCK} — is ever lost when the Datastore write transiently fails
 * (Req 11.1, 5.7, 8.3).
 *
 * <h2>Durability model</h2>
 * <p>{@link #write(AuditHistoryDocument)} first attempts to flush any previously-buffered records
 * (oldest first, preserving order), then attempts to persist the new record directly. If the direct
 * write throws (e.g. the Datastore is momentarily unreachable), the record is retained in an
 * in-memory buffer instead of being dropped, so the decision is durably <em>intended</em> before the
 * verdict is returned to the caller. A later {@link #write} or an explicit {@link #flush()} drains
 * the buffer to the Datastore once it recovers.
 *
 * <h2>Bounding</h2>
 * <p>The buffer is bounded by a configurable capacity ({@code intentguard.audit.buffer-capacity},
 * default {@value #DEFAULT_CAPACITY}). When it is full and a new record must be buffered, the oldest
 * buffered record is evicted to make room, with a warning logged. This keeps memory bounded under a
 * prolonged outage while still guaranteeing the most recent decisions (including recent blocks) are
 * retained for retry.
 *
 * <h2>Thread-safety</h2>
 * <p>All operations are guarded by a single monitor. The decision hot path calls {@link #write}
 * once per decision, so contention is minimal.
 */
@Component
public class AuditWriteAheadBuffer {

    private static final Logger log = LoggerFactory.getLogger(AuditWriteAheadBuffer.class);

    /** Default bounded capacity of the in-memory write-ahead buffer. */
    static final int DEFAULT_CAPACITY = 10_000;

    private final AuditHistoryRepository repository;
    private final int capacity;
    private final Deque<AuditHistoryDocument> pending = new ArrayDeque<>();
    private final Object lock = new Object();

    public AuditWriteAheadBuffer(
            AuditHistoryRepository repository,
            @Value("${intentguard.audit.buffer-capacity:10000}") int capacity) {
        this.repository = Objects.requireNonNull(repository, "repository must not be null");
        this.capacity = capacity > 0 ? capacity : DEFAULT_CAPACITY;
    }

    /**
     * Persist an audit record durably. Attempts to drain any buffered backlog first, then persist
     * {@code record}. On a transient Datastore failure the record is buffered (never lost) and this
     * method returns normally so the caller can still return its verdict.
     *
     * @param record the complete Audit_History record to persist (never {@code null})
     * @return {@code true} if the record was written straight through to the Datastore;
     *         {@code false} if it was retained in the buffer for later retry
     */
    public boolean write(AuditHistoryDocument record) {
        Objects.requireNonNull(record, "record must not be null");
        synchronized (lock) {
            drainPending();
            if (trySave(record)) {
                return true;
            }
            enqueue(record);
            log.warn(
                    "Datastore write failed; buffered audit record for event '{}' (buffered={})",
                    record.getEventId(),
                    pending.size());
            return false;
        }
    }

    /**
     * Attempt to drain all buffered records to the Datastore, oldest first. Records that still fail
     * to persist are left buffered in their original order.
     *
     * @return the number of records successfully flushed
     */
    public int flush() {
        synchronized (lock) {
            return drainPending();
        }
    }

    /** The number of records currently retained in the buffer awaiting retry. */
    public int bufferedCount() {
        synchronized (lock) {
            return pending.size();
        }
    }

    /** A snapshot copy of the currently buffered records, oldest first (for diagnostics/tests). */
    public List<AuditHistoryDocument> bufferedRecords() {
        synchronized (lock) {
            return new ArrayList<>(pending);
        }
    }

    // --- internals ----------------------------------------------------------------------------

    /** Drains buffered records oldest-first until one fails to persist or the buffer is empty. */
    private int drainPending() {
        int flushed = 0;
        while (!pending.isEmpty()) {
            AuditHistoryDocument head = pending.peekFirst();
            if (!trySave(head)) {
                break;
            }
            pending.pollFirst();
            flushed++;
        }
        return flushed;
    }

    private boolean trySave(AuditHistoryDocument record) {
        try {
            repository.save(record);
            return true;
        } catch (RuntimeException datastoreUnavailable) {
            return false;
        }
    }

    private void enqueue(AuditHistoryDocument record) {
        if (pending.size() >= capacity) {
            AuditHistoryDocument evicted = pending.pollFirst();
            log.warn(
                    "Audit write-ahead buffer full ({}); evicting oldest buffered record for event '{}'",
                    capacity,
                    evicted == null ? "?" : evicted.getEventId());
        }
        pending.addLast(record);
    }
}
