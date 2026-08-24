package com.intentguard.assist;

import java.util.List;

import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
}
