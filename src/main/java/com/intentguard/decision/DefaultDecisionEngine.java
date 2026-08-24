package com.intentguard.decision;

import java.util.Objects;

import org.springframework.stereotype.Component;

import com.intentguard.config.ThresholdConfiguration;
import com.intentguard.domain.ActorType;
import com.intentguard.domain.CommandEvent;
import com.intentguard.domain.CorrectiveAction;
import com.intentguard.domain.Decision;
import com.intentguard.domain.DivergenceResult;
import com.intentguard.domain.ProfileState;

/**
 * Default {@link DecisionEngine} applying the ordered decision rules of the design.
 *
 * <p>Rule order (see {@link DecisionEngine}): tamper override &rarr; threshold map &rarr; learning
 * clamp &rarr; agent containment, with the ask-timeout transition handled separately in
 * {@link #onAskTimeout(Decision)}. The order matters: the tamper override short-circuits everything
 * (a tamper attempt is always a block), and the clamp/containment adjustments are layered on top of
 * the base threshold decision.
 */
@Component
public class DefaultDecisionEngine implements DecisionEngine {

    /** Tamper override forced the maximum score and a block (Req 1.6, 13.3). */
    static final String REASON_TAMPER = "REJECTED_TAMPER";

    /** Score fell in the allow range of the Threshold_Configuration (Req 7.2). */
    static final String REASON_THRESHOLD_ALLOW = "THRESHOLD_ALLOW";

    /** Score fell in the ask range of the Threshold_Configuration (Req 7.3). */
    static final String REASON_THRESHOLD_ASK = "THRESHOLD_ASK";

    /** Score fell in the block range of the Threshold_Configuration (Req 7.4). */
    static final String REASON_THRESHOLD_BLOCK = "THRESHOLD_BLOCK";

    /** A would-be block was downgraded to ask because the profile is LEARNING (Req 3.4). */
    static final String REASON_LEARNING_CLAMP = "LEARNING_CLAMP";

    /** An agent action with no open human session was raised to at least ask (Req 13.4). */
    static final String REASON_AGENT_CONTAINMENT = "AGENT_CONTAINMENT";

    /** An unconfirmed ask became a block after the confirmation timeout (Req 7.6). */
    static final String REASON_ASK_TIMEOUT = "ASK_TIMEOUT_BLOCK";

    private final TamperClassifier tamperClassifier;

    public DefaultDecisionEngine(TamperClassifier tamperClassifier) {
        this.tamperClassifier = Objects.requireNonNull(tamperClassifier, "tamperClassifier must not be null");
    }

    @Override
    public Decision decide(
            CommandEvent event,
            DivergenceResult result,
            ThresholdConfiguration cfg,
            ProfileState profileState,
            boolean humanSessionOpen) {
        Objects.requireNonNull(event, "event must not be null");
        Objects.requireNonNull(result, "result must not be null");
        Objects.requireNonNull(cfg, "cfg must not be null");
        Objects.requireNonNull(profileState, "profileState must not be null");

        // Rule 1: tamper override. A command targeting engine config/process/datastore is forced to
        // the maximum score and blocked, regardless of the computed component scores.
        if (tamperClassifier.isTamperAttempt(event)) {
            return new Decision(CorrectiveAction.BLOCK, 1.0, REASON_TAMPER);
        }

        double score = result.composite();

        // Rule 2: threshold map.
        CorrectiveAction action = mapThreshold(score, cfg);
        String reasonCode = thresholdReason(action);

        // Rule 3: learning clamp. While the profile is LEARNING, a would-be block becomes an ask.
        if (profileState == ProfileState.LEARNING && action == CorrectiveAction.BLOCK) {
            action = CorrectiveAction.ASK;
            reasonCode = REASON_LEARNING_CLAMP;
        }

        // Rule 4: agent containment. An agent with no open human session is unauthorized-by-default
        // and must receive at least an ask (never an allow).
        if (event.actorType() == ActorType.AGENT
                && !humanSessionOpen
                && action == CorrectiveAction.ALLOW) {
            action = CorrectiveAction.ASK;
            reasonCode = REASON_AGENT_CONTAINMENT;
        }

        return new Decision(action, score, reasonCode);
    }

    @Override
    public Decision onAskTimeout(Decision pending) {
        Objects.requireNonNull(pending, "pending must not be null");
        if (pending.action() != CorrectiveAction.ASK) {
            return pending;
        }
        return new Decision(CorrectiveAction.BLOCK, pending.score(), REASON_ASK_TIMEOUT);
    }

    /**
     * Maps a Divergence_Score to a Corrective_Action per the Threshold_Configuration: allow below
     * the ask threshold, ask in [askThreshold, blockThreshold), block at or above the block
     * threshold. Total over [0,1] and monotonic (a higher score never yields a less restrictive
     * action).
     */
    private static CorrectiveAction mapThreshold(double score, ThresholdConfiguration cfg) {
        if (score < cfg.askThreshold()) {
            return CorrectiveAction.ALLOW;
        }
        if (score < cfg.blockThreshold()) {
            return CorrectiveAction.ASK;
        }
        return CorrectiveAction.BLOCK;
    }

    private static String thresholdReason(CorrectiveAction action) {
        return switch (action) {
            case ALLOW -> REASON_THRESHOLD_ALLOW;
            case ASK -> REASON_THRESHOLD_ASK;
            case BLOCK -> REASON_THRESHOLD_BLOCK;
        };
    }
}
