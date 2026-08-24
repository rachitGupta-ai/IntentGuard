package com.intentguard.hardening;

import java.util.Objects;

/**
 * The outcome of the startup privilege-separation verification (Req 9.3, 9.4).
 *
 * @param separated whether the engine's service account is distinct from every monitored user
 * @param reasonCode a short machine-readable code describing why verification passed or failed
 * @param detail a human-readable explanation, recorded in the Audit_History on failure
 */
public record PrivilegeSeparationResult(boolean separated, String reasonCode, String detail) {

    /** Verification passed: the service account is distinct from every monitored user. */
    public static final String REASON_SEPARATED = "PRIVILEGE_SEPARATED";

    /** Verification failed: no dedicated service account was configured. */
    public static final String REASON_NO_SERVICE_ACCOUNT = "PRIVILEGE_SEPARATION_NO_SERVICE_ACCOUNT";

    /** Verification failed: the service account is itself a monitored user. */
    public static final String REASON_ACCOUNT_MONITORED = "PRIVILEGE_SEPARATION_ACCOUNT_MONITORED";

    public PrivilegeSeparationResult {
        Objects.requireNonNull(reasonCode, "reasonCode must not be null");
        Objects.requireNonNull(detail, "detail must not be null");
    }
}
