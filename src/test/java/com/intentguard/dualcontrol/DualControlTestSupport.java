package com.intentguard.dualcontrol;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.mockito.Mockito.mock;

import com.intentguard.blastradius.GuardrailConfig;
import com.intentguard.blastradius.GuardrailConfigService;
import com.intentguard.domain.Actor;
import com.intentguard.domain.AgentRiskMarkers;
import com.intentguard.domain.CommandEvent;
import com.intentguard.domain.InputOrigin;
import com.intentguard.domain.IntentSource;
import com.intentguard.domain.SignalSource;
import com.intentguard.persistence.AuditHistoryDocument;
import com.intentguard.persistence.AuditHistoryRepository;
import com.intentguard.persistence.GuardrailConfigRepository;
import com.intentguard.persistence.PendingApprovalRepository;
import com.mongodb.client.MongoDatabase;

/**
 * Shared deterministic in-memory fakes and builders for the {@link DualControlService} property
 * tests (Properties 18-22). Every fake subclasses the real repository/service with a mocked
 * {@link MongoDatabase} (no I/O), and the service is driven by a {@link Clock#fixed fixed clock} so
 * approval timing is reproducible.
 */
final class DualControlTestSupport {

    /** Default confirmation timeout used across the dual-control property tests. */
    static final long TIMEOUT_MS = 300_000L;

    private DualControlTestSupport() {
    }

    /**
     * A {@link DualControlService} backed by the given fakes, stamped by a fixed clock at
     * {@code nowMs}, serving the given active {@link GuardrailConfig}.
     */
    static DualControlService service(
            PendingApprovalRepository approvals,
            AuditHistoryRepository audit,
            GuardrailConfig config,
            long nowMs) {
        DualControlService service =
                new DualControlService(approvals, audit, new FixedGuardrailConfigService(config));
        service.setClock(Clock.fixed(Instant.ofEpochMilli(nowMs), ZoneOffset.UTC));
        return service;
    }

    /** A valid config at version 1 with the given timeout and capability scopes. */
    static GuardrailConfig config(long timeoutMs, Map<String, List<String>> capabilityScopes) {
        return new GuardrailConfig(
                1,
                List.of(),
                100,
                List.of(),
                0.90,
                timeoutMs,
                capabilityScopes,
                Map.of(),
                "admin",
                0L);
    }

    /** A HUMAN Command_Event. */
    static CommandEvent humanEvent(String eventId, String userId, String commandText, long timestamp) {
        return event(eventId, Actor.human(userId), commandText, timestamp);
    }

    /** An AGENT Command_Event acting on behalf of {@code principalId}. */
    static CommandEvent agentEvent(
            String eventId, String agentId, String principalId, String commandText, long timestamp) {
        return event(eventId, Actor.agent(agentId, principalId), commandText, timestamp);
    }

    static CommandEvent event(String eventId, Actor actor, String commandText, long timestamp) {
        return new CommandEvent(
                eventId,
                actor,
                "sess-1",
                commandText,
                "/repo",
                "repo",
                Map.of(),
                timestamp,
                InputOrigin.TYPED,
                SignalSource.HOOK,
                IntentSource.NONE,
                AgentRiskMarkers.none());
    }

    /** In-memory {@link PendingApprovalRepository} keyed by event id. */
    static final class InMemoryPendingApprovalRepository extends PendingApprovalRepository {
        private final Map<String, PendingApproval> byEventId = new HashMap<>();

        InMemoryPendingApprovalRepository() {
            super(mock(MongoDatabase.class));
        }

        @Override
        public void save(PendingApproval approval) {
            byEventId.put(approval.eventId(), approval);
        }

        @Override
        public Optional<PendingApproval> findByEventId(String eventId) {
            return Optional.ofNullable(byEventId.get(eventId));
        }

        @Override
        public List<PendingApproval> findByStatus(ApprovalStatus status) {
            return byEventId.values().stream()
                    .filter(a -> a.status() == status)
                    .sorted((a, b) -> Long.compare(a.raisedAt(), b.raisedAt()))
                    .toList();
        }
    }

    /** Recording {@link AuditHistoryRepository} that captures every saved record in order. */
    static final class RecordingAuditHistoryRepository extends AuditHistoryRepository {
        private final List<AuditHistoryDocument> saved = new ArrayList<>();

        RecordingAuditHistoryRepository() {
            super(mock(MongoDatabase.class));
        }

        @Override
        public void save(AuditHistoryDocument record) {
            saved.add(record);
        }

        List<AuditHistoryDocument> saved() {
            return saved;
        }

        /** The reason codes of every recorded audit document, in save order. */
        List<String> reasonCodes() {
            List<String> reasons = new ArrayList<>(saved.size());
            for (AuditHistoryDocument doc : saved) {
                reasons.add(doc.getReasonCode());
            }
            return reasons;
        }

        /** The record types of every recorded audit document, in save order. */
        List<String> recordTypes() {
            List<String> types = new ArrayList<>(saved.size());
            for (AuditHistoryDocument doc : saved) {
                types.add(doc.getRecordType());
            }
            return types;
        }
    }

    /** A {@link GuardrailConfigService} that always serves a fixed (possibly {@code null}) config. */
    static final class FixedGuardrailConfigService extends GuardrailConfigService {
        private final GuardrailConfig config;

        FixedGuardrailConfigService(GuardrailConfig config) {
            super(mock(GuardrailConfigRepository.class));
            this.config = config;
        }

        @Override
        public Optional<GuardrailConfig> getActiveConfig() {
            return Optional.ofNullable(config);
        }
    }
}
