package com.intentguard.decision;

import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.intentguard.domain.ComponentId;
import com.intentguard.ingest.InteractiveDecisionProvider;
import com.intentguard.scoring.DivergenceComponent;
import com.intentguard.scoring.ScoringPipeline;

/**
 * Verifies the Task 13.1 wiring loads in the Spring context: the real
 * {@link PipelineDecisionProvider} is the resolved {@link InteractiveDecisionProvider} (replacing
 * the stub via {@code @Primary}), and all four {@link DivergenceComponent}s are registered as beans
 * so the {@link ScoringPipeline} receives the full component list.
 */
@SpringBootTest
class PipelineWiringTest {

    @Autowired
    private InteractiveDecisionProvider decisionProvider;

    @Autowired
    private List<DivergenceComponent> components;

    @Autowired
    private ScoringPipeline scoringPipeline;

    @Test
    void pipelineProviderIsTheWiredDecisionProvider() {
        assertThat(decisionProvider).isInstanceOf(PipelineDecisionProvider.class);
    }

    @Test
    void allFourDivergenceComponentsAreRegistered() {
        List<ComponentId> ids = components.stream()
                .map(DivergenceComponent::id)
                .collect(Collectors.toList());
        assertThat(ids).containsExactlyInAnyOrder(
                ComponentId.SEQUENCE_SURPRISE,
                ComponentId.CONTEXT_MISMATCH,
                ComponentId.BEHAVIORAL_DEVIATION,
                ComponentId.SEMANTIC_INCONSISTENCY);
        assertThat(scoringPipeline).isNotNull();
    }
}
