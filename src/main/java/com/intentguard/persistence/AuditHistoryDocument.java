package com.intentguard.persistence;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Persisted Audit_History record for the {@code audit_history} collection (Req 11.1). It embeds
 * the Command_Event together with its scoring outcome, corrective decision, and explanation so
 * that every decision is fully reviewable and reproducible.
 *
 * <p>Enum-valued fields are stored as their {@code name()} strings to keep the POJO codec mapping
 * simple and forward-compatible with new {@code recordType}/{@code reasonCode} values.
 *
 * <p>Mutable JavaBean shape with a no-arg constructor for the MongoDB POJO codec.
 */
public class AuditHistoryDocument {

    private String eventId;
    private String userId;
    private String actorType;
    private String humanPrincipalId;
    private String sessionId;
    private String commandText;
    private String cwd;
    private String repo;
    private Map<String, String> envContext = new LinkedHashMap<>();
    private long timestamp;
    private String inputOrigin;
    private String signalSource;
    private List<ComponentScoreDocument> components = new ArrayList<>();
    private List<String> excludedComponents = new ArrayList<>();
    private double divergenceScore;
    private String correctiveAction;
    private String reasonCode;
    private boolean intentPresent;
    private String intentSource;
    private String explanation;
    private String profileState;
    private String recordType;

    public AuditHistoryDocument() {
    }

    public String getEventId() {
        return eventId;
    }

    public void setEventId(String eventId) {
        this.eventId = eventId;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getActorType() {
        return actorType;
    }

    public void setActorType(String actorType) {
        this.actorType = actorType;
    }

    public String getHumanPrincipalId() {
        return humanPrincipalId;
    }

    public void setHumanPrincipalId(String humanPrincipalId) {
        this.humanPrincipalId = humanPrincipalId;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public String getCommandText() {
        return commandText;
    }

    public void setCommandText(String commandText) {
        this.commandText = commandText;
    }

    public String getCwd() {
        return cwd;
    }

    public void setCwd(String cwd) {
        this.cwd = cwd;
    }

    public String getRepo() {
        return repo;
    }

    public void setRepo(String repo) {
        this.repo = repo;
    }

    public Map<String, String> getEnvContext() {
        return envContext;
    }

    public void setEnvContext(Map<String, String> envContext) {
        this.envContext = envContext;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(long timestamp) {
        this.timestamp = timestamp;
    }

    public String getInputOrigin() {
        return inputOrigin;
    }

    public void setInputOrigin(String inputOrigin) {
        this.inputOrigin = inputOrigin;
    }

    public String getSignalSource() {
        return signalSource;
    }

    public void setSignalSource(String signalSource) {
        this.signalSource = signalSource;
    }

    public List<ComponentScoreDocument> getComponents() {
        return components;
    }

    public void setComponents(List<ComponentScoreDocument> components) {
        this.components = components;
    }

    public List<String> getExcludedComponents() {
        return excludedComponents;
    }

    public void setExcludedComponents(List<String> excludedComponents) {
        this.excludedComponents = excludedComponents;
    }

    public double getDivergenceScore() {
        return divergenceScore;
    }

    public void setDivergenceScore(double divergenceScore) {
        this.divergenceScore = divergenceScore;
    }

    public String getCorrectiveAction() {
        return correctiveAction;
    }

    public void setCorrectiveAction(String correctiveAction) {
        this.correctiveAction = correctiveAction;
    }

    public String getReasonCode() {
        return reasonCode;
    }

    public void setReasonCode(String reasonCode) {
        this.reasonCode = reasonCode;
    }

    public boolean isIntentPresent() {
        return intentPresent;
    }

    public void setIntentPresent(boolean intentPresent) {
        this.intentPresent = intentPresent;
    }

    public String getIntentSource() {
        return intentSource;
    }

    public void setIntentSource(String intentSource) {
        this.intentSource = intentSource;
    }

    public String getExplanation() {
        return explanation;
    }

    public void setExplanation(String explanation) {
        this.explanation = explanation;
    }

    public String getProfileState() {
        return profileState;
    }

    public void setProfileState(String profileState) {
        this.profileState = profileState;
    }

    public String getRecordType() {
        return recordType;
    }

    public void setRecordType(String recordType) {
        this.recordType = recordType;
    }
}
