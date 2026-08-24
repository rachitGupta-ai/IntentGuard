package com.intentguard.ingest;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;

import com.intentguard.domain.Actor;
import com.intentguard.domain.CorrectiveAction;
import com.intentguard.domain.RawShellSignal;
import com.intentguard.domain.Verdict;

/**
 * Unit tests for the synchronous blocking-gate ingestor: delegation to the injected decision
 * provider and enforcement of the 2-second decision budget deadline (Req 2.2, 5.8).
 */
class InteractiveSignalIngestorTest {

    private static RawShellSignal signal(String command) {
        return new RawShellSignal(Actor.human("alice"), command, "/home/alice", Map.of(), 1L, null);
    }

    @Test
    void delegatesToProviderAndReturnsItsVerdict() {
        InteractiveDecisionProvider provider = s -> Verdict.allow("STUB_ALLOW");
        InteractiveSignalIngestor ingestor =
                new InteractiveSignalIngestor(TestObjectProvider.of(provider), 2000);

        Verdict verdict = ingestor.submitInteractive(signal("ls -la"));

        assertThat(verdict.action()).isEqualTo(CorrectiveAction.ALLOW);
        assertThat(verdict.reasonCode()).isEqualTo("STUB_ALLOW");
    }

    @Test
    void enforcesDecisionBudgetAndFailsSafeWithBlock() {
        long budgetMs = 200;
        InteractiveDecisionProvider slow =
                s -> {
                    try {
                        Thread.sleep(5_000);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return Verdict.allow("SHOULD_NOT_BE_RETURNED");
                };
        InteractiveSignalIngestor ingestor =
                new InteractiveSignalIngestor(TestObjectProvider.of(slow), budgetMs);

        long start = System.nanoTime();
        Verdict verdict = ingestor.submitInteractive(signal("sleep 5"));
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertThat(verdict.action()).isEqualTo(CorrectiveAction.BLOCK);
        assertThat(verdict.reasonCode()).isEqualTo(InteractiveSignalIngestor.REASON_BUDGET_EXCEEDED);
        // The deadline must fire close to the budget, well before the provider's 5s sleep.
        assertThat(elapsedMs).isLessThan(2_000);
    }

    @Test
    void providerErrorFailsSafeWithBlock() {
        InteractiveDecisionProvider failing =
                s -> {
                    throw new IllegalStateException("boom");
                };
        InteractiveSignalIngestor ingestor =
                new InteractiveSignalIngestor(TestObjectProvider.of(failing), 2000);

        Verdict verdict = ingestor.submitInteractive(signal("whoami"));

        assertThat(verdict.action()).isEqualTo(CorrectiveAction.BLOCK);
        assertThat(verdict.reasonCode()).isEqualTo(InteractiveSignalIngestor.REASON_DECISION_ERROR);
    }

    @Test
    void nullProviderReturnFailsSafeWithBlock() {
        InteractiveDecisionProvider nullReturning = s -> null;
        InteractiveSignalIngestor ingestor =
                new InteractiveSignalIngestor(TestObjectProvider.of(nullReturning), 2000);

        Verdict verdict = ingestor.submitInteractive(signal("cat secrets"));

        assertThat(verdict.action()).isEqualTo(CorrectiveAction.BLOCK);
        assertThat(verdict.reasonCode()).isEqualTo(InteractiveSignalIngestor.REASON_DECISION_ERROR);
    }

    @Test
    void missingProviderFailsSafeWithAsk() {
        InteractiveSignalIngestor ingestor =
                new InteractiveSignalIngestor(TestObjectProvider.empty(), 2000);

        Verdict verdict = ingestor.submitInteractive(signal("git status"));

        assertThat(verdict.action()).isEqualTo(CorrectiveAction.ASK);
        assertThat(verdict.reasonCode()).isEqualTo(InteractiveSignalIngestor.REASON_NO_PROVIDER);
    }
}
