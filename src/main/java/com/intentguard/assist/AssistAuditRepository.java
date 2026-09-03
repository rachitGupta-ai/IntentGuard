package com.intentguard.assist;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.bson.Document;
import org.bson.conversions.Bson;
import org.bson.types.ObjectId;
import org.springframework.stereotype.Component;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import static com.mongodb.client.model.Filters.and;
import static com.mongodb.client.model.Filters.eq;
import static com.mongodb.client.model.Filters.exists;
import static com.mongodb.client.model.Filters.gte;
import static com.mongodb.client.model.Filters.lte;
import static com.mongodb.client.model.Filters.ne;
import com.mongodb.client.model.Sorts;

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

    // -----------------------------------------------------------------------
    // Read-only methods for the User Profiling Screen (Req 1.1, 4.1, 7.4, 9.3)
    // -----------------------------------------------------------------------

    /**
     * Returns QUERY records for a given operator whose {@code timestamp} falls within
     * {@code [from, to]} inclusive, ordered oldest-first; ties on identical timestamps are broken
     * by ascending {@code _id} (insertion order) to ensure deterministic ordering across repeated
     * requests (Req 4.1).
     *
     * <p>Each raw {@link Document} is mapped to an {@link AssistAuditDocument} with
     * {@code id} set to the hex representation of the BSON {@code _id} ObjectId.
     *
     * <p>Only QUERY records are returned; SELECTION, EXECUTION, and BLOCK records are excluded
     * because they carry no {@code operatorId} and are outside the scope of Requirement 4.
     *
     * @param operatorId the operator identifier to filter on (Req 4.1, 9.3)
     * @param from       inclusive lower timestamp bound in epoch milliseconds
     * @param to         inclusive upper timestamp bound in epoch milliseconds
     * @return non-null, possibly-empty list of matching QUERY records; performs no writes (Req 9.3)
     */
    public List<AssistAuditDocument> findQueriesByOperatorAndTimeRange(
            String operatorId, long from, long to) {
        Bson filter = and(
                eq("eventType", "QUERY"),
                eq("operatorId", operatorId),
                gte("timestamp", from),
                lte("timestamp", to));
        List<AssistAuditDocument> results = new ArrayList<>();
        collection.find(filter)
                .sort(Sorts.orderBy(Sorts.ascending("timestamp"), Sorts.ascending("_id")))
                .forEach(doc -> results.add(toDocument(doc)));
        return results;
    }

    /**
     * Returns the distinct non-null {@code operatorId} values that appear on QUERY records in the
     * collection (Req 1.1). Used to populate the Known_User list for the User Profiling Screen.
     *
     * <p>Only QUERY records carry a meaningful {@code operatorId}; non-QUERY records are excluded
     * because they either carry no {@code operatorId} or cannot be reliably attributed to an
     * operator. Null values are excluded so every returned identifier is non-null.
     *
     * @return non-null, possibly-empty list of distinct operator identifiers; performs no writes
     *         (Req 9.3)
     */
    public List<String> distinctOperatorIds() {
        Bson filter = and(eq("eventType", "QUERY"), exists("operatorId"), ne("operatorId", null));
        List<String> results = new ArrayList<>();
        collection.distinct("operatorId", filter, String.class).into(results);
        return results;
    }

    /**
     * Returns the earliest {@code timestamp} (epoch milliseconds) of any QUERY record for the
     * given operator, or an empty {@link Optional} when no such record exists (Req 7.4). Used to
     * compute the full-history Active_Window lower bound.
     *
     * @param operatorId the operator identifier to look up
     * @return the earliest QUERY timestamp, or {@link Optional#empty()} when none (Req 7.4, 9.3)
     */
    public Optional<Long> earliestQueryTimestampForOperator(String operatorId) {
        Bson filter = and(eq("eventType", "QUERY"), eq("operatorId", operatorId));
        Document doc = collection.find(filter)
                .sort(Sorts.ascending("timestamp"))
                .limit(1)
                .first();
        if (doc == null) {
            return Optional.empty();
        }
        return Optional.of(doc.getLong("timestamp"));
    }

    // -----------------------------------------------------------------------
    // Private helpers
    // -----------------------------------------------------------------------

    /**
     * Maps a raw BSON {@link Document} from the {@code assist_audit} collection to an
     * {@link AssistAuditDocument}, setting {@code id} to the hex representation of the
     * BSON {@code _id} ObjectId (Req 4.1).
     */
    @SuppressWarnings("unchecked")
    private static AssistAuditDocument toDocument(Document doc) {
        AssistAuditDocument d = new AssistAuditDocument();
        Object idObj = doc.get("_id");
        if (idObj instanceof ObjectId oid) {
            d.setId(oid.toHexString());
        } else if (idObj != null) {
            d.setId(idObj.toString());
        }
        d.setSessionId(doc.getString("sessionId"));
        d.setOperatorId(doc.getString("operatorId"));
        d.setEventType(doc.getString("eventType"));
        d.setQueryEnglish(doc.getString("queryEnglish"));
        d.setGeneratedCommands(doc.getList("generatedCommands", String.class));
        d.setSelectedCommand(doc.getString("selectedCommand"));
        d.setScore(doc.getDouble("score"));
        d.setAction(doc.getString("action"));
        d.setBlockReason(doc.getString("blockReason"));
        d.setExitCode(doc.getInteger("exitCode"));
        d.setStdout(doc.getString("stdout"));
        d.setStderr(doc.getString("stderr"));
        Long ts = doc.getLong("timestamp");
        d.setTimestamp(ts != null ? ts : 0L);
        return d;
    }
}
