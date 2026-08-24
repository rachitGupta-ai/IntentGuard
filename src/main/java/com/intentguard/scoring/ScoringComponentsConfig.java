package com.intentguard.scoring;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.intentguard.llm.LlmService;

/**
 * Registers the four deterministic/semantic {@link DivergenceComponent}s as Spring beans so the
 * {@link DefaultScoringPipeline} receives the full component list (Task 13.1).
 *
 * <p>These components were intentionally <em>not</em> annotated {@code @Component} earlier: each
 * needs collaborators ({@link ProfileSnapshotProvider} for the deterministic three,
 * {@link LlmService} for Semantic_Inconsistency) that were not yet available as beans, and wiring
 * them prematurely would have left the pipeline with a missing dependency. Now that
 * {@link com.intentguard.profile.BehavioralProfileManager} is a {@link ProfileSnapshotProvider}
 * bean and {@link com.intentguard.llm.GeminiLlmService} is an {@link LlmService} bean, this
 * configuration constructs each component with its collaborator and contributes it to the
 * {@code List<DivergenceComponent>} the pipeline autowires.
 *
 * <p>The {@code ProfileSnapshotProvider} injected here resolves to the single
 * {@code BehavioralProfileManager} bean, so scoring reads each user's real persisted profile.
 */
@Configuration
public class ScoringComponentsConfig {

    @Bean
    public SequenceSurpriseComponent sequenceSurpriseComponent(ProfileSnapshotProvider profileProvider) {
        return new SequenceSurpriseComponent(profileProvider);
    }

    @Bean
    public ContextMismatchComponent contextMismatchComponent(ProfileSnapshotProvider profileProvider) {
        return new ContextMismatchComponent(profileProvider);
    }

    @Bean
    public BehavioralDeviationComponent behavioralDeviationComponent(ProfileSnapshotProvider profileProvider) {
        return new BehavioralDeviationComponent(profileProvider);
    }

    @Bean
    public SemanticInconsistencyComponent semanticInconsistencyComponent(LlmService llmService) {
        return new SemanticInconsistencyComponent(llmService);
    }
}
