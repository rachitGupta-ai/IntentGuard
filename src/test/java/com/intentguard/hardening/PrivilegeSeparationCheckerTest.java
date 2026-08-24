package com.intentguard.hardening;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.mock;

import com.intentguard.domain.CorrectiveAction;
import com.intentguard.persistence.AuditHistoryDocument;
import com.intentguard.persistence.AuditHistoryRepository;
import com.mongodb.client.MongoDatabase;

/**
 * Unit tests for {@link PrivilegeSeparationChecker} (Req 9.3, 9.4).
 *
 * <p>Asserts the engine enters the enforcing state when it runs under a dedicated service account
 * distinct from every monitored user, and refuses to enter the enforcing state and records the
 * failure when privilege-separation cannot be verified (missing service account, or the service
 * account is itself a monitored user).
 */
class PrivilegeSeparationCheckerTest {

    private static final long NOW = 1_710_000_000_000L;

    private final RecordingAuditHistoryRepository audit = new RecordingAuditHistoryRepository();

    private PrivilegeSeparationChecker checker(String serviceAccount, Set<String> monitoredUsers) {
        PrivilegeSeparationChecker c =
                new PrivilegeSeparationChecker(audit, serviceAccount, monitoredUsers);
        c.setClock(Clock.fixed(Instant.ofEpochMilli(NOW), ZoneOffset.UTC));
        return c;
    }

    @Test
    void entersEnforcingStateWhenServiceAccountIsDistinctFromMonitoredUsers() {
        PrivilegeSeparationChecker checker = checker("intentguard-svc", Set.of("alice", "bob"));

        boolean enforcing = checker.enterEnforcingState();

        assertThat(enforcing).isTrue();
        assertThat(checker.isEnforcing()).isTrue();
        assertThat(checker.isSeparated()).isTrue();
        assertThat(audit.saved).isEmpty();
    }

    @Test
    void refusesEnforcingStateAndRecordsFailureWhenServiceAccountIsAMonitoredUser() {
        PrivilegeSeparationChecker checker = checker("alice", Set.of("alice", "bob"));

        boolean enforcing = checker.enterEnforcingState();

        assertThat(enforcing).isFalse();
        assertThat(checker.isEnforcing()).isFalse();
        assertThat(checker.isSeparated()).isFalse();

        assertThat(audit.saved).hasSize(1);
        AuditHistoryDocument record = audit.saved.get(0);
        assertThat(record.getRecordType()).isEqualTo(PrivilegeSeparationChecker.RECORD_TYPE);
        assertThat(record.getReasonCode())
                .isEqualTo(PrivilegeSeparationChecker.REASON_ENFORCEMENT_REFUSED);
        assertThat(record.getCorrectiveAction()).isEqualTo(CorrectiveAction.BLOCK.name());
        assertThat(record.getTimestamp()).isEqualTo(NOW);
        assertThat(record.getExplanation())
                .contains(PrivilegeSeparationResult.REASON_ACCOUNT_MONITORED);
    }

    @Test
    void refusesEnforcingStateAndRecordsFailureWhenNoServiceAccountConfigured() {
        PrivilegeSeparationChecker checker = checker("   ", Set.of("alice"));

        boolean enforcing = checker.enterEnforcingState();

        assertThat(enforcing).isFalse();
        assertThat(checker.isEnforcing()).isFalse();
        assertThat(audit.saved).hasSize(1);
        assertThat(audit.saved.get(0).getExplanation())
                .contains(PrivilegeSeparationResult.REASON_NO_SERVICE_ACCOUNT);
    }

    private static final class RecordingAuditHistoryRepository extends AuditHistoryRepository {
        private final List<AuditHistoryDocument> saved = new ArrayList<>();

        RecordingAuditHistoryRepository() {
            super(mock(MongoDatabase.class));
        }

        @Override
        public void save(AuditHistoryDocument record) {
            saved.add(record);
        }
    }
}
