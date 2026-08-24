package com.intentguard.api;

import com.intentguard.config.ThresholdConfiguration;

/**
 * Error body returned (HTTP 400) when an Administrator threshold update is rejected as invalid
 * (Req 7.5). Carries the validation {@code message} and the {@code previousConfig} that remains in
 * effect, making it explicit that the rejected update did not displace the active configuration.
 * {@code previousConfig} is {@code null} only when no configuration had been established yet.
 */
public record ThresholdUpdateErrorResponse(String message, ThresholdConfiguration previousConfig) {
}
