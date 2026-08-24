package com.intentguard.persistence;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Persisted, versioned Threshold_Configuration for the {@code threshold_config} collection
 * (Req 7.1, 7.5). {@link #version} is monotonically increasing; the highest version is the active
 * configuration.
 *
 * <p>Component weights are keyed by {@code ComponentId.name()}. Semantic validation (ordered
 * thresholds, non-negative weights, hot-reload) is layered on in Task 4.2; this document is the
 * storage shape only.
 *
 * <p>Mutable JavaBean shape with a no-arg constructor for the MongoDB POJO codec.
 */
public class ThresholdConfigDocument {

    private int version;
    private double askThreshold;
    private double blockThreshold;
    private Map<String, Double> componentWeights = new LinkedHashMap<>();
    private double inferredIntentSemanticWeight;
    private int learningMinEvents;
    private long monitoringGapTimeoutMs;
    private long confirmationTimeoutMs;
    private long llmTimeoutMs;
    private long correlationWindowMs;
    private String updatedBy;
    private long updatedAt;

    public ThresholdConfigDocument() {
    }

    public int getVersion() {
        return version;
    }

    public void setVersion(int version) {
        this.version = version;
    }

    public double getAskThreshold() {
        return askThreshold;
    }

    public void setAskThreshold(double askThreshold) {
        this.askThreshold = askThreshold;
    }

    public double getBlockThreshold() {
        return blockThreshold;
    }

    public void setBlockThreshold(double blockThreshold) {
        this.blockThreshold = blockThreshold;
    }

    public Map<String, Double> getComponentWeights() {
        return componentWeights;
    }

    public void setComponentWeights(Map<String, Double> componentWeights) {
        this.componentWeights = componentWeights;
    }

    public double getInferredIntentSemanticWeight() {
        return inferredIntentSemanticWeight;
    }

    public void setInferredIntentSemanticWeight(double inferredIntentSemanticWeight) {
        this.inferredIntentSemanticWeight = inferredIntentSemanticWeight;
    }

    public int getLearningMinEvents() {
        return learningMinEvents;
    }

    public void setLearningMinEvents(int learningMinEvents) {
        this.learningMinEvents = learningMinEvents;
    }

    public long getMonitoringGapTimeoutMs() {
        return monitoringGapTimeoutMs;
    }

    public void setMonitoringGapTimeoutMs(long monitoringGapTimeoutMs) {
        this.monitoringGapTimeoutMs = monitoringGapTimeoutMs;
    }

    public long getConfirmationTimeoutMs() {
        return confirmationTimeoutMs;
    }

    public void setConfirmationTimeoutMs(long confirmationTimeoutMs) {
        this.confirmationTimeoutMs = confirmationTimeoutMs;
    }

    public long getLlmTimeoutMs() {
        return llmTimeoutMs;
    }

    public void setLlmTimeoutMs(long llmTimeoutMs) {
        this.llmTimeoutMs = llmTimeoutMs;
    }

    public long getCorrelationWindowMs() {
        return correlationWindowMs;
    }

    public void setCorrelationWindowMs(long correlationWindowMs) {
        this.correlationWindowMs = correlationWindowMs;
    }

    public String getUpdatedBy() {
        return updatedBy;
    }

    public void setUpdatedBy(String updatedBy) {
        this.updatedBy = updatedBy;
    }

    public long getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(long updatedAt) {
        this.updatedAt = updatedAt;
    }
}
