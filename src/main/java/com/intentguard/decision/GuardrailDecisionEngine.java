package com.intentguard.decision;

import java.util.Objects;
import java.util.OptionalDouble;

import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import com.intentguard.config.ThresholdConfiguration;
import com.intentguard.domain.CommandEvent;
import com.intentguard.domain.CorrectiveAction;
import com.intentguard.domain.Decision;
import com.intentguard.domain.DivergenceResult;
import com.intentguard.domain.ProfileState;

/**
 * The wrapping {@link DecisionEngine} that composes the enforcement guardrail layer around the
 * unchanged {@link DefaultDecisionEngine} (Req 1). It runs the new pre-threshold and post-threshold
 * stages of the design's ordered chain and delegates the threshold-map / learning-clamp /
 * agent-containment stages to the delegate, so there is a single source of truth for that logic.
 *
 * <p>Marked {@link Primary} so it is injected wherever a {@link DecisionEngine} is required (the
 * pipeline picks it up without any wiring change); the delegate remains a distinct bean.
 *
 * <p>Ordered chain (Req 1.1), with the two composition mechanisms of the design:
 * <ol>
 *   <li><strong>tamper override</strong> - short-circuit {@code BLOCK}, never softened (Req 1.5,
 *       1.7);</li>
 *   <li><strong>command policy {@code DENY}</strong> - short-circuit {@code BLOCK} evaluated
 *       <em>before</em> the learning clamp so it is never downgraded (Req 1.2, 1.3, 2.7);</li>
 *   <li><strong>blast-radius / protected-target</strong> - a block-on-access target short-circuits
 *       to {@code BLOCK}; a destructive-verb match raises the Divergence_Score floor fed to the
 *       threshold map (Req 3.3, 3.6);</li>
 *   <li><strong>threshold map &rarr; learning clamp &rarr; agent containment</strong> - delegated to
 *       {@link DefaultDecisionEngine} (Req 1.8);</li>
 *   <li><strong>Corrective_Action floor</strong> - the most restrictive of the delegate action and
 *       every floor raised by a guardrail wins ({@link CorrectiveAction#max}); {@code REQUIRE_CONFIRM}
 *       policy, blast-radius, and out-of-capability-scope contributors each raise the floor to at
 *       least {@code ASK} (Req 1.4, 2.8, 3.2, 3.4, 3.5, 3.8, 4.8);</li>
 *   <li><strong>policy {@code ALLOW}</strong> - suppresses a threshold-map {@code BLOCK} for the
 *       event (capped at {@code ALLOW}) while every higher short-circuit still precedes it
 *       (Req 2.9);</li>
 *   <li><strong>dual-control</strong> - a {@code PENDING} confirmation withholds the event (raises
 *       the floor to at least {@code ASK}, never {@code ALLOW}) until a distinct Approver confirms
 *       (Req 4.1, 4.2); a {@code TIMED_OUT} confirmation resolves to {@code BLOCK} (Req 4.5).</li>
 * </ol>
 */
@Component
@Primary
public class GuardrailDecisionEngine implements DecisionEngine {

    /** Tamper override forced a block (mirrors {@link DefaultDecisionEngine#REASON_TAMPER}). */
    static final String REASON_TAMPER = "REJECTED_TAMPER";

    /** A matching {@code DENY} PolicyRule short-circuited to a block (Req 2.7). */
    static final String REASON_POLICY_DENY = "POLICY_DENY";

    /** A matching {@code REQUIRE_CONFIRM} PolicyRule raised the floor to ask (Req 2.8). */
    static final String REASON_POLICY_REQUIRE_CONFIRM = "POLICY_REQUIRE_CONFIRM";

    /** A matching {@code ALLOW} PolicyRule suppressed a threshold-map block (Req 2.9). */
    static final String REASON_POLICY_ALLOW = "POLICY_ALLOW";

