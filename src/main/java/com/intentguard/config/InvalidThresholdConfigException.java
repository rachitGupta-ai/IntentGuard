package com.intentguard.config;

/**
 * Thrown when a proposed {@link ThresholdConfiguration} fails validation (Req 7.1, 7.5).
 *
 * <p>When {@link ThresholdConfigurationService} receives an update that produces an invalid
 * configuration, it throws this exception and leaves the previously active configuration
 * unchanged, so an invalid Administrator update can never take effect.
 */
public class InvalidThresholdConfigException extends RuntimeException {

    public InvalidThresholdConfigException(String message) {
        super(message);
    }
}
