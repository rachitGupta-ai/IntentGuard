package com.intentguard.watchdog;

/**
 * A control operation a monitored user might attempt to issue against the running
 * Enforcement_Engine (Req 1.3).
 *
 * <p>These are the reference-monitor control-surface actions that only a privileged operator may
 * perform. A monitored user has no privilege over the engine, so any such attempt is rejected, the
 * engine's configuration and process state are left unchanged, and the attempt is recorded in the
 * Audit_History.
 */
public enum ControlOperation {

    /** Terminate the Enforcement_Engine process. */
    STOP,

    /** Suspend enforcement without terminating the process. */
    PAUSE,

    /** Change the engine's Threshold_Configuration or other runtime configuration. */
    RECONFIGURE
}
