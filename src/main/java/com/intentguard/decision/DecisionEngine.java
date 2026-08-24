package com.intentguard.decision;

import com.intentguard.config.ThresholdConfiguration;
import com.intentguard.domain.CommandEvent;
import com.intentguard.domain.Decision;
import com.intentguard.domain.DivergenceResult;
import com.intentguard.domain.ProfileState;

/**
 * Maps a {@link DivergenceResult} to a Corrective_Action, applying the ordered decision rules of
 * the design (Req 7, Req 1, Req 3, Req 13).
 *
 * <p>The rules are applied in this order:
 * <ol>
 *   <li><strong>Tamper override</strong> - if the Command_Event targets IntentGuard configuration,
 *       process state, or the Datastore, force {@code Divergence_Score = 1.0} and {@code BLOCK}
 *       regardless of the computed component scores (Req 1.6, 13.3).</li>
 *   <li><strong>Threshold map</strong> - {@code ALLOW} when {@code score < askThreshold},
 *       {@code ASK} when {@code askThreshold <= score < blockThreshold}, {@code BLOCK} when
 *       {@code score >= blockThreshold} (Req 7.1-7.4).</li>
 *   <li><strong>Learning clamp</strong> - while the user's profile is {@code LEARNING}, downgrade
 *       any {@code BLOCK} to {@code ASK} (Req 3.4).</li>
 *   <li><strong>Agent containment</strong> - if the actor is an {@code AGENT} and no human
 *       Intent_Session is open for its principal, apply at least {@code ASK} (Req 13.4).</li>
 * </ol>
 *
 * <p>The fifth rule, <strong>ask timeout</strong> (an unconfirmed {@code ASK} becomes {@code BLOCK}
 * after the confirmation timeout, Req 7.6), is a separate transition applied when a pending
 * confirmation expires; see {@link #onAskTimeout(Decision)}.
 *
 * <p>The Threshold_Configuration is passed in per call so a hot-reloaded configuration takes effect
 * on subsequent Command_Events without a restart (Req 7.5).
 */
public interface DecisionEngine {

    /**
     * Applies the ordered decision rules to reach a Corrective_Action for a Command_Event.
     *
     * @param event            the Command_Event being decided
     * @param result           the scoring pipeline's Divergence_Score and component breakdown
     * @param cfg              the active Threshold_Configuration
     * @param profileState     the maturity state of the acting user's Behavioral_Profile
     * @param humanSessionOpen whether a human Intent_Session is open for the actor's principal;
     *                         relevant only for {@code AGENT} actors (agent containment)
     * @return the {@link Decision} with the chosen action, the score it was based on, and a reason
     *     code identifying which rule produced it
     */
    Decision decide(
            CommandEvent event,
            DivergenceResult result,
            ThresholdConfiguration cfg,
            ProfileState profileState,
            boolean humanSessionOpen);

    /**
     * Applies the ask-timeout rule: an {@code ASK} decision that was never confirmed within the
     * configured confirmation timeout becomes a {@code BLOCK} (Req 7.6). Any non-{@code ASK}
     * decision is returned unchanged, so this is safe to call on any pending decision.
     *
     * @param pending the decision that was awaiting confirmation
     * @return a {@code BLOCK} decision if {@code pending} was an unconfirmed {@code ASK}, otherwise
     *     {@code pending} unchanged
     */
    Decision onAskTimeout(Decision pending);
}
