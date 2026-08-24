package com.intentguard.assist;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.Size;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property-based tests for {@link GenerationBlocklist}.
 *
 * <p><b>Validates: Requirements 3.1, 3.3, 3.4</b>
 *
 * <p>Property 4: Blocklist filtering completeness.
 * For any list of alternatives where some match blocklist patterns, the output contains
 * zero blocked commands; when all are blocked, an empty list is returned.
 */
class GenerationBlocklistPropertyTest {

    /** The default blocklist patterns (same as AssistProperties defaults). */
    private static final List<String> DEFAULT_BLOCKLIST = List.of(
            "rm\\s+-rf\\s+/(?:\\s|$)",
            "mkfs",
            "rmmod",
            "modprobe\\s+-r"
    );

    private static final List<Pattern> COMPILED_PATTERNS = DEFAULT_BLOCKLIST.stream()
            .map(Pattern::compile)
            .toList();

    private final GenerationBlocklist blocklist = createBlocklist();

    private static GenerationBlocklist createBlocklist() {
        AssistProperties properties = new AssistProperties();
        // defaults are already set in AssistProperties
        return new GenerationBlocklist(properties);
    }

    // --- Property 1: Filtering removes all blocked commands ---

    /**
     * Property: for any mixed list of alternatives (some blocked, some safe),
     * after filtering, no remaining command matches any blocklist pattern.
     */
    @Property(tries = 200)
    void filteredOutputContainsNoBlockedCommands(
            @ForAll("mixedAlternatives") List<CommandAlternative> alternatives) {

        List<CommandAlternative> result = blocklist.filter(alternatives);

        for (CommandAlternative alt : result) {
            assertThat(blocklist.isBlocked(alt.command()))
                    .as("Command '%s' should not be blocked but survived filtering", alt.command())
                    .isFalse();
        }
    }

    // --- Property 2: Filtering preserves all safe commands ---

    /**
     * Property: for any list containing only safe (non-blocked) commands,
     * all commands survive the filter unchanged.
     */
    @Property(tries = 200)
    void safeCommandsAreNeverRemoved(
            @ForAll("safeAlternatives") List<CommandAlternative> alternatives) {

        List<CommandAlternative> result = blocklist.filter(alternatives);

        assertThat(result).hasSameSizeAs(alternatives);
        for (int i = 0; i < alternatives.size(); i++) {
            assertThat(result.get(i).command()).isEqualTo(alternatives.get(i).command());
        }
    }

    // --- Property 3: All blocked yields empty list ---

    /**
     * Property: when every alternative in the input matches a blocklist pattern,
     * the result is an empty list.
     */
    @Property(tries = 100)
    void allBlockedAlternativesYieldEmptyList(
            @ForAll("blockedAlternatives") List<CommandAlternative> alternatives) {

        List<CommandAlternative> result = blocklist.filter(alternatives);

        assertThat(result).isEmpty();
    }

    // --- Property 4: isBlocked returns true for known blocked patterns ---

    /**
     * Property: isBlocked() returns true for commands that match any default blocklist pattern.
     */
    @Property(tries = 100)
    void isBlockedReturnsTrueForBlockedCommands(
            @ForAll("blockedCommands") String command) {

        assertThat(blocklist.isBlocked(command)).isTrue();
    }

    // --- Property 5: isBlocked returns false for safe commands ---

    /**
     * Property: isBlocked() returns false for commands that do not match any blocklist pattern.
     */
    @Property(tries = 200)
    void isBlockedReturnsFalseForSafeCommands(
            @ForAll("safeCommands") String command) {

        assertThat(blocklist.isBlocked(command)).isFalse();
    }

    // --- Providers ---

    @Provide
    Arbitrary<List<CommandAlternative>> mixedAlternatives() {
        Arbitrary<CommandAlternative> safe = safeAlternativeArbitrary();
        Arbitrary<CommandAlternative> blocked = blockedAlternativeArbitrary();

        // Generate a list with at least one safe and at least one blocked
        return Combinators.combine(
                safe.list().ofMinSize(1).ofMaxSize(3),
                blocked.list().ofMinSize(1).ofMaxSize(2)
        ).as((safeList, blockedList) -> {
            List<CommandAlternative> mixed = new ArrayList<>(safeList);
            mixed.addAll(blockedList);
            // Re-index all alternatives
            List<CommandAlternative> result = new ArrayList<>();
            for (int i = 0; i < mixed.size(); i++) {
                result.add(new CommandAlternative(mixed.get(i).command(), mixed.get(i).explanation(), i));
            }
            return result;
        });
    }

    @Provide
    Arbitrary<List<CommandAlternative>> safeAlternatives() {
        return safeAlternativeArbitrary().list().ofMinSize(1).ofMaxSize(5);
    }

    @Provide
    Arbitrary<List<CommandAlternative>> blockedAlternatives() {
        return blockedAlternativeArbitrary().list().ofMinSize(1).ofMaxSize(5);
    }

    @Provide
    Arbitrary<String> blockedCommands() {
        return Arbitraries.of(
                "rm -rf /",
                "rm  -rf /",
                "rm -rf / --no-preserve-root",
                "sudo rm -rf /",
                "mkfs /dev/sda1",
                "mkfs.ext4 /dev/sda",
                "rmmod nf_conntrack",
                "modprobe -r usb_storage",
                "modprobe  -r  i2c_core"
        );
    }

    @Provide
    Arbitrary<String> safeCommands() {
        return Arbitraries.of(
                "ls -la",
                "cat /etc/hostname",
                "grep -r 'error' /var/log",
                "df -h",
                "ps aux",
                "top -bn1",
                "find /tmp -name '*.log'",
                "systemctl status nginx",
                "curl http://localhost:8080/health",
                "echo hello",
                "mkdir -p /tmp/test",
                "cp file.txt backup.txt",
                "chmod 644 config.yml",
                "tail -f /var/log/syslog",
                "wc -l report.csv"
        );
    }

    // --- Helper Arbitraries ---

    private Arbitrary<CommandAlternative> safeAlternativeArbitrary() {
        Arbitrary<String> commands = safeCommands();
        Arbitrary<String> explanations = Arbitraries.of(
                "Lists directory contents",
                "Shows disk usage",
                "Displays running processes",
                "Checks service status",
                "Searches for patterns in files"
        );
        Arbitrary<Integer> indices = Arbitraries.integers().between(0, 10);

        return Combinators.combine(commands, explanations, indices)
                .as(CommandAlternative::new);
    }

    private Arbitrary<CommandAlternative> blockedAlternativeArbitrary() {
        Arbitrary<String> commands = blockedCommands();
        Arbitrary<String> explanations = Arbitraries.of(
                "Removes all files from root",
                "Formats the disk",
                "Removes kernel module",
                "Unloads module"
        );
        Arbitrary<Integer> indices = Arbitraries.integers().between(0, 10);

        return Combinators.combine(commands, explanations, indices)
                .as(CommandAlternative::new);
    }
}
