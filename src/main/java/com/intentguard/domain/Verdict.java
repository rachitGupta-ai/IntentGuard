package com.intentguard.domain;

import java.util.Objects;

/**
 * The enforcement verdict returned synchronously to the Shell_Hook (Req 2.2, 7). It carries the
 * Corrective_Action the hook must apply and, for {@code ASK}/{@code BLOCK}, the plain-English
 * Explanation shown to the user (Req 8.1).
 *
 * <p>On {@code BLOCK} (or an unconfirmed {@code ASK}) the hook returns non-zero and the command
 * never executes.
 *
 * @param action      the corrective action the hook must enforce
 * @param reasonCode  the decision reason code (mirrors {@link Decision#reasonCode()})
 * @param explanation the plain-English explanation for a flagged command, or {@code null} for
 *                    an {@code ALLOW}
 */
public record Verdict(CorrectiveAction action, String reasonCode, String explanation) {

    public Verdict {
        Objects.requireNonNull(action, "action must not be null");
        Objects.requireNonNull(reasonCode, "reasonCode must not be null");
    }

    /** An allow verdict; the command may proceed. */
    public static Verdict allow(String reasonCode) {
        return new Verdict(CorrectiveAction.ALLOW, reasonCode, null);
    }

    /** An ask verdict; the hook must obtain confirmation before proceeding. */
    public static Verdict ask(String reasonCode, String explanation) {
        return new Verdict(CorrectiveAction.ASK, reasonCode, explanation);
    }

    /** A block verdict; the command must not execute. */
    public static Verdict block(String reasonCode, String explanation) {
        return new Verdict(CorrectiveAction.BLOCK, reasonCode, explanation);
    }

    /** Build a verdict from a {@link Decision}, attaching an explanation for flagged actions. */
    public static Verdict from(Decision decision, String explanation) {
        Objects.requireNonNull(decision, "decision must not be null");
        return new Verdict(
                decision.action(),
                decision.reasonCode(),
                decision.action() == CorrectiveAction.ALLOW ? null : explanation);
    }

    public boolean allowsExecution() {
        return action == CorrectiveAction.ALLOW;
    }
}
