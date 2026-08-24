package com.intentguard.decision;

import java.util.List;
import java.util.Locale;

import org.springframework.stereotype.Component;

import com.intentguard.domain.CommandEvent;

/**
 * Classifies whether a {@link CommandEvent} targets IntentGuard's own configuration, process state,
 * or the Datastore. Such events are the tamper attempts that the Decision Engine forces to the
 * maximum Divergence_Score and blocks (Req 1.6, 13.3).
 *
 * <p>The classification is a deterministic, allocation-light keyword scan over the command text and
 * working directory. It errs toward flagging: the reference monitor treats any command that appears
 * to touch its own control surface (config files, service process, or the MongoDB Datastore /
 * IntentGuard collections) as a tamper attempt. This is intentionally conservative because the cost
 * of a false negative (a successful tamper) is far higher than a false positive (a blocked command
 * the user can re-issue through an authorized path).
 */
@Component
public class TamperClassifier {

    /**
     * Fragments that indicate the command references IntentGuard's own artifacts: the service name,
     * its config locations and process controls, the MongoDB Datastore, or the IntentGuard
     * collections. Matching any fragment (case-insensitively) in the command text or cwd marks the
     * event as a tamper attempt.
     */
    private static final List<String> TAMPER_FRAGMENTS =
            List.of(
                    // IntentGuard's own name / install locations / config.
                    "intentguard",
                    "/etc/intentguard",
                    "/var/lib/intentguard",
                    "/opt/intentguard",
                    // The engine's local IPC control surface.
                    "intentguard.sock",
                    // The Datastore and the IntentGuard collections within it.
                    "threshold_config",
                    "behavioral_profiles",
                    "intent_sessions",
                    "audit_history",
                    "scenario_baselines");

    /**
     * Returns {@code true} when the Command_Event targets IntentGuard configuration, process state,
     * or the Datastore and must therefore be force-blocked.
     */
    public boolean isTamperAttempt(CommandEvent event) {
        if (event == null) {
            return false;
        }
        return referencesIntentGuard(event.commandText()) || referencesIntentGuard(event.cwd());
    }

    private static boolean referencesIntentGuard(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        String normalized = text.toLowerCase(Locale.ROOT);
        for (String fragment : TAMPER_FRAGMENTS) {
            if (normalized.contains(fragment)) {
                return true;
            }
        }
        return false;
    }
}
