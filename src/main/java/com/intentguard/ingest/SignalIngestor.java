package com.intentguard.ingest;

import com.intentguard.domain.RawShellSignal;
import com.intentguard.domain.Verdict;

/**
 * Signal_Ingestor contract for the synchronous, pre-execution blocking gate (Req 2.2).
 *
 * <p>The Shell_Hook writes a {@link RawShellSignal} to the service-account-owned Unix domain
 * socket and synchronously waits for the returned {@link Verdict}. On {@code BLOCK} (or an
 * unconfirmed {@code ASK}) the hook returns non-zero and the command never executes.
 *
 * <p>Implementations MUST return a verdict within the configured decision budget (Req 5.8);
 * when the budget deadline is reached the implementation fails safe rather than blocking the
 * caller indefinitely.
 */
public interface SignalIngestor {

    /**
     * Handle a Shell_Hook signal synchronously and return the enforcement verdict.
     *
     * <p>This call is the blocking gate: it always returns a verdict, and always within the
     * decision budget. It never throws for a slow or failing decision path; instead it returns
     * a conservative fail-safe verdict.
     *
     * @param signal the raw shell signal received from the hook (never {@code null})
     * @return the verdict the hook must enforce (never {@code null})
     */
    Verdict submitInteractive(RawShellSignal signal);
}
