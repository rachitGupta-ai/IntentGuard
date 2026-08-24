package com.intentguard.persistence;

import java.util.ArrayList;
import java.util.List;

/**
 * Persisted scenario baseline for the {@code scenario_baselines} collection (Req 16.1, 16.2).
 * Holds a frozen seed Behavioral_Profile and Threshold_Configuration plus the ordered script of
 * Command_Events to replay, so that a scenario replays reproducibly and deterministically.
 *
 * <p>{@link #scenarioId} is the business key. Mutable JavaBean shape with a no-arg constructor for
 * the MongoDB POJO codec.
 */
public class ScenarioBaselineDocument {

    private String scenarioId;
    private BehavioralProfileDocument seedProfile;
    private ThresholdConfigDocument seedThresholds;
    private List<ScenarioCommandDocument> eventScript = new ArrayList<>();

    public ScenarioBaselineDocument() {
    }

    public String getScenarioId() {
        return scenarioId;
    }

    public void setScenarioId(String scenarioId) {
        this.scenarioId = scenarioId;
    }

    public BehavioralProfileDocument getSeedProfile() {
        return seedProfile;
    }

    public void setSeedProfile(BehavioralProfileDocument seedProfile) {
        this.seedProfile = seedProfile;
    }

    public ThresholdConfigDocument getSeedThresholds() {
        return seedThresholds;
    }

    public void setSeedThresholds(ThresholdConfigDocument seedThresholds) {
        this.seedThresholds = seedThresholds;
    }

    public List<ScenarioCommandDocument> getEventScript() {
        return eventScript;
    }

    public void setEventScript(List<ScenarioCommandDocument> eventScript) {
        this.eventScript = eventScript;
    }
}
