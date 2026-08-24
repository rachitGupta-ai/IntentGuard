package com.intentguard.intent;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.intentguard.llm.LlmService;
import com.intentguard.profile.BehavioralProfileManager;
import com.intentguard.scoring.ProfileSnapshot;

/**
 * Derives an Inferred_Intent for a user from recent Command_Event statistics and LLM_Service
 * summarization (Req 14.1) — the stretch capability that estimates what a user is doing when they
 * have not declared a goal.
 *
 * <h2>Feature flag (Req 14.1)</h2>
 * <p>Inferred-intent estimation is gated behind the {@code intentguard.inferred-intent.enabled}
 * property (default {@code false}). When disabled, {@link #deriveInferredIntent(String)} always
 * returns {@link Optional#empty()} so the core pipeline behaves exactly as before — an event with
 * no open Intent_Session is scored with intent absent (source {@code NONE}) and
 * Semantic_Inconsistency excluded.
 *
 * <h2>Derivation</h2>
 * <p>When enabled, the recent command window is taken from the user's Behavioral_Profile command
 * statistics (the most-frequently observed executables, highest first) via
 * {@link BehavioralProfileManager#snapshotForUser(String)}, and summarized into a short
 * natural-language goal by the {@link LlmService}.
 *
 * <h2>Graceful degradation (Req 14.1, mirroring 6.3/6.4)</h2>
 * <p>If the user has no learned commands yet, or the {@link LlmService} is unavailable (returns
 * empty on timeout, error, or malformed output), or the summary is blank, this returns
 * {@link Optional#empty()} — no Inferred_Intent is produced and the caller leaves the intent source
 * {@code NONE} so Semantic_Inconsistency is excluded rather than scored against a guess.
 */
@Service
public class InferredIntentService {

    /** Maximum number of recent commands included in the window handed to the LLM summarizer. */
    static final int MAX_RECENT_COMMANDS = 20;

    private final BehavioralProfileManager profileManager;
    private final LlmService llmService;
    private final boolean enabled;

    public InferredIntentService(
            BehavioralProfileManager profileManager,
            LlmService llmService,
            @Value("${intentguard.inferred-intent.enabled:false}") boolean enabled) {
        this.profileManager = Objects.requireNonNull(profileManager, "profileManager must not be null");
        this.llmService = Objects.requireNonNull(llmService, "llmService must not be null");
        this.enabled = enabled;
    }

    /** Whether inferred-intent estimation is enabled by the feature flag (Req 14.1). */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Derives an Inferred_Intent for {@code userId} from recent command statistics and LLM
     * summarization (Req 14.1). Returns {@link Optional#empty()} when the feature flag is off, the
     * user has no recent commands, or the LLM_Service cannot produce a usable summary.
     */
    public Optional<String> deriveInferredIntent(String userId) {
        if (!enabled || userId == null) {
            return Optional.empty();
        }
        List<String> recentCommands = recentCommandsFor(userId);
        if (recentCommands.isEmpty()) {
            return Optional.empty();
        }
        return llmService.summarizeIntent(recentCommands)
                .map(String::trim)
                .filter(summary -> !summary.isEmpty());
    }

    /**
     * The recent command window for {@code userId}: the user's most-frequently observed
     * executables (highest count first), capped at {@link #MAX_RECENT_COMMANDS}. Empty when the
     * user has no learned command vocabulary yet.
     */
    private List<String> recentCommandsFor(String userId) {
        ProfileSnapshot snapshot = profileManager.snapshotForUser(userId);
        return snapshot.vocabulary().entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .limit(MAX_RECENT_COMMANDS)
                .map(Map.Entry::getKey)
                .toList();
    }
}