    /** A block-on-access protected target short-circuited to a block (Req 3.3). */
    static final String REASON_BLAST_RADIUS_BLOCK_ON_ACCESS = "BLAST_RADIUS_BLOCK_ON_ACCESS";

    /** A blast-radius / protected-target / mass-op / indeterminate floor raised the action (Req 3). */
    static final String REASON_BLAST_RADIUS_ASK = "BLAST_RADIUS_ASK";

    /** An Agent_Actor event outside its capability scope raised the floor to ask (Req 4.8). */
    static final String REASON_CAPABILITY_SCOPE = "CAPABILITY_SCOPE";

    /**
     * A dual-control confirmation is pending for a Break-glass / dual-control-required event, so the
     * event is withheld (raised to at least {@code ASK}, never {@code ALLOW}) until a distinct
     * Approver confirms or it times out (Req 4.1, 4.2).
     */
    static final String REASON_DUAL_CONTROL_PENDING = "DUAL_CONTROL_PENDING";

    /** An unconfirmed dual-control request timed out to a block (Req 4.5). */
    static final String REASON_DUAL_CONTROL_TIMEOUT = "DUAL_CONTROL_TIMEOUT";

    private final DefaultDecisionEngine delegate;
    private final TamperClassifier tamperClassifier;

    public GuardrailDecisionEngine(DefaultDecisionEngine delegate, TamperClassifier tamperClassifier) {
        this.delegate = Objects.requireNonNull(delegate, "delegate must not be null");
        this.tamperClassifier =
                Objects.requireNonNull(tamperClassifier, "tamperClassifier must not be null");
    }

    /**
     * Retained interface method: reduces to the delegate's decision by composing with an
     * {@linkplain GuardrailContext#empty() empty} guardrail context. Existing callers and tests
     * keep compiling unchanged.
     */
    @Override
    public Decision decide(
            CommandEvent event,
            DivergenceResult result,
            ThresholdConfiguration cfg,
            ProfileState profileState,
            boolean humanSessionOpen) {
        return decide(event, result, cfg, profileState, humanSessionOpen, GuardrailContext.empty());
    }

    @Override
    public Decision onAskTimeout(Decision pending) {
        return delegate.onAskTimeout(pending);
    }

