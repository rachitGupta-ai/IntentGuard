package com.intentguard.config;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.junit.jupiter.api.Test;

import com.intentguard.domain.ComponentId;
import com.intentguard.persistence.ThresholdConfigDocument;

/**
 * Unit tests for the {@link ThresholdConfiguration} domain model: validation of ordered thresholds
 * and non-negative weights, and lossless conversion to/from the persisted document (Req 7.1, 7.5).
 * No live Datastore is required.
 */
class ThresholdConfigurationTest {

    private static Map<ComponentId, Double> validWeights() {
        Map<ComponentId, Double> weights = new EnumMap<>(ComponentId.class);
        weights.put(ComponentId.SEQUENCE_SURPRISE, 0.25);
        weights.put(ComponentId.CONTEXT_MISMATCH, 0.20);
        weights.put(ComponentId.BEHAVIORAL_DEVIATION, 0.25);
        weights.put(ComponentId.SEMANTIC_INCONSISTENCY, 0.30);
        return weights;
    }

    private static ThresholdConfiguration valid(int version) {
        return new ThresholdConfiguration(
                version, 0.4, 0.7, validWeights(), 0.15, 200, 5000, 15000, 1200, 1000, "admin", 111L);
    }

    @Test
    void buildsValidConfiguration() {
        ThresholdConfiguration config = valid(1);
        assertThat(config.askThreshold()).isEqualTo(0.4);
        assertThat(config.blockThreshold()).isEqualTo(0.7);
        assertThat(config.componentWeights()).containsEntry(ComponentId.SEMANTIC_INCONSISTENCY, 0.30);
    }

    @Test
    void rejectsUnorderedThresholds() {
        assertThatThrownBy(() -> new ThresholdConfiguration(
                1, 0.8, 0.5, validWeights(), 0.15, 200, 5000, 15000, 1200, 1000, "admin", 0L))
                .isInstanceOf(InvalidThresholdConfigException.class)
                .hasMessageContaining("askThreshold");
    }

    @Test
    void rejectsThresholdOutsideUnitInterval() {
        assertThatThrownBy(() -> new ThresholdConfiguration(
                1, -0.1, 0.7, validWeights(), 0.15, 200, 5000, 15000, 1200, 1000, "admin", 0L))
                .isInstanceOf(InvalidThresholdConfigException.class);
        assertThatThrownBy(() -> new ThresholdConfiguration(
                1, 0.4, 1.5, validWeights(), 0.15, 200, 5000, 15000, 1200, 1000, "admin", 0L))
                .isInstanceOf(InvalidThresholdConfigException.class);
    }

    @Test
    void rejectsNegativeComponentWeight() {
        Map<ComponentId, Double> weights = validWeights();
        weights.put(ComponentId.CONTEXT_MISMATCH, -0.01);
        assertThatThrownBy(() -> new ThresholdConfiguration(
                1, 0.4, 0.7, weights, 0.15, 200, 5000, 15000, 1200, 1000, "admin", 0L))
                .isInstanceOf(InvalidThresholdConfigException.class)
                .hasMessageContaining("CONTEXT_MISMATCH");
    }

    @Test
    void rejectsNegativeInferredIntentWeight() {
        assertThatThrownBy(() -> new ThresholdConfiguration(
                1, 0.4, 0.7, validWeights(), -0.1, 200, 5000, 15000, 1200, 1000, "admin", 0L))
                .isInstanceOf(InvalidThresholdConfigException.class)
                .hasMessageContaining("inferredIntentSemanticWeight");
    }

    @Test
    void rejectsNonPositiveTimeoutsAndLearningMin() {
        assertThatThrownBy(() -> new ThresholdConfiguration(
                1, 0.4, 0.7, validWeights(), 0.15, 0, 5000, 15000, 1200, 1000, "admin", 0L))
                .isInstanceOf(InvalidThresholdConfigException.class)
                .hasMessageContaining("learningMinEvents");
        assertThatThrownBy(() -> new ThresholdConfiguration(
                1, 0.4, 0.7, validWeights(), 0.15, 200, 0, 15000, 1200, 1000, "admin", 0L))
                .isInstanceOf(InvalidThresholdConfigException.class)
                .hasMessageContaining("monitoringGapTimeoutMs");
        assertThatThrownBy(() -> new ThresholdConfiguration(
                1, 0.4, 0.7, validWeights(), 0.15, 200, 5000, 15000, 1200, -1, "admin", 0L))
                .isInstanceOf(InvalidThresholdConfigException.class)
                .hasMessageContaining("correlationWindowMs");
    }

    @Test
    void rejectsVersionBelowOne() {
        assertThatThrownBy(() -> new ThresholdConfiguration(
                0, 0.4, 0.7, validWeights(), 0.15, 200, 5000, 15000, 1200, 1000, "admin", 0L))
                .isInstanceOf(InvalidThresholdConfigException.class)
                .hasMessageContaining("version");
    }

    @Test
    void equalAskAndBlockThresholdsAreAllowed() {
        ThresholdConfiguration config = new ThresholdConfiguration(
                1, 0.5, 0.5, validWeights(), 0.15, 200, 5000, 15000, 1200, 1000, "admin", 0L);
        assertThat(config.askThreshold()).isEqualTo(config.blockThreshold());
    }

    @Test
    void documentRoundTripPreservesData() {
        ThresholdConfiguration original = valid(7);
        ThresholdConfigDocument document = original.toDocument();
        ThresholdConfiguration restored = ThresholdConfiguration.fromDocument(document);
        assertThat(restored).isEqualTo(original);
    }

    @Test
    void fromDocumentRejectsUnknownComponentId() {
        ThresholdConfigDocument document = valid(1).toDocument();
        Map<String, Double> weights = new HashMap<>(document.getComponentWeights());
        weights.put("NOT_A_COMPONENT", 0.1);
        document.setComponentWeights(weights);
        assertThatThrownBy(() -> ThresholdConfiguration.fromDocument(document))
                .isInstanceOf(InvalidThresholdConfigException.class)
                .hasMessageContaining("NOT_A_COMPONENT");
    }

    @Test
    void toScoringConfigCarriesWeights() {
        ThresholdConfiguration config = valid(1);
        assertThat(config.toScoringConfig().weightFor(ComponentId.SEQUENCE_SURPRISE)).isEqualTo(0.25);
        assertThat(config.toScoringConfig().inferredIntentSemanticWeight()).isEqualTo(0.15);
    }
}
