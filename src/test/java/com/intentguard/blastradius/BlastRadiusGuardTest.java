package com.intentguard.blastradius;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;

import com.intentguard.domain.Actor;
import com.intentguard.domain.CommandEvent;
import com.intentguard.domain.CorrectiveAction;

/**
 * Unit tests for {@link BlastRadiusGuard} covering the protected-target, mass-operation,
 * destructive-verb, and indeterminate fail-safe behaviors (Req 3.2, 3.3, 3.4, 3.5, 3.6, 3.8) and
 * the {@link BlastRadiusGuard#estimate(CommandEvent)} heuristics.
 */
class BlastRadiusGuardTest {

    private final BlastRadiusGuard guard = new BlastRadiusGuard();

    // --- helpers --------------------------------------------------------------------------------

    private static CommandEvent event(String commandText) {
        return event(commandText, "/home/alice/project");
    }

    private static CommandEvent event(String commandText, String cwd) {
        return new CommandEvent(
                "evt-1",
                Actor.human("alice"),
                "session-1",
                commandText,
                cwd,
                null,
                null,
                1_710_000_000_000L,
                null,
                null,
                null,
                null);
    }

    private static GuardrailConfig config(
            List<ProtectedTarget> targets,
            int massOperationLimit,
            List<String> destructiveVerbPatterns) {
        return new GuardrailConfig(
                1,
                targets,
                massOperationLimit,
                destructiveVerbPatterns,
                GuardrailConfig.DEFAULT_DESTRUCTIVE_OPERATION_FLOOR,
                GuardrailConfig.DEFAULT_DUAL_CONTROL_CONFIRMATION_TIMEOUT_MS,
                Map.of(),
                Map.of(),
                "admin",
                1_710_000_000_000L);
    }

    private static GuardrailConfig emptyConfig() {
        return config(List.of(), GuardrailConfig.DEFAULT_MASS_OPERATION_LIMIT, List.of());
    }

    // --- protected-target: read/write raises the ASK floor (Req 3.2, 3.4) ----------------------

    @Test
    void protectedPathAccessRaisesFloorToAsk() {
        ProtectedTarget sshKeys = new ProtectedTarget("ssh-keys", TargetKind.PATH, "~/.ssh/**", false);
        GuardrailConfig cfg = config(List.of(sshKeys), 100, List.of());

        BlastRadiusResult result = guard.evaluate(event("cat ~/.ssh/id_rsa"), cfg);

        assertThat(result.floor()).isEqualTo(CorrectiveAction.ASK);
        assertThat(result.blockOnAccessHit()).isFalse();
        assertThat(result.indeterminate()).isFalse();
        assertThat(result.triggeredGuardrailIds()).contains("ssh-keys");
    }

    @Test
    void protectedHostAccessRaisesFloorToAsk() {
        ProtectedTarget prodHost = new ProtectedTarget("prod-host", TargetKind.HOST, "prod-*.db", false);
        GuardrailConfig cfg = config(List.of(prodHost), 100, List.of());

        BlastRadiusResult result = guard.evaluate(event("ssh deploy@prod-1.db"), cfg);

        assertThat(result.floor()).isEqualTo(CorrectiveAction.ASK);
        assertThat(result.triggeredGuardrailIds()).contains("prod-host");
    }

    @Test
    void protectedResourceAccessRaisesFloorToAsk() {
        ProtectedTarget prodDb = new ProtectedTarget("prod-db", TargetKind.RESOURCE, "db:prod-*", false);
        GuardrailConfig cfg = config(List.of(prodDb), 100, List.of());

        BlastRadiusResult result = guard.evaluate(event("psql db:prod-1"), cfg);

        assertThat(result.floor()).isEqualTo(CorrectiveAction.ASK);
        assertThat(result.triggeredGuardrailIds()).contains("prod-db");
    }

