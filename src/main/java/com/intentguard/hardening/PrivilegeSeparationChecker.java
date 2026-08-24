package com.intentguard.hardening;

import java.time.Clock;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.intentguard.domain.CorrectiveAction;
import com.intentguard.persistence.AuditHistoryDocument;
import com.intentguard.persistence.AuditHistoryRepository;

import jakarta.annotation.PostConstruct;

/**
 * Startup privilege-separation check (Req 9.3, 9.4, Stretch).
 *
 * <p>When the Enforcement_Engine starts, it must verify that it runs under a dedicated service
 * account distinct from every monitored user account before entering the enforcing state (Req 9.3).
 * If separation cannot be verified, the engine refuses to enter the enforcing state and records the
 * failure in the Audit_History (Req 9.4).
 *
 * <p>The service account and the monitored-user set are injected as configuration (rather than
 * inspected from the OS) so the check is deterministic and testable. Configuration defaults are
 * chosen so a fresh context verifies successfully — the default service account
 * ({@value #DEFAULT_SERVICE_ACCOUNT}) is distinct from an empty monitored-user set — which keeps the
 * {@link PostConstruct} startup hook from breaking application context loading.
 */
@Component
public class PrivilegeSeparationChecker {

    /** Default dedicated service account; distinct from an empty monitored-user set by default. */
    public static final String DEFAULT_SERVICE_ACCOUNT = "intentguard-svc";

    /** Audit {@code recordType} marking a privilege-separation verification failure. */
    public static final String RECORD_TYPE = "PRIVILEGE_SEPARATION";

    /** Audit {@code reasonCode} marking a refusal to enter the enforcing state. */
    public static final String REASON_ENFORCEMENT_REFUSED = "PRIVILEGE_SEPARATION_ENFORCEMENT_REFUSED";

    private final AuditHistoryRepository auditHistory;
    private final String serviceAccount;
    private final Set<String> monitoredUsers;

    /** {@code true} only once separation has been verified and the engine has entered enforcement. */
    private final AtomicBoolean enforcing = new AtomicBoolean(false);

    private Clock clock = Clock.systemUTC();

    /**
     * Spring constructor. Binds the dedicated service account and comma-separated monitored users
     * from configuration, defaulting to a separated, safe posture so context loading is not broken.
     */
    @Autowired
    public PrivilegeSeparationChecker(
            AuditHistoryRepository auditHistory,
            @Value("${intentguard.guardrails.privilege-separation.service-account:"
                    + DEFAULT_SERVICE_ACCOUNT + "}") String serviceAccount,
            @Value("${intentguard.guardrails.privilege-separation.monitored-users:}")
                    String monitoredUsersCsv) {
        this(auditHistory, serviceAccount, parseCsv(monitoredUsersCsv));
    }

    /** Testable constructor taking the account and monitored-user set directly. */
    public PrivilegeSeparationChecker(
            AuditHistoryRepository auditHistory, String serviceAccount, Set<String> monitoredUsers) {
        this.auditHistory = Objects.requireNonNull(auditHistory, "auditHistory must not be null");
        this.serviceAccount = serviceAccount == null ? "" : serviceAccount.trim();
        this.monitoredUsers = monitoredUsers == null ? Set.of() : Set.copyOf(monitoredUsers);
    }

    /** Overrides the clock (used by tests for a deterministic audit timestamp). */
    public void setClock(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    /**
     * Startup hook: verifies privilege-separation and enters the enforcing state only on success
     * (Req 9.3). On failure it refuses enforcement and records the failure (Req 9.4). Guarded so a
     * failed check never throws out of context initialization.
     */
    @PostConstruct
    void verifyAtStartup() {
        enterEnforcingState();
    }

    /**
     * Runs the privilege-separation verification and transitions the engine into the enforcing state
     * only when separation holds (Req 9.3). If it does not, the enforcing flag stays {@code false}
     * and the failure is recorded in the Audit_History (Req 9.4).
     *
     * @return {@code true} if the engine entered the enforcing state, {@code false} if it refused
     */
    public boolean enterEnforcingState() {
        PrivilegeSeparationResult result = verify();
        if (result.separated()) {
            enforcing.set(true);
            return true;
        }
        enforcing.set(false);
        recordFailure(result);
        return false;
    }

    /**
     * Verifies that the configured service account is present and distinct from every monitored
     * user account, without inspecting the OS (Req 9.3).
     *
     * @return the verification result
     */
    public PrivilegeSeparationResult verify() {
        if (serviceAccount.isBlank()) {
            return new PrivilegeSeparationResult(
                    false,
                    PrivilegeSeparationResult.REASON_NO_SERVICE_ACCOUNT,
                    "No dedicated service account is configured for the Enforcement_Engine.");
        }
        if (monitoredUsers.contains(serviceAccount)) {
            return new PrivilegeSeparationResult(
                    false,
                    PrivilegeSeparationResult.REASON_ACCOUNT_MONITORED,
                    "Service account '" + serviceAccount
                            + "' is also a monitored user; privilege-separation cannot be verified.");
        }
        return new PrivilegeSeparationResult(
                true,
                PrivilegeSeparationResult.REASON_SEPARATED,
                "Service account '" + serviceAccount
                        + "' is distinct from every monitored user account.");
    }

    /** Whether the engine has verified separation and entered the enforcing state. */
    public boolean isSeparated() {
        return verify().separated();
    }

    /** Whether the engine is currently in the enforcing state. */
    public boolean isEnforcing() {
        return enforcing.get();
    }

    private void recordFailure(PrivilegeSeparationResult result) {
        AuditHistoryDocument record = new AuditHistoryDocument();
        record.setEventId("startup-privilege-separation");
        record.setUserId(serviceAccount.isBlank() ? "(unconfigured)" : serviceAccount);
        record.setTimestamp(clock.withZone(ZoneOffset.UTC).millis());
        record.setCorrectiveAction(CorrectiveAction.BLOCK.name());
        record.setRecordType(RECORD_TYPE);
        record.setReasonCode(REASON_ENFORCEMENT_REFUSED);
        record.setExplanation(
                "Refused to enter the enforcing state: " + result.detail()
                        + " (" + result.reasonCode() + ")");
        auditHistory.save(record);
    }

    private static Set<String> parseCsv(String csv) {
        Set<String> users = new LinkedHashSet<>();
        if (csv != null && !csv.isBlank()) {
            Arrays.stream(csv.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .forEach(users::add);
        }
        return users;
    }
}
