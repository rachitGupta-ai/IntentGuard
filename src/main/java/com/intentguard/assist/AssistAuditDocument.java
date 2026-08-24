package com.intentguard.assist;

import java.util.List;

/**
 * MongoDB document for assist audit entries. Stored in the {@code assist_audit} collection.
 *
 * <p>Each interaction with the NL Operations Assistant (query submission, command selection,
 * execution, or block) is persisted as a separate audit entry for forensic review.
 *
 * <p>Mutable JavaBean shape with a no-arg constructor for flexible construction in the repository.
 */
public class AssistAuditDocument {

    private String id;
    private String sessionId;
    private String operatorId;
    private String eventType; // QUERY, SELECTION, EXECUTION, BLOCK
    private String queryEnglish;
    private List<String> generatedCommands;
    private String selectedCommand;
    private Double score;
    private String action;
    private String blockReason;
    private Integer exitCode;
    private String stdout;
    private String stderr;
    private long timestamp;

    public AssistAuditDocument() {
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public String getOperatorId() {
        return operatorId;
    }

    public void setOperatorId(String operatorId) {
        this.operatorId = operatorId;
    }

    public String getEventType() {
        return eventType;
    }

    public void setEventType(String eventType) {
        this.eventType = eventType;
    }

    public String getQueryEnglish() {
        return queryEnglish;
    }

    public void setQueryEnglish(String queryEnglish) {
        this.queryEnglish = queryEnglish;
    }

    public List<String> getGeneratedCommands() {
        return generatedCommands;
    }

    public void setGeneratedCommands(List<String> generatedCommands) {
        this.generatedCommands = generatedCommands;
    }

    public String getSelectedCommand() {
        return selectedCommand;
    }

    public void setSelectedCommand(String selectedCommand) {
        this.selectedCommand = selectedCommand;
    }

    public Double getScore() {
        return score;
    }

    public void setScore(Double score) {
        this.score = score;
    }

    public String getAction() {
        return action;
    }

    public void setAction(String action) {
        this.action = action;
    }

    public String getBlockReason() {
        return blockReason;
    }

    public void setBlockReason(String blockReason) {
        this.blockReason = blockReason;
    }

    public Integer getExitCode() {
        return exitCode;
    }

    public void setExitCode(Integer exitCode) {
        this.exitCode = exitCode;
    }

    public String getStdout() {
        return stdout;
    }

    public void setStdout(String stdout) {
        this.stdout = stdout;
    }

    public String getStderr() {
        return stderr;
    }

    public void setStderr(String stderr) {
        this.stderr = stderr;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(long timestamp) {
        this.timestamp = timestamp;
    }
}