    @Test
    void nonMatchingProtectedTargetDoesNotRaiseFloor() {
        ProtectedTarget sshKeys = new ProtectedTarget("ssh-keys", TargetKind.PATH, "~/.ssh/**", false);
        GuardrailConfig cfg = config(List.of(sshKeys), 100, List.of());

        BlastRadiusResult result = guard.evaluate(event("cat README.md"), cfg);

        assertThat(result.floor()).isEqualTo(CorrectiveAction.ALLOW);
        assertThat(result.blockOnAccessHit()).isFalse();
        assertThat(result.triggeredGuardrailIds()).doesNotContain("ssh-keys");
    }

    // --- protected-target: block-on-access short-circuits BLOCK (Req 3.3) -----------------------

    @Test
    void blockOnAccessTargetSetsBlockOnAccessHit() {
        ProtectedTarget canary =
                new ProtectedTarget("canary-aws", TargetKind.PATH, "/opt/creds/aws.canary", true);
        GuardrailConfig cfg = config(List.of(canary), 100, List.of());

        BlastRadiusResult result = guard.evaluate(event("cat /opt/creds/aws.canary"), cfg);

        assertThat(result.blockOnAccessHit()).isTrue();
        assertThat(result.triggeredGuardrailIds()).contains("canary-aws");
    }

    // --- mass-operation limit (Req 3.5) ---------------------------------------------------------

    @Test
    void blastRadiusOverMassOperationLimitRaisesFloorToAsk() {
        // A wildcard operation estimates an unbounded (over-limit) blast radius.
        BlastRadiusResult result = guard.evaluate(event("rm *.log"), emptyConfig());

        assertThat(result.floor()).isEqualTo(CorrectiveAction.ASK);
        assertThat(result.indeterminate()).isFalse();
        assertThat(result.triggeredGuardrailIds()).contains(BlastRadiusGuard.MASS_OPERATION_TRIGGER_ID);
    }

    @Test
    void blastRadiusAtOrBelowLimitDoesNotRaiseFloor() {
        BlastRadiusResult result = guard.evaluate(event("cat notes.txt"), emptyConfig());

        assertThat(result.floor()).isEqualTo(CorrectiveAction.ALLOW);
        assertThat(result.indeterminate()).isFalse();
        assertThat(result.triggeredGuardrailIds()).isEmpty();
    }

    // --- destructive-verb score floor (Req 3.6) -------------------------------------------------

    @Test
    void destructiveVerbRaisesScoreFloorToConfiguredFloor() {
        GuardrailConfig cfg = config(List.of(), 100, List.of("rm -rf", "DROP TABLE"));

        BlastRadiusResult result = guard.evaluate(event("rm -rf build"), cfg);

        assertThat(result.scoreFloor()).isPresent();
        assertThat(result.scoreFloor().getAsDouble())
                .isEqualTo(GuardrailConfig.DEFAULT_DESTRUCTIVE_OPERATION_FLOOR);
        assertThat(result.triggeredGuardrailIds())
                .contains(BlastRadiusGuard.DESTRUCTIVE_VERB_TRIGGER_ID);
    }

    @Test
    void destructiveVerbMatchIsCaseInsensitive() {
        GuardrailConfig cfg = config(List.of(), 100, List.of("DROP TABLE"));

        BlastRadiusResult result = guard.evaluate(event("psql -c 'drop table users'"), cfg);

        assertThat(result.scoreFloor()).isPresent();
        assertThat(result.scoreFloor().getAsDouble())
                .isEqualTo(GuardrailConfig.DEFAULT_DESTRUCTIVE_OPERATION_FLOOR);
    }

    @Test
    void nonDestructiveCommandHasNoScoreFloor() {
        GuardrailConfig cfg = config(List.of(), 100, List.of("rm -rf", "DROP TABLE"));

        BlastRadiusResult result = guard.evaluate(event("ls -la"), cfg);

        assertThat(result.scoreFloor()).isEmpty();
        assertThat(result.triggeredGuardrailIds())
                .doesNotContain(BlastRadiusGuard.DESTRUCTIVE_VERB_TRIGGER_ID);
    }

