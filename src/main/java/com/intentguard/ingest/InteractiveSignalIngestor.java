package com.intentguard.ingest;

import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.intentguard.domain.RawShellSignal;
import com.intentguard.domain.Verdict;

import jakarta.annotation.PreDestroy;

/**
 * Default {@link SignalIngestor} for the synchronous blocking gate.
 *
 * <p>It delegates the allow/ask/block decision to an injectable {@link InteractiveDecisionProvider}
 * and enforces the 2-second decision budget deadline (Req 5.8) on every request: the decision runs
 * on a worker and is awaited only up to the budget. If the budget is exceeded, the provider errors,
 * or no provider is wired yet, a conservative fail-safe verdict is returned so the hook is never
 * left waiting.
 */
@Service
public class InteractiveSignalIngestor implements SignalIngestor {

    private static final Logger log = LoggerFactory.getLogger(InteractiveSignalIngestor.class);

    /** Reason code emitted when the decision budget deadline is reached. */
    static final String REASON_BUDGET_EXCEEDED = "DECISION_BUDGET_EXCEEDED";
    /** Reason code emitted when the decision provider throws or returns null. */
    static final String REASON_DECISION_ERROR = "DECISION_ERROR";
    /** Reason code emitted when no decision provider has been wired in yet. */
    static final String REASON_NO_PROVIDER = "NO_DECISION_PROVIDER";

    private final ObjectProvider<InteractiveDecisionProvider> decisionProvider;
    private final long budgetMillis;
    private final ExecutorService executor;

    public InteractiveSignalIngestor(
            ObjectProvider<InteractiveDecisionProvider> decisionProvider,
            @Value("${intentguard.decision.budget-ms:2000}") long budgetMillis) {
        this.decisionProvider = decisionProvider;
        this.budgetMillis = budgetMillis > 0 ? budgetMillis : 2000L;
        this.executor = newDecisionExecutor();
    }

    private static ExecutorService newDecisionExecutor() {
        AtomicLong counter = new AtomicLong();
        // A direct-handoff pool: one worker per in-flight decision so a slow decision can be
        // abandoned (interrupted) at the budget deadline without stalling other requests.
        ThreadPoolExecutor pool =
                new ThreadPoolExecutor(
                        0,
                        Integer.MAX_VALUE,
                        60L,
                        TimeUnit.SECONDS,
                        new SynchronousQueue<>(),
                        runnable -> {
                            Thread thread = new Thread(runnable);
                            thread.setName("intentguard-decision-" + counter.incrementAndGet());
                            thread.setDaemon(true);
                            return thread;
                        });
        return pool;
    }

    @Override
    public Verdict submitInteractive(RawShellSignal signal) {
        Objects.requireNonNull(signal, "signal must not be null");

        InteractiveDecisionProvider provider = decisionProvider.getIfAvailable();
        if (provider == null) {
            // No decision engine is wired yet (early walking-skeleton state). Fail safe by
            // requiring confirmation rather than silently allowing.
            log.warn("No InteractiveDecisionProvider is available; returning fail-safe ASK");
            return Verdict.ask(
                    REASON_NO_PROVIDER,
                    "IntentGuard could not reach a decision engine; confirmation is required.");
        }

        Future<Verdict> future = executor.submit(() -> provider.decide(signal));
        try {
            Verdict verdict = future.get(budgetMillis, TimeUnit.MILLISECONDS);
            if (verdict == null) {
                log.warn("Decision provider returned null; failing safe with BLOCK");
                return failSafeBlock(REASON_DECISION_ERROR);
            }
            return verdict;
        } catch (TimeoutException e) {
            future.cancel(true);
            log.warn("Decision budget of {} ms exceeded; failing safe with BLOCK", budgetMillis);
            return failSafeBlock(REASON_BUDGET_EXCEEDED);
        } catch (ExecutionException e) {
            log.warn("Decision provider failed; failing safe with BLOCK", e.getCause());
            return failSafeBlock(REASON_DECISION_ERROR);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            future.cancel(true);
            log.warn("Interrupted awaiting decision; failing safe with BLOCK");
            return failSafeBlock(REASON_DECISION_ERROR);
        }
    }

    private static Verdict failSafeBlock(String reasonCode) {
        return Verdict.block(
                reasonCode,
                "IntentGuard could not complete a decision within its safety budget and blocked "
                        + "the command as a precaution.");
    }

    /** The decision budget in milliseconds enforced on each request. */
    long budgetMillis() {
        return budgetMillis;
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }
}
