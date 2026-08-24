package com.intentguard.persistence;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Persisted form of a single scripted Command_Event in a scenario's {@code eventScript}
 * (see {@link ScenarioBaselineDocument}). Captures the fields needed to replay the event
 * deterministically during a demo scenario (Req 16).
 *
 * <p>Mutable JavaBean shape with a no-arg constructor for the MongoDB POJO codec.
 */
public class ScenarioCommandDocument {

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
    private String intentSource;
    private boolean opensOutboundConnection;
    private boolean accessesSecret;
    private boolean privilegeEscalation;

    public ScenarioCommandDocument() {
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

    public String getIntentSource() {
        return intentSource;
    }

    public void setIntentSource(String intentSource) {
        this.intentSource = intentSource;
    }

    public boolean isOpensOutboundConnection() {
        return opensOutboundConnection;
    }

    public void setOpensOutboundConnection(boolean opensOutboundConnection) {
        this.opensOutboundConnection = opensOutboundConnection;
    }

    public boolean isAccessesSecret() {
        return accessesSecret;
    }

    public void setAccessesSecret(boolean accessesSecret) {
        this.accessesSecret = accessesSecret;
    }

    public boolean isPrivilegeEscalation() {
        return privilegeEscalation;
    }

    public void setPrivilegeEscalation(boolean privilegeEscalation) {
        this.privilegeEscalation = privilegeEscalation;
    }
}
