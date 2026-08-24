package com.intentguard.watchdog;

/**
 * Thrown when a control request (stop / pause / reconfigure, or a socket request targeting engine
 * config / process state / the Datastore) is rejected by the reference monitor's self-defense
 * because the requesting actor lacks privilege over the Enforcement_Engine (Req 1.3, 1.6).
 *
 * <p>By the time this is thrown the rejected attempt has already been recorded in the Audit_History
 * and no engine state has been mutated, so callers can treat it purely as a signal that the request
 * was refused.
 */
public class ControlRequestRejectedException extends RuntimeException {

    public ControlRequestRejectedException(String message) {
        super(message);
    }
}
