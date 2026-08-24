package com.intentguard.snapshot;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import org.bson.BsonDocument;
import org.bson.BsonDocumentReader;
import org.bson.BsonDocumentWriter;
import org.bson.codecs.Codec;
import org.bson.codecs.DecoderContext;
import org.bson.codecs.EncoderContext;
import org.bson.codecs.configuration.CodecRegistry;
import org.junit.jupiter.api.Test;

import com.intentguard.persistence.MongoConfig;
import com.intentguard.persistence.SnapshotDocument;

/**
 * Verifies the {@link SnapshotDocument} for the {@code snapshots} collection round-trips through
 * the POJO codec registry configured in {@link MongoConfig}, including the nullable
 * {@code undoneAt} field. This confirms the undo metadata persists (Req 15.2) without a live
 * MongoDB connection.
 */
class SnapshotDocumentCodecTest {

    private final CodecRegistry registry = new MongoConfig().intentGuardCodecRegistry();

    private <T> T roundTrip(T value, Class<T> type) {
        Codec<T> codec = registry.get(type);
        BsonDocument bson = new BsonDocument();
        codec.encode(new BsonDocumentWriter(bson), value, EncoderContext.builder().build());
        return codec.decode(new BsonDocumentReader(bson), DecoderContext.builder().build());
    }

    @Test
    void snapshotDocumentRoundTripsWithNullUndoneAt() {
        SnapshotDocument doc = new SnapshotDocument();
        doc.setEventId("evt-1");
        doc.setCapturedAt(1_710_000_000_000L);
        doc.setTargetPaths(List.of("/home/alice", "/tmp/x"));
        doc.setBackupLocation("intentguard-backup/file_restore/evt-1");
        doc.setUndoStrategy(UndoStrategy.FILE_RESTORE.name());
        doc.setUndone(false);
        doc.setUndoneAt(null);

        SnapshotDocument out = roundTrip(doc, SnapshotDocument.class);

        assertThat(out.getEventId()).isEqualTo("evt-1");
        assertThat(out.getCapturedAt()).isEqualTo(1_710_000_000_000L);
        assertThat(out.getTargetPaths()).containsExactly("/home/alice", "/tmp/x");
        assertThat(out.getBackupLocation()).isEqualTo("intentguard-backup/file_restore/evt-1");
        assertThat(out.getUndoStrategy()).isEqualTo("FILE_RESTORE");
        assertThat(out.isUndone()).isFalse();
        assertThat(out.getUndoneAt()).isNull();
    }

    @Test
    void snapshotDocumentRoundTripsWhenUndone() {
        SnapshotDocument doc = new SnapshotDocument();
        doc.setEventId("evt-2");
        doc.setCapturedAt(1_710_000_000_000L);
        doc.setTargetPaths(List.of("/home/alice/repo"));
        doc.setBackupLocation("intentguard-backup/git_stash/evt-2");
        doc.setUndoStrategy(UndoStrategy.GIT_STASH.name());
        doc.setUndone(true);
        doc.setUndoneAt(1_710_000_500_000L);

        SnapshotDocument out = roundTrip(doc, SnapshotDocument.class);

        assertThat(out.getUndoStrategy()).isEqualTo("GIT_STASH");
        assertThat(out.isUndone()).isTrue();
        assertThat(out.getUndoneAt()).isEqualTo(1_710_000_500_000L);
    }
}
