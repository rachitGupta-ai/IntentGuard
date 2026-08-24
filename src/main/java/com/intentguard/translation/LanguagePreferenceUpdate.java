package com.intentguard.translation;

import java.util.Objects;

/**
 * The outcome of a {@link LanguagePreferenceService#setPreference(String, LanguageTag)} attempt,
 * carrying both the effective {@code Language_Preference} after the operation and how the request
 * was resolved.
 *
 * <ul>
 *   <li>{@link Status#SAVED} — the tag was a {@code Supported_Language} and was persisted so it
 *       applies to subsequent content and survives sessions (Req 1.2, 1.4).</li>
 *   <li>{@link Status#REJECTED_UNSUPPORTED} — the tag was outside the {@code Supported_Language}
 *       set, so it was rejected and the current preference was retained (Req 1.5).</li>
 *   <li>{@link Status#SAVED_IN_SESSION_ONLY} — the tag was accepted and kept for the current
 *       session, but persistence failed, so the not-saved condition is signalled (Req 1.6).</li>
 * </ul>
 *
 * @param status     how the set-preference request was resolved
 * @param preference the effective {@code Language_Preference} after the operation
 */
public record LanguagePreferenceUpdate(Status status, LanguageTag preference) {

    /** How a set-preference request was resolved. */
    public enum Status {
        /** Accepted and persisted (Req 1.2, 1.4). */
        SAVED,
        /** Rejected because the tag is not a {@code Supported_Language}; current retained (Req 1.5). */
        REJECTED_UNSUPPORTED,
        /** Accepted for the current session but not persisted; not-saved is signalled (Req 1.6). */
        SAVED_IN_SESSION_ONLY
    }

    public LanguagePreferenceUpdate {
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(preference, "preference must not be null");
    }

    /**
     * @return {@code true} when the request selected a {@code Supported_Language} (whether or not
     *     persistence succeeded); {@code false} only when the tag was rejected (Req 1.5)
     */
    public boolean accepted() {
        return status != Status.REJECTED_UNSUPPORTED;
    }

    /**
     * @return {@code true} when the preference was durably persisted; {@code false} on a rejection
     *     or a persistence failure, the latter being the not-saved signal (Req 1.6)
     */
    public boolean saved() {
        return status == Status.SAVED;
    }
}
