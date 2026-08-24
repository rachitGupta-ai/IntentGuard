package com.intentguard.watchdog;

import java.time.Clock;
import java.util.Objects;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.intentguard.decision.TamperClassifier;
import com.intentguard.domain.Actor;
import com.intentguard.domain.CommandEvent;
import com.intentguard.domain.IntentSource;
import com.intentguard.persistence.AuditHistoryDocument;
import com.intentguard.persistence.AuditHistoryRepository;

/**
 * Reference-monitor self-defense over control requests (Req 1.3, 1.6).
 *
 * <p>The Enforcement_Engine runs under a dedicated service account distinct from the monitored
 * users. The operating system is the ultimate authority that stops a monitored user from
 * signalling, reconfiguring, or terminating the engine; this component implements the engine-side
 * <em>logic</em> of that guarantee so it is unit-testable without an OS deployment:
 *
 * <ul>
 *   <li>{@link #handleControlRequest} rejects a stop / pause / reconfigure attempt from an actor
 *       that lacks privilege over the engine, leaves the engine's configuration and process state
 *       unchanged, and records the rejected attempt in the Audit_History (Req 1.3).</li>
 *   <li>{@link #handleSocketRequest} rejects any request arriving over the local IPC socket that
 *       targets IntentGuard configuration, process state, or the Datastore, recording the rejected
 *       attempt (Req 1.6 self-defense complement). Tamper <em>classification</em> is delegated to
 *       the shared {@link TamperClassifier} so this does not duplicate the Decision Engine's
 *       tamper-override scoring path.</li>
 * </ul>
 *
 * <p>Process/configuration state is modelled here as two in-memory flags ({@code running},
 * {@code paused}) so that "preserve state unchanged" is directly observable in tests. A rejected
 * request never touches them; a privileged operation (privilege check passed by the caller) is
 * allowed to apply. Timestamps come from a {@link Clock} that defaults to {@link Clock#systemUTC()}
 * and is overridable in tests, mirroring the other engine services.
 *
 * <p>Thread-safety: state changes and the reject-and-record step are guarded by a monitor so a
 * rejection can never race with a state mutation.
 */
@Service
public class SelfDefenseGuard {

    /** Audit_History record type for a rejected control/tamper attempt (Req 1.3, 1.6). */
    static final String RECORD_TYPE_REJECTED_TAMPER = "REJECTED_TAMPER";

    /** Reason code for an unprivileged stop/pause/reconfigure attempt (Req 1.3). */
    static final String REASON_UNPRIVILEGED_CONTROL_REJECTED = "UNPRIVILEGED_CONTROL_REJECTED";

    /** Reason code for a socket request targeting engine config/process/Datastore (Req 1.6). */
    static final String REASON_TAMPER_SOCKET_REQUEST_REJECTED = "TAMPER_SOCKET_REQUEST_REJECTED";

    private final AuditHistoryRepository auditHistory;
    private final TamperClassifier tamperClassifier;
    private volatile Clock clock = Clock.systemUTC();

    private final Object stateLock = new Object();
    private boolean running = true;
    private boolean paused = false;

    public SelfDefenseGuard(AuditHistoryRepository auditHistory, TamperClassifier tamperClassifier) {
        this.auditHistory = Objects.requireNonNull(auditHistory, "auditHistory must not be null");
        this.tamperClassifier = Objects.requireNonNull(tamperClassifier, "tamperClassifier must not be null");
    }

    /** Test seam: overrides the clock used to stamp rejected-attempt records. */
    void setClock(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    /**
     * Handles a stop / pause / reconfigure control request against the Enforcement_Engine.
     *
     * <p>If the requesting actor lacks privilege over the engine (the case for every monitored
     * user), the request is rejected: the engine's configuration and process state are left
     * unchanged and the attempt is recorded in the Audit_History (Req 1.3). A privileged request
     * (privilege check performed by the caller / OS layer) is applied to the modelled process
     * state.
     *
     * @param actor            the actor issuing the control request
     * @param operation        the control operation attempted
     * @param actorHasPrivilege whether the actor holds privilege over the engine (a monitored user
     *                          never does)
     * @throws ControlRequestRejectedException if the actor lacks privilege; state is preserved and
     *                                          the attempt has been recorded before this is thrown
     */
    public void handleControlRequest(Actor actor, ControlOperation operation, boolean actorHasPrivilege) {
        Objects.requireNonNull(actor, "actor must not be null");
        Objects.requireNonNull(operation, "operation must not be null");
        synchronized (stateLock) {
            if (!actorHasPrivilege) {
                // Reject and record BEFORE any state change, so state is preserved unchanged.
                recordRejectedAttempt(
                        actor,
                        "control request: " + operation.name(),
                        REASON_UNPRIVILEGED_CONTROL_REJECTED);
                throw new ControlRequestRejectedException(
                        "Unprivileged actor '" + actor.userId() + "' may not " + operation
                                + " the Enforcement_Engine; request rejected and recorded (Req 1.3)");
            }
            applyPrivileged(operation);
        }
    }

    /**
     * Handles a request arriving over the local IPC socket. If it targets IntentGuard configuration,
     * process state, or the Datastore (as classified by {@link TamperClassifier}), it is rejected
     * and recorded (Req 1.6). Otherwise it is a benign request and this method returns normally.
     *
     * @param event the Command_Event carrying the socket request
     * @param actor the actor that issued the request
     * @throws ControlRequestRejectedException if the request targets the engine's control surface
     */
    public void handleSocketRequest(CommandEvent event, Actor actor) {
        Objects.requireNonNull(event, "event must not be null");
        Objects.requireNonNull(actor, "actor must not be null");
        if (tamperClassifier.isTamperAttempt(event)) {
            recordRejectedAttempt(
                    actor,
                    event.commandText(),
                    REASON_TAMPER_SOCKET_REQUEST_REJECTED);
            throw new ControlRequestRejectedException(
                    "Socket request from '" + actor.userId()
                            + "' targets IntentGuard config/process/Datastore; rejected and recorded (Req 1.6)");
        }
    }

    /** Whether the modelled engine process is running (never changed by a rejected request). */
    public boolean isRunning() {
        synchronized (stateLock) {
            return running;
        }
    }

    /** Whether enforcement is paused (never changed by a rejected request). */
    public boolean isPaused() {
        synchronized (stateLock) {
            return paused;
        }
    }

    private void applyPrivileged(ControlOperation operation) {
        switch (operation) {
            case STOP -> running = false;
            case PAUSE -> paused = true;
            case RECONFIGURE -> {
                // A privileged reconfigure is applied by the Threshold_Configuration service; the
                // modelled process state is unaffected here.
            }
        }
    }

    private void recordRejectedAttempt(Actor actor, String attempt, String reasonCode) {
        AuditHistoryDocument record = new AuditHistoryDocument();
        record.setEventId(UUID.randomUUID().toString());
        record.setUserId(actor.userId());
        record.setActorType(actor.type().name());
        record.setHumanPrincipalId(actor.humanPrincipalId());
        record.setCommandText(attempt);
        record.setTimestamp(clock.millis());
        record.setRecordType(RECORD_TYPE_REJECTED_TAMPER);
        record.setReasonCode(reasonCode);
        record.setDivergenceScore(1.0);
        record.setIntentPresent(false);
        record.setIntentSource(IntentSource.NONE.name());
        auditHistory.save(record);
    }
}
