package com.intentguard.api;

/**
 * Aggregation service for the User Profiling Screen.
 *
 * <p>Implementations MUST be stateless and read-only: they MUST NOT invoke any scoring,
 * decision, translation, or command-execution component (Req 9.4), and MUST NOT perform any
 * write operation against any repository (Req 9.3).
 *
 * <p>The three methods form the full contract used by {@link UserProfileController}:
 * <ol>
 *   <li>{@link #listKnownUsers()} — returns the case-insensitive-deduplicated union of user
 *       identifiers across all persisted stores (Req 1.1, 1.2, 1.3).</li>
 *   <li>{@link #resolveWindow(String, int, boolean)} — validates the window parameters and
 *       computes the {@link ActiveWindow} for the subsequent assembly call (Req 7).</li>
 *   <li>{@link #assemble(String, ActiveWindow)} — assembles the full per-user profile within
 *       the given window, tolerating per-category failures (Req 2–8, 10).</li>
 * </ol>
 */
public interface UserProfileService {

    /**
     * Returns the distinct Known_User list assembled from all persisted user-keyed stores.
     *
     * <p>The Translation_Record store contributes no identifiers (no userId is stored there).
     * Identifiers are deduplicated case-insensitively and sorted case-insensitively ascending
     * (Req 1.1, 1.2, 1.3).
     *
     * @return a {@link KnownUsersView} containing the deduplicated, sorted user list
     */
    KnownUsersView listKnownUsers();

    /**
     * Assembles the full User Profiling Screen view for the given user and time window.
     *
     * <p>Each of the five activity categories (command timeline, multilingual entries, assistant
     * queries, translation records, behavioral profile) is fetched and projected independently.
     * Each category has an independent 5-second cutoff; a timeout or exception yields
     * {@link CategoryStatus#UNAVAILABLE} for that category while siblings still complete
     * (Req 10.2, 10.3). {@link UserProfileView#profileLoadFailed()} is set {@code true} only when
     * every category is {@code UNAVAILABLE} (Req 10.4).
     *
     * <p>The implementation MUST NOT invoke scoring, decision, translation, or execution
     * collaborators (Req 9.4) and MUST NOT perform any writes (Req 9.3).
     *
     * @param userId      the user to profile; must not be blank (validated upstream by the controller)
     * @param window      the resolved {@link ActiveWindow}; if {@link ActiveWindow#empty()} is
     *                    {@code true} all categories return empty results
     * @param fullHistory {@code true} when the full-history window was applied (Req 7.4); surfaced
     *                    in {@link UserProfileView#fullHistory()} for the UI
     * @return the assembled {@link UserProfileView} (never {@code null})
     */
    UserProfileView assemble(String userId, ActiveWindow window, boolean fullHistory);

    /**
     * Resolves and validates the Active_Window parameters supplied by the caller.
     *
     * <p>Resolution rules (Req 7.1–7.5):
     * <ul>
     *   <li>When {@code full == false}: {@code days} must be in [1, 365]; if not, throws
     *       {@link InvalidWindowException} (Req 7.3). The window is
     *       {@code [now - days * 86_400_000L, now]} (Req 7.1, 7.2).</li>
     *   <li>When {@code full == true}: the lower bound is the minimum timestamp of any
     *       persisted user-keyed record across Audit_History, Intent_Session, and Assist_Audit
     *       QUERY records (Req 7.4). If the user has no such records the method returns
     *       {@link ActiveWindow#emptyWindow()} (Req 7.5). The upper bound is always {@code now}.</li>
     * </ul>
     *
     * <p>A rejected request (e.g. out-of-range {@code days}) MUST NOT mutate any state (Req 9.3).
     *
     * @param userId the user whose earliest record determines the full-history lower bound
     * @param days   look-back window in days; must be in [1, 365] when {@code full == false}
     * @param full   when {@code true} the window spans the earliest persisted record to now
     * @return the resolved {@link ActiveWindow}
     * @throws InvalidWindowException if {@code full == false} and {@code days} is not in [1, 365]
     *                                (Req 7.3)
     */
    ActiveWindow resolveWindow(String userId, int days, boolean full);
}
