package com.intentguard.api;

/**
 * Envelope response for {@code GET /api/users/{userId}/profile}. Carries the resolved
 * {@link ActiveWindow} metadata and one {@link CategoryView} per activity category, plus the
 * {@link BehavioralProfileView} summary (Req 7.5, 7.6, 8.1, 10.4).
 *
 * <p>Consumers should check {@link #windowEmpty} before rendering records: when {@code true} the
 * user has no persisted data within any store and all category lists will be empty (Req 7.5).
 *
 * <p>{@link #profileLoadFailed} is {@code true} only when <em>every</em> category returned
 * {@link CategoryStatus#UNAVAILABLE} (i.e. all timed out or threw). When at least one category
 * succeeded this field is {@code false}, even if every successful category is empty (Req 10.4).
 *
 * @param userId            the user the profile was assembled for
 * @param windowStart       epoch-ms lower bound of the Active_Window (Req 7.6)
 * @param windowEnd         epoch-ms upper bound of the Active_Window (Req 7.6)
 * @param fullHistory       {@code true} when the full-history window was applied (Req 7.4)
 * @param windowEmpty       {@code true} when full-history was requested but no records exist (Req 7.5)
 * @param profileLoadFailed {@code true} only when all five categories are UNAVAILABLE (Req 10.4)
 * @param commandTimeline   command decision records (Req 2, 8)
 * @param multilingual      multilingual intent entries (Req 3, 8)
 * @param assistQueries     NL assistant query records (Req 4, 8)
 * @param translations      translation records (Req 5, 8)
 * @param behavioralProfile behavioral profile summary (Req 6)
 * @param riskStats         average command score + 30-day risk trend for this user
 */
public record UserProfileView(
        String userId,
        long windowStart,
        long windowEnd,
        boolean fullHistory,
        boolean windowEmpty,
        boolean profileLoadFailed,
        CategoryView<CommandDecisionEntry> commandTimeline,
        CategoryView<MultilingualEntryView> multilingual,
        CategoryView<AssistQueryView> assistQueries,
        CategoryView<TranslationRecordView> translations,
        BehavioralProfileView behavioralProfile,
        RiskStats riskStats) {
}
