package com.intentguard.assist;

/**
 * Thrown when the LLM service fails or times out during command alternative generation.
 */
public class AssistGenerationException extends RuntimeException {

    public AssistGenerationException(String message) {
        super(message);
    }
}