    // --- indeterminate fail-safe (Req 3.8) ------------------------------------------------------

    @Test
    void indeterminateBlastRadiusFailsSafeToAskWithFlag() {
        // A blank command cannot be estimated => indeterminate fail-safe.
        BlastRadiusResult result = guard.evaluate(event("   "), emptyConfig());

        assertThat(result.floor()).isEqualTo(CorrectiveAction.ASK);
        assertThat(result.indeterminate()).isTrue();
        assertThat(result.triggeredGuardrailIds())
                .contains(BlastRadiusGuard.INDETERMINATE_TRIGGER_ID);
    }

    // --- combined behavior ----------------------------------------------------------------------

    @Test
    void blockOnAccessTakesPrecedenceAlongsideAskFloorContributors() {
        ProtectedTarget canary =
                new ProtectedTarget("canary-aws", TargetKind.PATH, "/opt/creds/aws.canary", true);
        GuardrailConfig cfg = config(List.of(canary), 100, List.of("rm -rf"));

        // Recursive + wildcard + block-on-access + destructive verb all in one command.
        BlastRadiusResult result = guard.evaluate(event("rm -rf /opt/creds/aws.canary"), cfg);

        assertThat(result.blockOnAccessHit()).isTrue();
        assertThat(result.scoreFloor()).isPresent();
        assertThat(result.triggeredGuardrailIds())
                .contains("canary-aws", BlastRadiusGuard.DESTRUCTIVE_VERB_TRIGGER_ID);
    }

    // --- estimate() heuristics ------------------------------------------------------------------

    @Test
    void estimateRecursiveFlagIsMassOperation() {
        BlastRadius radius = guard.estimate(event("rm -rf /tmp/build"));

        assertThat(radius.indeterminate()).isFalse();
        assertThat(radius.affectedCount()).isEqualTo(BlastRadiusGuard.MASS_OPERATION_AFFECTED_COUNT);
    }

    @Test
    void estimateCombinedRecursiveFlagIsMassOperation() {
        BlastRadius radius = guard.estimate(event("cp -R src dest"));

        assertThat(radius.affectedCount()).isEqualTo(BlastRadiusGuard.MASS_OPERATION_AFFECTED_COUNT);
    }

    @Test
    void estimateWildcardIsMassOperation() {
        BlastRadius radius = guard.estimate(event("rm *.tmp"));

        assertThat(radius.indeterminate()).isFalse();
        assertThat(radius.affectedCount()).isEqualTo(BlastRadiusGuard.MASS_OPERATION_AFFECTED_COUNT);
    }

    @Test
    void estimateBulkSqlWithoutWhereIsMassOperation() {
        BlastRadius deleteRadius = guard.estimate(event("DELETE FROM users"));
        BlastRadius updateRadius = guard.estimate(event("UPDATE users SET active = 0"));

        assertThat(deleteRadius.affectedCount())
                .isEqualTo(BlastRadiusGuard.MASS_OPERATION_AFFECTED_COUNT);
        assertThat(updateRadius.affectedCount())
                .isEqualTo(BlastRadiusGuard.MASS_OPERATION_AFFECTED_COUNT);
    }

    @Test
    void estimateBulkSqlWithWhereIsBounded() {
        BlastRadius radius = guard.estimate(event("DELETE FROM users WHERE id = 5"));

        assertThat(radius.indeterminate()).isFalse();
        assertThat(radius.affectedCount()).isEqualTo(1);
    }

    @Test
    void estimateSimpleCommandIsBounded() {
        BlastRadius radius = guard.estimate(event("cat notes.txt"));

        assertThat(radius.indeterminate()).isFalse();
        assertThat(radius.affectedCount()).isEqualTo(1);
    }

    @Test
    void estimateBlankCommandIsUnknown() {
        BlastRadius radius = guard.estimate(event("   "));

        assertThat(radius.indeterminate()).isTrue();
        assertThat(radius.affectedCount()).isZero();
    }
}
