package com.intentguard.domain;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;

import static com.intentguard.domain.CorrectiveAction.ALLOW;
import static com.intentguard.domain.CorrectiveAction.ASK;
import static com.intentguard.domain.CorrectiveAction.BLOCK;

/**
 * Unit tests for the {@link CorrectiveAction} floor combinator helpers ({@code max} and
 * {@code raiseTo}) added for the guardrail Corrective_Action floor model (Req 1.4).
 *
 * <p>Covers all ordered pairs, idempotence and commutativity of {@code max}, and that
 * {@code raiseTo} never lowers restrictiveness, using the ordering {@code ALLOW < ASK < BLOCK}.
 */
class CorrectiveActionTest {

    @Test
    void maxReturnsMostRestrictiveForEveryPair() {
        // Diagonal (equal inputs) and every off-diagonal ordered pair.
        assertThat(CorrectiveAction.max(ALLOW, ALLOW)).isEqualTo(ALLOW);
        assertThat(CorrectiveAction.max(ALLOW, ASK)).isEqualTo(ASK);
        assertThat(CorrectiveAction.max(ALLOW, BLOCK)).isEqualTo(BLOCK);

        assertThat(CorrectiveAction.max(ASK, ALLOW)).isEqualTo(ASK);
        assertThat(CorrectiveAction.max(ASK, ASK)).isEqualTo(ASK);
        assertThat(CorrectiveAction.max(ASK, BLOCK)).isEqualTo(BLOCK);

        assertThat(CorrectiveAction.max(BLOCK, ALLOW)).isEqualTo(BLOCK);
        assertThat(CorrectiveAction.max(BLOCK, ASK)).isEqualTo(BLOCK);
        assertThat(CorrectiveAction.max(BLOCK, BLOCK)).isEqualTo(BLOCK);
    }

    @Test
    void maxIsIdempotent() {
        for (CorrectiveAction action : CorrectiveAction.values()) {
            assertThat(CorrectiveAction.max(action, action)).isEqualTo(action);
        }
    }

    @Test
    void maxIsCommutative() {
        for (CorrectiveAction a : CorrectiveAction.values()) {
            for (CorrectiveAction b : CorrectiveAction.values()) {
                assertThat(CorrectiveAction.max(a, b)).isEqualTo(CorrectiveAction.max(b, a));
            }
        }
    }

    @Test
    void maxAlwaysReturnsAnInputAndIsNoLessRestrictiveThanBoth() {
        for (CorrectiveAction a : CorrectiveAction.values()) {
            for (CorrectiveAction b : CorrectiveAction.values()) {
                CorrectiveAction result = CorrectiveAction.max(a, b);
                assertThat(result).isIn(a, b);
                assertThat(result.ordinal()).isGreaterThanOrEqualTo(a.ordinal());
                assertThat(result.ordinal()).isGreaterThanOrEqualTo(b.ordinal());
            }
        }
    }

    @Test
    void raiseToNeverLowersRestrictiveness() {
        for (CorrectiveAction action : CorrectiveAction.values()) {
            for (CorrectiveAction floor : CorrectiveAction.values()) {
                CorrectiveAction raised = action.raiseTo(floor);
                // Result is never less restrictive than the original action.
                assertThat(raised.ordinal()).isGreaterThanOrEqualTo(action.ordinal());
                // Result is never less restrictive than the requested floor.
                assertThat(raised.ordinal()).isGreaterThanOrEqualTo(floor.ordinal());
            }
        }
    }

    @Test
    void raiseToLeavesActionUnchangedWhenAlreadyAtOrAboveFloor() {
        assertThat(BLOCK.raiseTo(ASK)).isEqualTo(BLOCK);
        assertThat(BLOCK.raiseTo(ALLOW)).isEqualTo(BLOCK);
        assertThat(ASK.raiseTo(ALLOW)).isEqualTo(ASK);
        assertThat(ASK.raiseTo(ASK)).isEqualTo(ASK);
        assertThat(ALLOW.raiseTo(ALLOW)).isEqualTo(ALLOW);
    }

    @Test
    void raiseToLiftsActionUpToFloorWhenBelow() {
        assertThat(ALLOW.raiseTo(ASK)).isEqualTo(ASK);
        assertThat(ALLOW.raiseTo(BLOCK)).isEqualTo(BLOCK);
        assertThat(ASK.raiseTo(BLOCK)).isEqualTo(BLOCK);
    }
}
