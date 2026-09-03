package com.intentguard.assist;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mongodb.client.DistinctIterable;
import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;

/**
 * Unit tests for {@link AssistAuditRepository}.
 *
 * <p><b>Property 15: Audit persistence with block reasons</b>
 * <p><b>Validates: Requirements 10.2, 10.3</b>
 *
 * <p>Verifies that:
 * <ul>
 *   <li>Block events include the blockReason field</li>
 *   <li>Non-block events include query/alternatives/score fields as appropriate</li>
 *   <li>All events include a timestamp and the correct eventType</li>
 * </ul>
 *
 * <p>Also verifies the read-only methods added for the User Profiling Screen
 * (Requirements 1.1, 4.1, 7.4, 9.3):
 * <ul>
 *   <li>{@code findQueriesByOperatorAndTimeRange} maps raw Documents to AssistAuditDocument,
 *       uses only QUERY records, and filters by operator + time range</li>
 *   <li>{@code distinctOperatorIds} returns non-null operator ids from QUERY records only</li>
 *   <li>{@code earliestQueryTimestampForOperator} returns the lowest timestamp or empty</li>
 * </ul>
 */
class AssistAuditRepositoryTest {

    @SuppressWarnings("unchecked")
    private final MongoCollection<Document> collection = mock(MongoCollection.class);
    private final MongoDatabase database = mock(MongoDatabase.class);
    private AssistAuditRepository repository;
    private final ArgumentCaptor<Document> docCaptor = ArgumentCaptor.forClass(Document.class);

    @BeforeEach
    void setUp() {
        when(database.getCollection("assist_audit")).thenReturn(collection);
        repository = new AssistAuditRepository(database);
    }

    @Test
    void saveQuery_persistsQueryEventWithAlternatives() {
        List<CommandAlternative> alternatives = List.of(
                new CommandAlternative("ls -la", "List all files", 0),
                new CommandAlternative("find . -type f", "Find all files", 1)
        );

        repository.saveQuery("sess-1", "operator-a", "list files", alternatives);

        verify(collection).insertOne(docCaptor.capture());
        Document doc = docCaptor.getValue();

        assertThat(doc.getString("sessionId")).isEqualTo("sess-1");
        assertThat(doc.getString("operatorId")).isEqualTo("operator-a");
        assertThat(doc.getString("eventType")).isEqualTo("QUERY");
        assertThat(doc.getString("queryEnglish")).isEqualTo("list files");
        assertThat(doc.getList("generatedCommands", String.class))
                .containsExactly("ls -la", "find . -type f");
        assertThat(doc.getLong("timestamp")).isPositive();
    }

    @Test
    void saveSelection_persistsSelectionEventWithScore() {
        repository.saveSelection("sess-2", "df -h", 0.35, "ALLOW", false);

        verify(collection).insertOne(docCaptor.capture());
        Document doc = docCaptor.getValue();

        assertThat(doc.getString("sessionId")).isEqualTo("sess-2");
        assertThat(doc.getString("eventType")).isEqualTo("SELECTION");
        assertThat(doc.getString("selectedCommand")).isEqualTo("df -h");
        assertThat(doc.getDouble("score")).isEqualTo(0.35);
        assertThat(doc.getString("action")).isEqualTo("ALLOW");
        assertThat(doc.getLong("timestamp")).isPositive();
    }

    @Test
    void saveSelection_persistsBlockEventWhenBlocked() {
        repository.saveSelection("sess-3", "rm -rf /", 0.95, "BLOCK", true);

        verify(collection).insertOne(docCaptor.capture());
        Document doc = docCaptor.getValue();

        assertThat(doc.getString("sessionId")).isEqualTo("sess-3");
        assertThat(doc.getString("eventType")).isEqualTo("BLOCK");
        assertThat(doc.getString("selectedCommand")).isEqualTo("rm -rf /");
        assertThat(doc.getDouble("score")).isEqualTo(0.95);
        assertThat(doc.getString("action")).isEqualTo("BLOCK");
        assertThat(doc.getLong("timestamp")).isPositive();
    }

