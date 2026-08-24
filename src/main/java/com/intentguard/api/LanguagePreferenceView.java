package com.intentguard.api;

import com.intentguard.translation.LanguagePreferenceUpdate;

/**
 * Response body for {@code PUT /api/preferences/language}: the effective Language_Preference after
 * a set-preference attempt together with how it was resolved (Req 1.2, 1.5, 1.6).
 *
 * <p>{@code languageTag} is the BCP-47 tag of the effective preference after the operation.
 * {@code saved} is {@code true} only when the preference was durably persisted; it is {@code false}
 * when the selection was kept for the current session only because persistence failed (Req 1.6), so
 * the Control_Tower can notify the Operator that the preference could not be saved. {@code status}
 * names the {@link LanguagePreferenceUpdate.Status} for precise client handling.
 *
 * @param operatorId  the Operator whose preference was set
 * @param languageTag the effective Language_Preference BCP-47 tag
 * @param saved       {@code true} when durably persisted; {@code false} for session-only
 * @param status      the {@code LanguagePreferenceUpdate.Status} name
 */
public record LanguagePreferenceView(
        String operatorId, String languageTag, boolean saved, String status) {

    /** Builds a view from a {@link LanguagePreferenceUpdate} for the given operator. */
    public static LanguagePreferenceView from(String operatorId, LanguagePreferenceUpdate update) {
        return new LanguagePreferenceView(
                operatorId, update.preference().value(), update.saved(), update.status().name());
    }
}
