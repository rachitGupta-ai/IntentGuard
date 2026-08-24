package com.intentguard.blastradius;

/**
 * Thrown when a proposed {@link GuardrailConfig} is invalid (Req 9.5, 9.6).
 *
 * <p>Because {@link GuardrailConfig} is valid-by-construction, an invalid candidate can never be
 * materialized, so the previously active configuration is never displaced by a bad update.
 */
public class InvalidGuardrailConfigException extends RuntimeException {

    public InvalidGuardrailConfigException(String message) {
        super(message);
    }
}