    @Test
    void saveExecution_persistsExecutionEventWithOutput() {
        repository.saveExecution("sess-4", "echo hello", 0, "hello\n", "");

        verify(collection).insertOne(docCaptor.capture());
        Document doc = docCaptor.getValue();

        assertThat(doc.getString("sessionId")).isEqualTo("sess-4");
        assertThat(doc.getString("eventType")).isEqualTo("EXECUTION");
        assertThat(doc.getString("selectedCommand")).isEqualTo("echo hello");
        assertThat(doc.getInteger("exitCode")).isEqualTo(0);
        assertThat(doc.getString("stdout")).isEqualTo("hello\n");
        assertThat(doc.getString("stderr")).isEqualTo("");
        assertThat(doc.getLong("timestamp")).isPositive();
    }

    @Test
    void saveBlock_persistsBlockEventWithReason() {
        repository.saveBlock("sess-5", "mkfs /dev/sda", "Matched blocklist pattern: mkfs");

        verify(collection).insertOne(docCaptor.capture());
        Document doc = docCaptor.getValue();

        assertThat(doc.getString("sessionId")).isEqualTo("sess-5");
        assertThat(doc.getString("eventType")).isEqualTo("BLOCK");
        assertThat(doc.getString("selectedCommand")).isEqualTo("mkfs /dev/sda");
        assertThat(doc.getString("blockReason")).isEqualTo("Matched blocklist pattern: mkfs");
        assertThat(doc.getLong("timestamp")).isPositive();
    }

    // -----------------------------------------------------------------------
    // Read-only method tests (Req 1.1, 4.1, 7.4, 9.3)
    // -----------------------------------------------------------------------

    /**
     * findQueriesByOperatorAndTimeRange maps a raw Document to AssistAuditDocument with the
     * _id converted to a hex string, and preserves all relevant fields (Req 4.1).
     */
    @Test
    @SuppressWarnings("unchecked")
    void findQueriesByOperatorAndTimeRange_mapsDocumentFieldsCorrectly() {
        ObjectId oid = new ObjectId();
        Document raw = new Document("_id", oid)
                .append("sessionId", "sess-q1")
                .append("operatorId", "op-alice")
                .append("eventType", "QUERY")
                .append("queryEnglish", "list running processes")
                .append("generatedCommands", List.of("ps aux", "top -bn1"))
                .append("timestamp", 1_000_000L);

        FindIterable<Document> findIterable = mock(FindIterable.class);
        when(collection.find(any(org.bson.conversions.Bson.class))).thenReturn(findIterable);
        when(findIterable.sort(any())).thenReturn(findIterable);
        // simulate forEach by calling consumer for the single document
        doAnswer(inv -> {
            java.util.function.Consumer<Document> consumer = inv.getArgument(0);
            consumer.accept(raw);
            return null;
        }).when(findIterable).forEach(any(java.util.function.Consumer.class));

        List<AssistAuditDocument> results =
                repository.findQueriesByOperatorAndTimeRange("op-alice", 0L, 2_000_000L);

        assertThat(results).hasSize(1);
        AssistAuditDocument d = results.get(0);
        assertThat(d.getId()).isEqualTo(oid.toHexString());
        assertThat(d.getSessionId()).isEqualTo("sess-q1");
        assertThat(d.getOperatorId()).isEqualTo("op-alice");
        assertThat(d.getEventType()).isEqualTo("QUERY");
        assertThat(d.getQueryEnglish()).isEqualTo("list running processes");
        assertThat(d.getGeneratedCommands()).containsExactly("ps aux", "top -bn1");
        assertThat(d.getTimestamp()).isEqualTo(1_000_000L);
    }

