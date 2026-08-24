package com.intentguard.config;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import com.intentguard.domain.ComponentId;
import com.intentguard.domain.ScoringConfig;
import com.intentguard.persistence.ThresholdConfigDocument;

/**
 * Immutable, versioned Threshold_Configuration domain model (Req 7.1, 7.5).
 *
 * <p>Maps Divergence_Score ranges to Corrective_Actions ({@code allow} below {@link #askThreshold},
 * {@code ask} in [askThreshold, blockThreshold), {@code block} at or above {@link #blockThreshold})
 * and carries the component weights plus the engine's tunable timeouts and learning threshold.
 *
 * <p>Every instance is <em>valid by construction</em>: the compact constructor enforces the
 * invariants below and throws {@link InvalidThresholdConfigException} otherwise. This is what makes
 * "reject invalid updates and retain the previous config" straightforward — an invalid candidate
 * can never be materialized, so the previously active configuration is never displaced.
 *
 * <p>Invariants:
 * <ul>
 *   <li>{@code version} is at least 1 (monotonically increasing across updates);</li>
 *   <li>{@code askThreshold} and {@code blockThreshold} lie in [0.0, 1.0] and are ordered
 *       ({@code askThreshold <= blockThreshold});</li>
 *   <li>every component weight is non-negative (and not NaN), and
 *       {@code inferredIntentSemanticWeight} is non-negative;</li>
 *   <li>{@code learningMinEvents} and every timeout / window are strictly positive.</li>
 * </ul>
 */
