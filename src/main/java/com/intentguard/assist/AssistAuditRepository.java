package com.intentguard.assist;

import java.util.List;

import org.bson.Document;
import org.springframework.stereotype.Component;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;

/**
 * Persists assist audit entries to the {@code assist_audit} MongoDB collection.
 *
 * <p>Every NL Operations Assistant interaction — query submission, command selection, execution
 * outcome, or blocked command — is recorded as a separate BSON document for forensic review and
 * compliance (Requirements 10.1, 10.2, 10.3).
 *
 * <p>Uses raw {@link Document} insertion (rather than the POJO codec) to keep the persistence
 * layer decoupled from the domain model and to allow flexible schema evolution.
 */
@Component
public class AssistAuditRepository {

    static final String COLLECTION = "assist_audit";

    private final MongoCollection<Document> collection;

    public AssistAuditRepository(MongoDatabase database) {
        this.collection = database.getCollection(COLLECTION);
    }

    /**
     * Records a query submission event: the operator's English query and the generated alternatives.
     */
    public void saveQuery(String sessionId, String operatorId,
                          String queryEnglish, List<CommandAlternative> alternatives) {
        Document doc = new Document()
                .append("sessionId", sessionId)
                .append("operatorId", operatorId)
                .append("eventType", "QUERY")
                .append("queryEnglish", queryEnglish)
                .append("generatedCommands", alternatives.stream()
                        .map(CommandAlternative::command)
                        .toList())
                .append("timestamp", System.currentTimeMillis());
        collection.insertOne(doc);
    }

    /**
     * Records a command selection event with its divergence score and corrective action.
     * If the command was blocked by the decision engine, the event type is set to BLOCK.
     */
    public void saveSelection(String sessionId, String command,
                              double score, String action, boolean blocked) {
        Document doc = new Document()
                .append("sessionId", sessionId)
                .append("eventType", blocked ? "BLOCK" : "SELECTION")
                .append("selectedCommand", command)
                .append("score", score)
                .append("action", action)
                .append("timestamp", System.currentTimeMillis());
        collection.insertOne(doc);
    }

    /**
     * Records a command execution event with exit code and captured output.
     */
    public void saveExecution(String sessionId, String command,
                              int exitCode, String stdout, String stderr) {
        Document doc = new Document()
                .append("sessionId", sessionId)
                .append("eventType", "EXECUTION")
                .append("selectedCommand", command)
                .append("exitCode", exitCode)
                .append("stdout", stdout)
                .append("stderr", stderr)
                .append("timestamp", System.currentTimeMillis());
        collection.insertOne(doc);
    }

    /**
     * Records a block event with the reason the command was prevented from executing.
     */
    public void saveBlock(String sessionId, String command, String blockReason) {
        Document doc = new Document()
                .append("sessionId", sessionId)
                .append("eventType", "BLOCK")
                .append("selectedCommand", command)
                .append("blockReason", blockReason)
                .append("timestamp", System.currentTimeMillis());
        collection.insertOne(doc);
    }
}
