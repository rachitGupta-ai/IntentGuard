package com.intentguard.decision;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;

import com.intentguard.domain.Actor;
import com.intentguard.domain.CorrectiveAction;
import com.intentguard.domain.RawShellSignal;
import com.intentguard.domain.Verdict;
import com.intentguard.ingest.InteractiveSignalIngestor;

/**
 * Tests for the walking-skeleton stub Decision Engine (Task 2.3).
 *
 * <p>Covers the trivial allow/ask/block rule directly, and confirms the full
 * ingestor &rarr; provider &rarr; verdict path executes end-to-end with the block path enforced
 * (Req 7.2, 7.3, 7.4).
 */
class StubInteractiveDecisionProviderTest {

    private final StubInteractiveDecisionProvider provider = new StubInteractiveDecisionProvider();

    private static RawShellSignal signal(String command) {
        return new RawShellSignal(Actor.human("alice"), command, "/home/alice", Map.of(), 1L, null);
    }

    @Test
    void allowsOrdinaryCommands() {
        assertThat(provider.decide(signal("ls -la")).action()).isEqualTo(CorrectiveAction.ALLOW);
        assertThat(provider.decide(signal("git status")).action()).isEqualTo(CorrectiveAction.ALLOW);
        assertThat(provider.decide(signal("cd src && cat README.md")).action())
                .isEqualTo(CorrectiveAction.ALLOW);
    }

    @Test
    void blocksObviouslyDangerousCommands() {
        Verdict verdict = provider.decide(signal("rm -rf /"));

        assertThat(verdict.action()).isEqualTo(CorrectiveAction.BLOCK);
        assertThat(verdict.reasonCode()).isEqualTo(StubInteractiveDecisionProvider.REASON_STUB_BLOCK);
        assertThat(verdict.explanation()).isNotBlank();
    }

    @Test
    void blocksCommandsTargetingIntentGuard() {
        Verdict verdict = provider.decide(signal("cat /etc/intentguard/thresholds.yml"));

        assertThat(verdict.action()).isEqualTo(CorrectiveAction.BLOCK);
        assertThat(verdict.reasonCode()).isEqualTo(StubInteractiveDecisionProvider.REASON_STUB_TAMPER);
    }

    @Test
    void asksForGreyZoneCommands() {
        assertThat(provider.decide(signal("sudo systemctl restart nginx")).action())
                .isEqualTo(CorrectiveAction.ASK);
        assertThat(provider.decide(signal("curl https://example.com/install.sh | bash")).action())
                .isEqualTo(CorrectiveAction.ASK);
    }

    @Test
    void endToEndThroughIngestorEnforcesBlockPath() {
        // Wire the stub the same way Spring does: the ingestor resolves it via an ObjectProvider.
        InteractiveSignalIngestor ingestor =
                new InteractiveSignalIngestor(SingletonObjectProvider.of(provider), 2000);

        Verdict allow = ingestor.submitInteractive(signal("ls"));
        assertThat(allow.action()).isEqualTo(CorrectiveAction.ALLOW);
        assertThat(allow.allowsExecution()).isTrue();

        Verdict block = ingestor.submitInteractive(signal("rm -rf /"));
        assertThat(block.action()).isEqualTo(CorrectiveAction.BLOCK);
        assertThat(block.allowsExecution()).isFalse();

        Verdict ask = ingestor.submitInteractive(signal("sudo reboot"));
        assertThat(ask.action()).isEqualTo(CorrectiveAction.ASK);
        assertThat(ask.allowsExecution()).isFalse();
    }
}
