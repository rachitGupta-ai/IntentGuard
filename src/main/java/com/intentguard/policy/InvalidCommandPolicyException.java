package com.intentguard.policy;

/**
 * Thrown when a proposed {@link CommandPolicy}, {@link PolicyRule}, or {@link PolicyScope} fails
 * validation (Req 2.12, 2.13).
 *
 * <p>A {@link PolicyRule} with a blank id, a missing or non-compilable pattern, a {@code null}
 * scope, or a missing action is invalid; a {@link CommandPolicy} with a version below {@code 1},
 * a {@code null} rules list, or duplicate rule ids is invalid. When {@code CommandPolicyService}
 * receives an update that produces an invalid policy it throws this exception and leaves the
 * previously active policy unchanged, so an invalid Administrator update can never take effect.
 */
public class InvalidCommandPolicyException extends RuntimeException {

    public InvalidCommandPolicyException(String message) {
        super(message);
    }

    public InvalidCommandPolicyException(String message, Throwable cause) {
        super(message, cause);
    }
}