    /**
     * findQueriesByOperatorAndTimeRange returns an empty list when the iterable yields no
     * documents (Req 4.1 empty-result path).
     */
    @Test
    @SuppressWarnings("unchecked")
    void findQueriesByOperatorAndTimeRange_returnsEmptyListWhenNoResults() {
        FindIterable<Document> findIterable = mock(FindIterable.class);
        when(collection.find(any(org.bson.conversions.Bson.class))).thenReturn(findIterable);
        when(findIterable.sort(any())).thenReturn(findIterable);
        doAnswer(inv -> null).when(findIterable).forEach(any(java.util.function.Consumer.class));

        List<AssistAuditDocument> results =
                repository.findQueriesByOperatorAndTimeRange("unknown-op", 0L, 9_999_999L);

        assertThat(results).isEmpty();
    }

    /**
     * distinctOperatorIds collects non-null operator ids from QUERY records into a list (Req 1.1).
     */
    @Test
    @SuppressWarnings("unchecked")
    void distinctOperatorIds_returnsCollectedIds() {
        DistinctIterable<String> distinctIterable = mock(DistinctIterable.class);
        when(collection.distinct(eq("operatorId"), any(org.bson.conversions.Bson.class), eq(String.class)))
                .thenReturn(distinctIterable);
        doAnswer(inv -> {
            List<String> target = inv.getArgument(0);
            target.add("op-alice");
            target.add("op-bob");
            return null;
        }).when(distinctIterable).into(any());

        List<String> ids = repository.distinctOperatorIds();

        assertThat(ids).containsExactlyInAnyOrder("op-alice", "op-bob");
    }

    /**
     * distinctOperatorIds returns an empty list when no QUERY records have a non-null operatorId
     * (Req 1.1 empty-store path).
     */
    @Test
    @SuppressWarnings("unchecked")
    void distinctOperatorIds_returnsEmptyListWhenNone() {
        DistinctIterable<String> distinctIterable = mock(DistinctIterable.class);
        when(collection.distinct(eq("operatorId"), any(org.bson.conversions.Bson.class), eq(String.class)))
                .thenReturn(distinctIterable);
        doAnswer(inv -> null).when(distinctIterable).into(any());

        List<String> ids = repository.distinctOperatorIds();

        assertThat(ids).isEmpty();
    }

    /**
     * earliestQueryTimestampForOperator returns the timestamp from the first document returned
     * (Req 7.4).
     */
    @Test
    @SuppressWarnings("unchecked")
    void earliestQueryTimestampForOperator_returnsTimestampWhenPresent() {
        Document raw = new Document("_id", new ObjectId())
                .append("operatorId", "op-alice")
                .append("eventType", "QUERY")
                .append("timestamp", 500_000L);

        FindIterable<Document> findIterable = mock(FindIterable.class);
        when(collection.find(any(org.bson.conversions.Bson.class))).thenReturn(findIterable);
        when(findIterable.sort(any())).thenReturn(findIterable);
        when(findIterable.limit(1)).thenReturn(findIterable);
        when(findIterable.first()).thenReturn(raw);

        Optional<Long> result = repository.earliestQueryTimestampForOperator("op-alice");

        assertThat(result).hasValue(500_000L);
    }

    /**
     * earliestQueryTimestampForOperator returns empty when no matching QUERY record exists (Req 7.4).
     */
    @Test
    @SuppressWarnings("unchecked")
    void earliestQueryTimestampForOperator_returnsEmptyWhenNone() {
        FindIterable<Document> findIterable = mock(FindIterable.class);
        when(collection.find(any(org.bson.conversions.Bson.class))).thenReturn(findIterable);
        when(findIterable.sort(any())).thenReturn(findIterable);
        when(findIterable.limit(1)).thenReturn(findIterable);
        when(findIterable.first()).thenReturn(null);

        Optional<Long> result = repository.earliestQueryTimestampForOperator("unknown-op");

        assertThat(result).isEmpty();
    }
}
