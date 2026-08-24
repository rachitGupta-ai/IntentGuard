package com.intentguard.intent;

/**
 * Thrown when an {@code Agent_Actor} attempts to open, expand, or modify an Intent_Session or a
 * Declared_Intent. An Agent_Actor has no independent authority over intent (it is bound to the
 * intent envelope of its human principal), so any such request is rejected, the affected session is
 * preserved unchanged, and the rejected attempt is recorded in the Audit_History (Req 13.3).
 *
 * <p>This is an unchecked exception because it represents a rejected security-relevant request on
 * the enforcement path rather than a recoverable application condition.
 */
public class AgentIntentMutationException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public AgentIntentMutationException(String message) {
        super(message);
    }
}
