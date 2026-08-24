package com.intentguard.assist;

/**
 * Thrown when all generated command alternatives are discarded by the generation blocklist,
 * indicating the requested operation cannot be safely fulfilled.
 */
public class AssistBlocklistException extends RuntimeException {

    public AssistBlocklistException(String message) {
        super(message);
    }
}
