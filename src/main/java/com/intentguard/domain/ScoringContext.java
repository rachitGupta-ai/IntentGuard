package com.intentguard.domain;

import java.util.Objects;
import java.util.Optional;

/**
 * The immutable input passed to each {@code DivergenceComponent} when scoring a Command_Event.
 * It bundles the event under evaluation with the intent it should be scored against, the current
 * profile state, and the scoring configuration.
 *
 * @param event        the Command_Event being scored
 * @param intentText   the Declared_Intent or Inferred_Intent text, or {@code null} when none
 * @param intentSource the provenance of {@code intentText}
 * @param profileState the state of the user's Behavioral_Profile at scoring time
 * @param config       the scoring configuration (component weights)
 */
public record ScoringContext(
        CommandEvent event,
        String intentText,
        IntentSource intentSource,
        ProfileState profileState,
        ScoringConfig config) {

    public ScoringContext {
        Objects.requireNonNull(event, "event must not be null");
        Objects.requireNonNull(config, "config must not be null");
        intentSource = intentSource == null ? IntentSource.NONE : intentSource;
        profileState = profileState == null ? ProfileState.LEARNING : profileState;
    }

    /** The intent text, if any, this event is scored against. */
    public Optional<String> intent() {
        return Optional.ofNullable(intentText);
    }

    public boolean hasIntent() {
        return intentText != null && intentSource != IntentSource.NONE;
    }
}
