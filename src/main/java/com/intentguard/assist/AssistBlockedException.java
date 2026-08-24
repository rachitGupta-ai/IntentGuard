package com.intentguard.assist;

/**
 * Thrown when a selected command receives a BLOCK action from the decision engine,
 * preventing execution.
 */
public class AssistBlockedException extends RuntimeException {

    public AssistBlockedException(String message) {
        super(message);
    }
}
