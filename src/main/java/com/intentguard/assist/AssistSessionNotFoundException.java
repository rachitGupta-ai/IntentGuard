package com.intentguard.assist;

/**
 * Thrown when a referenced assist session does not exist or has already expired.
 */
public class AssistSessionNotFoundException extends RuntimeException {

    public AssistSessionNotFoundException(String sessionId) {
        super("Assist session not found: " + sessionId);
    }
}
