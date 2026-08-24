package com.intentguard.translation;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.springframework.stereotype.Service;

/**
 * Manages each Operator's {@code Language_Preference} for the Control_Tower (Req 1).
 *
 * <p>Reads return the operator's saved {@code Supported_Language}, falling back to English when no
 * preference has been saved (Req 1.3). Writes accept only tags in the configured
 * {@code Supported_Language} set: an out-of-set tag is rejected and the current preference is
 * retained (Req 1.5), while an accepted tag is persisted so it applies to subsequent
 * Operator_Facing_Content (Req 1.2) and survives Control_Tower sessions and restarts (Req 1.4).
 *
 * <p>Persistence is treated as best-effort for the write path: when saving fails, the selection is
 * kept for the current session in an in-memory cache and the {@link LanguagePreferenceUpdate}
 * signals that the preference could not be saved (Req 1.6). The in-memory cache is also consulted
 * first on reads so a session-only selection is honoured for the remainder of the session even
 * though it was never durably stored.
 */
@Service
public class LanguagePreferenceService {

    private final LanguagePreferenceRepository repository;
    private final SupportedLanguages supportedLanguages;

    /**
     * Session-scoped view of accepted selections. Populated on a successful save, on a
     * persistence-failure fallback (Req 1.6), and lazily from the repository on read so subsequent
     * reads within the session are stable.
     */
    private final ConcurrentMap<String, LanguageTag> sessionPreferences = new ConcurrentHashMap<>();

    public LanguagePreferenceService(
            LanguagePreferenceRepository repository, SupportedLanguages supportedLanguages) {
        this.repository = Objects.requireNonNull(repository, "repository must not be null");
        this.supportedLanguages =
                Objects.requireNonNull(supportedLanguages, "supportedLanguages must not be null");
    }

    /**
     * Returns the operator's effective {@code Language_Preference}: the session selection if one is
     * held, else the persisted preference, else English when none is saved (Req 1.3).
     *
     * @param operatorId the operator whose preference to read
     * @return the effective {@code Language_Preference}; never {@code null}
     */
    public LanguageTag getPreference(String operatorId) {
        Objects.requireNonNull(operatorId, "operatorId must not be null");

        LanguageTag sessionSelection = sessionPreferences.get(operatorId);
        if (sessionSelection != null) {
            return sessionSelection;
        }

        Optional<LanguageTag> saved = readSaved(operatorId);
        if (saved.isPresent()) {
            LanguageTag tag = saved.get();
            sessionPreferences.put(operatorId, tag);
            return tag;
        }

        return SupportedLanguages.ENGLISH;
    }

    /**
     * Sets the operator's {@code Language_Preference} to {@code tag}.
     *
     * <p>An out-of-set tag is rejected and the current preference retained (Req 1.5). An accepted
     * tag is applied to the current session immediately and persisted; if persistence fails, the
     * selection is retained for the session and the not-saved condition is signalled (Req 1.6).
     *
     * @param operatorId the operator whose preference to set
     * @param tag        the requested {@code Language_Preference}
     * @return the outcome and the effective preference after the operation
     */
    public LanguagePreferenceUpdate setPreference(String operatorId, LanguageTag tag) {
        Objects.requireNonNull(operatorId, "operatorId must not be null");

        if (!supportedLanguages.isSupported(tag)) {
            // Reject and retain the current preference (Req 1.5).
            return new LanguagePreferenceUpdate(
                    LanguagePreferenceUpdate.Status.REJECTED_UNSUPPORTED, getPreference(operatorId));
        }

        // Apply to the current session immediately so the selection is honoured even if the
        // durable write fails (Req 1.2, 1.6).
        sessionPreferences.put(operatorId, tag);

        try {
            LanguagePreferenceDocument document = new LanguagePreferenceDocument();
            document.setOperatorId(operatorId);
            document.setLanguageTag(tag.value());
            document.setUpdatedAt(System.currentTimeMillis());
            repository.save(document);
            return new LanguagePreferenceUpdate(LanguagePreferenceUpdate.Status.SAVED, tag);
        } catch (RuntimeException persistenceFailure) {
            // Keep the selection for the current session and signal not-saved (Req 1.6).
            return new LanguagePreferenceUpdate(
                    LanguagePreferenceUpdate.Status.SAVED_IN_SESSION_ONLY, tag);
        }
    }

    private Optional<LanguageTag> readSaved(String operatorId) {
        try {
            return repository
                    .findByOperatorId(operatorId)
                    .map(document -> document.getLanguageTag())
                    .filter(value -> value != null && !value.isBlank())
                    .map(LanguageTag::of)
                    .filter(supportedLanguages::isSupported);
        } catch (RuntimeException readFailure) {
            // A transient read failure degrades to the default rather than blocking the operator.
            return Optional.empty();
        }
    }
}
