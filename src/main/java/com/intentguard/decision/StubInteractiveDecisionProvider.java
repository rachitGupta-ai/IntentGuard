package com.intentguard.decision;

import java.util.List;
import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.intentguard.domain.RawShellSignal;
import com.intentguard.domain.Verdict;
import com.intentguard.ingest.InteractiveDecisionProvider;

/**
 * Walking-skeleton stub Decision Engine (Task 2.3).
 *
 * <p>This bean exists solely to prove the full Shell_Hook &rarr; socket &rarr; ingestor &rarr;
 * decision &rarr; verdict &rarr; hook path executes end-to-end and that the {@code block} path is
 * observably enforced (the hook returns non-zero and the command never runs). It applies a
 * trivial keyword-matching rule, NOT the real four-component divergence scoring:
 *
 * <ul>
 *   <li>{@code BLOCK} - obviously destructive commands (e.g. {@code rm -rf /}) or any command that
 *       targets IntentGuard configuration/process/datastore (a stand-in for the tamper override of
 *       Req 1.6 / 13.3).</li>
 *   <li>{@code ASK}  - commands in the grey zone (privilege escalation, piping a remote script into
 *       a shell, and similar) that warrant explicit confirmation (Req 7.3).</li>
 *   <li>{@code ALLOW} - everything else, treated as ordinary work (Req 7.2).</li>
 * </ul>
 *
 * <p><strong>SUPERSEDED (Task 13.1):</strong> this stub has been replaced at runtime by the real
 * {@link PipelineDecisionProvider}, which is annotated {@link org.springframework.context.annotation.Primary
 * @Primary} and is therefore the {@link InteractiveDecisionProvider} the
 * {@code InteractiveSignalIngestor} resolves. The stub bean is intentionally retained (still a
 * {@code @Component}) so the walking-skeleton unit tests continue to exercise it directly, but it no
 * longer participates in live enforcement decisions and must not be relied on for them.
 *
 * @implNote Because {@link PipelineDecisionProvider} is {@code @Primary}, the ingestor's
 *     {@code ObjectProvider} resolves that provider even though this stub bean is also present;
 *     exactly one provider is used at runtime.
 */
@Component
public class StubInteractiveDecisionProvider implements InteractiveDecisionProvider {

    private static final Logger log = LoggerFactory.getLogger(StubInteractiveDecisionProvider.class);

    static final String REASON_STUB_BLOCK = "STUB_BLOCK_DANGEROUS";
    static final String REASON_STUB_TAMPER = "STUB_BLOCK_TAMPER";
    static final String REASON_STUB_ASK = "STUB_ASK_GREY_ZONE";
    static final String REASON_STUB_ALLOW = "STUB_ALLOW_ORDINARY";

    /**
     * Obviously destructive command fragments. Matching any of these forces a block so the
     * enforcement path is demonstrably exercised.
     */
    private static final List<String> DANGEROUS_FRAGMENTS =
            List.of(
                    "rm -rf /",
                    "rm -rf /*",
                    "rm -rf ~",
                    "mkfs",
                    "dd if=",
                    ":(){:|:&};:", // fork bomb
                    "> /dev/sda");

    /**
     * Fragments indicating an attempt to touch IntentGuard's own config/process/datastore. A
     * stand-in for the real tamper override; also forces a block.
     */
    private static final List<String> TAMPER_FRAGMENTS =
            List.of(
                    "intentguard",
                    "/etc/intentguard",
                    "threshold_config",
                    "behavioral_profiles");

    /**
     * Grey-zone fragments that warrant explicit confirmation rather than an outright allow.
     */
    private static final List<String> ASK_FRAGMENTS =
            List.of(
                    "sudo ",
                    "curl ",
                    "wget ",
                    "| sh",
                    "| bash",
                    "chmod 777",
                    "chown ");

    @Override
    public Verdict decide(RawShellSignal signal) {
        String command = signal.commandText();
        String normalized = command.toLowerCase(Locale.ROOT).strip();

        if (containsAny(normalized, TAMPER_FRAGMENTS)) {
            log.info("[stub] BLOCK (tamper) for command: {}", command);
            return Verdict.block(
                    REASON_STUB_TAMPER,
                    "This command appears to target IntentGuard's own configuration or state and "
                            + "was blocked. (walking-skeleton stub)");
        }

        if (containsAny(normalized, DANGEROUS_FRAGMENTS)) {
            log.info("[stub] BLOCK (dangerous) for command: {}", command);
            return Verdict.block(
                    REASON_STUB_BLOCK,
                    "This command looks destructive and was blocked as a precaution. "
                            + "(walking-skeleton stub)");
        }

        if (containsAny(normalized, ASK_FRAGMENTS)) {
            log.info("[stub] ASK (grey-zone) for command: {}", command);
            return Verdict.ask(
                    REASON_STUB_ASK,
                    "This command is in a grey zone and needs your confirmation before it runs. "
                            + "(walking-skeleton stub)");
        }

        log.info("[stub] ALLOW for command: {}", command);
        return Verdict.allow(REASON_STUB_ALLOW);
    }

    private static boolean containsAny(String haystack, List<String> needles) {
        for (String needle : needles) {
            if (haystack.contains(needle)) {
                return true;
            }
        }
        return false;
    }
}