public record ThresholdConfiguration(
        int version,
        double askThreshold,
        double blockThreshold,
        Map<ComponentId, Double> componentWeights,
        double inferredIntentSemanticWeight,
        int learningMinEvents,
        long monitoringGapTimeoutMs,
        long confirmationTimeoutMs,
        long llmTimeoutMs,
        long correlationWindowMs,
        String updatedBy,
        long updatedAt) {

    public ThresholdConfiguration {
        if (version < 1) {
            throw new InvalidThresholdConfigException("version must be >= 1: " + version);
        }
        requireUnitInterval("askThreshold", askThreshold);
        requireUnitInterval("blockThreshold", blockThreshold);
        if (askThreshold > blockThreshold) {
            throw new InvalidThresholdConfigException(
                    "askThreshold (" + askThreshold + ") must be <= blockThreshold (" + blockThreshold + ")");
        }

        Objects.requireNonNull(componentWeights, "componentWeights must not be null");
        Map<ComponentId, Double> copy = new EnumMap<>(ComponentId.class);
        componentWeights.forEach((id, weight) -> {
            if (id == null) {
                throw new InvalidThresholdConfigException("componentWeights contains a null component id");
            }
            if (weight == null || Double.isNaN(weight) || weight < 0.0) {
                throw new InvalidThresholdConfigException(
                        "weight for " + id + " must be non-negative: " + weight);
            }
            copy.put(id, weight);
        });
        // Defensive, unmodifiable copy so the record stays immutable.
        componentWeights = Map.copyOf(copy);

        if (Double.isNaN(inferredIntentSemanticWeight) || inferredIntentSemanticWeight < 0.0) {
            throw new InvalidThresholdConfigException(
                    "inferredIntentSemanticWeight must be non-negative: " + inferredIntentSemanticWeight);
        }
        requirePositive("learningMinEvents", learningMinEvents);
        requirePositive("monitoringGapTimeoutMs", monitoringGapTimeoutMs);
        requirePositive("confirmationTimeoutMs", confirmationTimeoutMs);
        requirePositive("llmTimeoutMs", llmTimeoutMs);
        requirePositive("correlationWindowMs", correlationWindowMs);
        Objects.requireNonNull(updatedBy, "updatedBy must not be null");
    }

    private static void requireUnitInterval(String field, double value) {
        if (Double.isNaN(value) || value < 0.0 || value > 1.0) {
            throw new InvalidThresholdConfigException(field + " must be in [0.0, 1.0]: " + value);
        }
    }

    private static void requirePositive(String field, long value) {
        if (value <= 0) {
            throw new InvalidThresholdConfigException(field + " must be positive: " + value);
        }
    }

    /**
     * Builds a validated {@link ThresholdConfiguration} from a proposed {@link ThresholdConfigUpdate}
     * at the given version, stamping the author and timestamp. Throws
     * {@link InvalidThresholdConfigException} if the proposed values are invalid.
     */
    public static ThresholdConfiguration fromUpdate(
            int version, ThresholdConfigUpdate update, String updatedBy, long updatedAt) {
        Objects.requireNonNull(update, "update must not be null");
        return new ThresholdConfiguration(
                version,
                update.askThreshold(),
                update.blockThreshold(),
                update.componentWeights(),
                update.inferredIntentSemanticWeight(),
                update.learningMinEvents(),
                update.monitoringGapTimeoutMs(),
                update.confirmationTimeoutMs(),
                update.llmTimeoutMs(),
                update.correlationWindowMs(),
                updatedBy,
                updatedAt);
    }

    /**
     * Reconstructs a {@link ThresholdConfiguration} from its persisted {@link ThresholdConfigDocument}.
     * Weight keys are parsed as {@link ComponentId} values; validation is applied as for any instance.
     */
    public static ThresholdConfiguration fromDocument(ThresholdConfigDocument document) {
        Objects.requireNonNull(document, "document must not be null");
        Map<ComponentId, Double> weights = new EnumMap<>(ComponentId.class);
        Map<String, Double> raw = document.getComponentWeights();
        if (raw != null) {
            raw.forEach((key, weight) -> weights.put(parseComponentId(key), weight));
        }
        return new ThresholdConfiguration(
                document.getVersion(),
                document.getAskThreshold(),
                document.getBlockThreshold(),
                weights,
                document.getInferredIntentSemanticWeight(),
                document.getLearningMinEvents(),
                document.getMonitoringGapTimeoutMs(),
                document.getConfirmationTimeoutMs(),
                document.getLlmTimeoutMs(),
                document.getCorrelationWindowMs(),
                document.getUpdatedBy(),
                document.getUpdatedAt());
    }

    private static ComponentId parseComponentId(String key) {
        try {
            return ComponentId.valueOf(key);
        } catch (IllegalArgumentException e) {
            throw new InvalidThresholdConfigException("unknown component id in weights: " + key);
        }
    }

    /** Converts this configuration to its persisted {@link ThresholdConfigDocument} shape. */
    public ThresholdConfigDocument toDocument() {
        ThresholdConfigDocument document = new ThresholdConfigDocument();
        document.setVersion(version);
        document.setAskThreshold(askThreshold);
        document.setBlockThreshold(blockThreshold);
        Map<String, Double> weights = new LinkedHashMap<>();
        componentWeights.forEach((id, weight) -> weights.put(id.name(), weight));
        document.setComponentWeights(weights);
        document.setInferredIntentSemanticWeight(inferredIntentSemanticWeight);
        document.setLearningMinEvents(learningMinEvents);
        document.setMonitoringGapTimeoutMs(monitoringGapTimeoutMs);
        document.setConfirmationTimeoutMs(confirmationTimeoutMs);
        document.setLlmTimeoutMs(llmTimeoutMs);
        document.setCorrelationWindowMs(correlationWindowMs);
        document.setUpdatedBy(updatedBy);
        document.setUpdatedAt(updatedAt);
        return document;
    }

    /**
     * Derives the {@link ScoringConfig} the scoring pipeline consumes for a single scoring: the
     * component weights and the (lower) inferred-intent semantic weight. This is the bridge that
     * lets a hot-reloaded configuration take effect on subsequent Command_Events (Req 7.5).
     */
    public ScoringConfig toScoringConfig() {
        return new ScoringConfig(componentWeights, inferredIntentSemanticWeight);
    }
}
