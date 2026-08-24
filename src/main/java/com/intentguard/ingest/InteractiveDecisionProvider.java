package com.intentguard.ingest;

import com.intentguard.domain.RawShellSignal;
import com.intentguard.domain.Verdict;

/**
 * Injectable decision function for the synchronous blocking gate.
 *
 * <p>The {@link InteractiveSignalIngestor} delegates the actual allow/ask/block decision to an
 * implementation of this interface. This keeps the socket listener and the decision-budget
 * enforcement independent of the decision logic itself: the walking-skeleton stub (wired later)
 * and the full ingest &rarr; scoring &rarr; decision pipeline both plug in here.
 *
 * <p>Implementations are invoked on a worker bounded by the decision budget, so they should
 * respect interruption and avoid unbounded blocking. A {@code null} return is treated as a
 * decision error and mapped to a fail-safe verdict by the ingestor.
 */
@FunctionalInterface
public interface InteractiveDecisionProvider {

    /**
     * Produce a verdict for the given shell signal.
     *
     * @param signal the raw shell signal (never {@code null})
     * @return the verdict for this signal
     */
    Verdict decide(RawShellSignal signal);
}
