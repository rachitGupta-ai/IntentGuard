package com.intentguard.config;

import java.util.Map;

import com.intentguard.domain.ComponentId;

/**
 * A proposed Administrator update to the Threshold_Configuration (Req 7.5).
 *
 * <p>Carries only the tunable fields; the {@code version}, author, and timestamp are assigned by
 * {@link ThresholdConfigurationService} when a valid update is applied. The service validates a
 * proposed update by materializing a {@link ThresholdConfiguration} from it — an invalid proposal
 * is rejected and the previously active configuration is retained.
 */
public record ThresholdConfigUpdate(
        double askThreshold,
        double blockThreshold,
        Map<ComponentId, Double> componentWeights,
        double inferredIntentSemanticWeight,
        int learningMinEvents,
        long monitoringGapTimeoutMs,
        long confirmationTimeoutMs,
        long llmTimeoutMs,
        long correlationWindowMs) {

    /** Derives an update carrying the tunable fields of an existing configuration. */
    public static ThresholdConfigUpdate from(ThresholdConfiguration config) {
        return new ThresholdConfigUpdate(
                config.askThreshold(),
                config.blockThreshold(),
                config.componentWeights(),
                config.inferredIntentSemanticWeight(),
                config.learningMinEvents(),
                config.monitoringGapTimeoutMs(),
                config.confirmationTimeoutMs(),
                config.llmTimeoutMs(),
                config.correlationWindowMs());
    }
}
