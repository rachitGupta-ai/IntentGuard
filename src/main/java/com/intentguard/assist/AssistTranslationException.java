package com.intentguard.assist;

/**
 * Thrown when translation of a non-English NL query fails during assist processing.
 */
public class AssistTranslationException extends RuntimeException {

    public AssistTranslationException(String message) {
        super(message);
    }
}
