package com.intentguard.persistence;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Persisted per-user Behavioral_Profile ("behavioral DNA") for the {@code behavioral_profiles}
 * collection (Req 3.1, 3.5). {@link #userId} is the business key used for lookup and upsert.
 *
 * <p>Mutable JavaBean shape with a no-arg constructor for the MongoDB POJO codec.
 */
public class BehavioralProfileDocument {

    private String userId;
    private long eventCount;
    private String state;
    private Map<String, Integer> vocabulary = new LinkedHashMap<>();
    private Map<String, Integer> sequenceStats = new LinkedHashMap<>();
    private Map<String, Double> typedPastedRatioByCategory = new LinkedHashMap<>();
    private TimingPatternsDocument timingPatterns = new TimingPatternsDocument();
    private Map<String, List<String>> contextAssociations = new LinkedHashMap<>();
    private long updatedAt;

    public BehavioralProfileDocument() {
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public long getEventCount() {
        return eventCount;
    }

    public void setEventCount(long eventCount) {
        this.eventCount = eventCount;
    }

    public String getState() {
        return state;
    }

    public void setState(String state) {
        this.state = state;
    }

    public Map<String, Integer> getVocabulary() {
        return vocabulary;
    }

    public void setVocabulary(Map<String, Integer> vocabulary) {
        this.vocabulary = vocabulary;
    }

    public Map<String, Integer> getSequenceStats() {
        return sequenceStats;
    }

    public void setSequenceStats(Map<String, Integer> sequenceStats) {
        this.sequenceStats = sequenceStats;
    }

    public Map<String, Double> getTypedPastedRatioByCategory() {
        return typedPastedRatioByCategory;
    }

    public void setTypedPastedRatioByCategory(Map<String, Double> typedPastedRatioByCategory) {
        this.typedPastedRatioByCategory = typedPastedRatioByCategory;
    }

    public TimingPatternsDocument getTimingPatterns() {
        return timingPatterns;
    }

    public void setTimingPatterns(TimingPatternsDocument timingPatterns) {
        this.timingPatterns = timingPatterns;
    }

    public Map<String, List<String>> getContextAssociations() {
        return contextAssociations;
    }

    public void setContextAssociations(Map<String, List<String>> contextAssociations) {
        this.contextAssociations = contextAssociations;
    }

    public long getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(long updatedAt) {
        this.updatedAt = updatedAt;
    }
}