    /**
     * Additive overload applying the full ordered guardrail chain and floor model (Req 1.1-1.8).
     * See the class documentation for the stage ordering.
     */
    public Decision decide(
            CommandEvent event,
            DivergenceResult result,
            ThresholdConfiguration cfg,
            ProfileState profileState,
            boolean humanSessionOpen,
            GuardrailContext guardrail) {
        Objects.requireNonNull(event, "event must not be null");
        Objects.requireNonNull(result, "result must not be null");
        Objects.requireNonNull(guardrail, "guardrail must not be null");

        // 1. Tamper override: short-circuit BLOCK, never softened, ahead of everything (Req 1.5,1.7).
        if (tamperClassifier.isTamperAttempt(event)) {
            return new Decision(CorrectiveAction.BLOCK, 1.0, REASON_TAMPER);
        }

        // 2. Command policy DENY: short-circuit BLOCK, evaluated BEFORE the learning clamp so it is
        //    never downgraded to ask (Req 1.2, 1.3, 2.7).
        if (guardrail.policy().isDeny()) {
            return new Decision(CorrectiveAction.BLOCK, result.composite(), REASON_POLICY_DENY);
        }

        // 3a. Block-on-access protected target: short-circuit BLOCK (Req 3.3).
        if (guardrail.blastRadius().blockOnAccessHit()) {
            return new Decision(
                    CorrectiveAction.BLOCK, result.composite(), REASON_BLAST_RADIUS_BLOCK_ON_ACCESS);
        }

        // 3b. Destructive-verb Divergence_Score floor fed to the threshold map (Req 3.6).
        DivergenceResult effective = applyScoreFloor(result, guardrail.blastRadius().scoreFloor());

        // 4-6. Delegate the threshold map, learning clamp, and agent containment to the UNCHANGED
        //      engine (Req 1.8).
        Decision base = delegate.decide(event, effective, cfg, profileState, humanSessionOpen);

        CorrectiveAction action = base.action();
        String reason = base.reasonCode();

        // Policy ALLOW suppresses a threshold-map block for this event (Req 2.9). Higher
        // short-circuits (tamper/DENY/block-on-access) already returned above, so this only caps a
        // score-derived block.
        if (guardrail.policy().isAllow() && action == CorrectiveAction.BLOCK) {
            action = CorrectiveAction.ALLOW;
            reason = REASON_POLICY_ALLOW;
        }

        // Corrective_Action floor: the most restrictive contributor wins (Req 1.4). Each raise only
        // changes the reason when it strictly increases restrictiveness, so an earlier-in-chain
        // contributor wins ties.
        Contribution current = new Contribution(action, reason);

        // REQUIRE_CONFIRM policy -> ASK floor (Req 2.8).
        if (guardrail.policy().isRequireConfirm()) {
            current = current.raiseTo(CorrectiveAction.ASK, REASON_POLICY_REQUIRE_CONFIRM);
        }
        // Blast-radius / protected-target / mass-op / indeterminate floor (Req 3.2, 3.4, 3.5, 3.8).
        current = current.raiseTo(guardrail.blastRadius().floor(), REASON_BLAST_RADIUS_ASK);
        // Out-of-capability-scope agent action -> ASK floor (Req 4.8).
        if (!guardrail.withinCapabilityScope()) {
            current = current.raiseTo(CorrectiveAction.ASK, REASON_CAPABILITY_SCOPE);
        }
        // Dual-control stage (Req 4). A PENDING confirmation for a Break-glass (block-range) or
        // dual-control-required event withholds execution: the floor is raised to at least ASK so
        // the event is never ALLOWed while awaiting a distinct Approver (Req 4.1, 4.2). A TIMED_OUT
        // confirmation resolves to BLOCK (Req 4.5). These use raiseTo, so a stronger contributor
        // (e.g. a threshold BLOCK for the block-range score, or the timeout BLOCK) still wins.
        if (guardrail.dualControl() == DualControlStatus.PENDING) {
            current = current.raiseTo(CorrectiveAction.ASK, REASON_DUAL_CONTROL_PENDING);
        }
        if (guardrail.dualControl() == DualControlStatus.TIMED_OUT) {
            current = current.raiseTo(CorrectiveAction.BLOCK, REASON_DUAL_CONTROL_TIMEOUT);
        }

        return new Decision(current.action(), base.score(), current.reason());
    }

    /**
     * Returns a {@link DivergenceResult} whose composite is raised to at least {@code scoreFloor}
     * (leaving the component breakdown and exclusions intact), or the original result when no floor
     * is present or the floor is already met. This is how a destructive-verb match feeds the
     * threshold map (Req 3.6).
     */
    private static DivergenceResult applyScoreFloor(DivergenceResult result, OptionalDouble scoreFloor) {
        if (scoreFloor.isEmpty() || scoreFloor.getAsDouble() <= result.composite()) {
            return result;
        }
        return new DivergenceResult(
                scoreFloor.getAsDouble(), result.components(), result.excluded());
    }

    /** A partial (action, reason) pair carried through the floor-raising stages. */
    private record Contribution(CorrectiveAction action, String reason) {
        /**
         * Raises this contribution to {@code floor}: when {@code floor} is strictly more restrictive
         * the floor's action and reason win; otherwise this contribution is unchanged (the
         * earlier-in-chain contributor keeps the reason on a tie).
         */
        Contribution raiseTo(CorrectiveAction floor, String floorReason) {
            return floor.ordinal() > action.ordinal()
                    ? new Contribution(floor, floorReason)
                    : this;
        }
    }
}
