package com.intentguard.api;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * Read-only controller for the User Profiling Screen API.
 *
 * <p>Provides two endpoints, both {@code GET}-only. Because no non-GET mappings exist for these
 * paths, Spring MVC automatically returns HTTP 405 for any {@code POST}, {@code PUT}, or
 * {@code DELETE} request (Req 9.1, 9.2).
 *
 * <ul>
 *   <li>{@code GET /api/users} — returns the distinct Known_User list assembled from all
 *       persisted user-keyed stores (Req 1.1, 1.2, 1.3).</li>
 *   <li>{@code GET /api/users/{userId}/profile} — returns the consolidated, bounded,
 *       partial-failure-tolerant profile for the requested user and time window
 *       (Req 2.1, 3.1, 4.1, 5.1, 6.1, 7.1, 7.2, 7.4, 8.1, 10.1).</li>
 * </ul>
 *
 * <p>This controller is strictly additive: it delegates entirely to {@link UserProfileService}
 * which performs only repository reads. It never invokes scoring, decision, translation, or
 * command-execution paths (Req 9.4).
 *
 * <p><strong>SECURITY — UNAUTHENTICATED PROTOTYPE ENDPOINT.</strong> Like the rest of the
 * Control_Tower API, these endpoints have no authentication layer and must be bound to a
 * loopback/OS-restricted interface before any non-prototype use. The consolidated, per-user
 * nature of this view makes that hardening especially important.
 */
@RestController
@RequestMapping("/api")
public class UserProfileController {

    private final UserProfileService userProfileService;

    /**
     * Constructs the controller with its single dependency.
     *
     * @param userProfileService the aggregation service; must not be {@code null} (Req 9.4)
     */
    public UserProfileController(UserProfileService userProfileService) {
        this.userProfileService = userProfileService;
    }

    // -----------------------------------------------------------------------
    // GET /api/users — R1
    // -----------------------------------------------------------------------

    /**
     * Returns the distinct Known_User list assembled from all persisted user-keyed stores.
     *
     * <p>Identifiers are deduplicated case-insensitively and sorted case-insensitively ascending
     * (Req 1.1, 1.2, 1.3). The Translation_Record store contributes no identifiers (no
     * {@code userId} is stored there).
     *
     * @return {@link KnownUsersView} containing the deduplicated, sorted user list; HTTP 200
     */
    @GetMapping("/users")
    public KnownUsersView listUsers() {
        return userProfileService.listKnownUsers();
    }

    // -----------------------------------------------------------------------
    // GET /api/users/{userId}/profile — R2–R8, R10
    // -----------------------------------------------------------------------

    /**
     * Returns the consolidated, bounded, partial-failure-tolerant profile for the requested user.
     *
     * <p>The {@code userId} path variable must not be blank; a blank or all-whitespace value
     * causes {@link MissingUserIdException} to be thrown and mapped to HTTP 400 (Req 10.1).
     *
     * <p>The {@code days} parameter selects the look-back window (default 3, accepted range
     * [1, 365], Req 7.1, 7.2). A non-integer value (e.g. {@code days=abc}) fails Spring type
     * conversion and is handled by {@link #onTypeMismatch}, returning HTTP 400 with the accepted
     * range (Req 7.3). An out-of-range integer causes {@link InvalidWindowException} → HTTP 400
     * (Req 7.3). When {@code full=true} the window spans the earliest persisted record to now,
     * ignoring {@code days} (Req 7.4, 7.5).
     *
     * <p>Each of the five activity categories is assembled with an independent 5-second cutoff;
     * a timeout or exception yields {@link CategoryStatus#UNAVAILABLE} for that category while
     * siblings still complete (Req 10.2, 10.3). When every category is {@code UNAVAILABLE},
     * {@link UserProfileView#profileLoadFailed()} is {@code true} (Req 10.4).
     *
     * @param userId the user to profile; blank/whitespace → HTTP 400 (Req 10.1)
     * @param days   look-back window in days; must be in [1, 365] when {@code full=false}
     *               (default 3, Req 7.1)
     * @param full   when {@code true}, window spans earliest persisted record → now (Req 7.4)
     * @return {@link UserProfileView} assembled profile; HTTP 200
     * @throws MissingUserIdException  if {@code userId} is blank or all-whitespace (Req 10.1)
     * @throws InvalidWindowException  if {@code full=false} and {@code days} ∉ [1, 365] (Req 7.3)
     */
    @GetMapping("/users/{userId}/profile")
    public UserProfileView profile(
            @PathVariable String userId,
            @RequestParam(name = "days", defaultValue = "3") int days,
            @RequestParam(name = "full", defaultValue = "false") boolean full) {

        // Req 10.1: reject blank/all-whitespace userId before any repository reads
        if (userId == null || userId.isBlank()) {
            throw new MissingUserIdException();
        }

        // Req 7.1–7.5: validate and compute the Active_Window (throws InvalidWindowException if
        // days is out of range and full=false; no state mutation on rejection, Req 9.3)
        ActiveWindow window = userProfileService.resolveWindow(userId, days, full);

        // Req 2.1, 3.1, 4.1, 5.1, 6.1, 8.1, 10.2–10.4: assemble the profile
        return userProfileService.assemble(userId, window, full);
    }

    // -----------------------------------------------------------------------
    // Exception handlers — R7.3, R10.1
    // -----------------------------------------------------------------------

    /**
     * Maps {@link MissingUserIdException} to HTTP 400 with a {@link ProfileErrorResponse} body.
     *
     * <p>Returned when the {@code {userId}} path variable is blank or all-whitespace (Req 10.1).
     *
     * @param ex the thrown exception
     * @return 400 response with error code {@code "MISSING_USER_ID"}
     */
    @ExceptionHandler(MissingUserIdException.class)
    public ResponseEntity<ProfileErrorResponse> onMissingUserId(MissingUserIdException ex) {
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(new ProfileErrorResponse(
                        "MISSING_USER_ID",
                        "userId must not be blank (Req 10.1)"));
    }

    /**
     * Maps {@link InvalidWindowException} to HTTP 400 with a {@link ProfileErrorResponse} body
     * stating the accepted range.
     *
     * <p>Returned when {@code full=false} and the {@code days} parameter is less than 1 or
     * greater than 365 (Req 7.3).
     *
     * @param ex the thrown exception carrying the invalid value
     * @return 400 response with error code {@code "INVALID_WINDOW"} and the accepted range
     */
    @ExceptionHandler(InvalidWindowException.class)
    public ResponseEntity<ProfileErrorResponse> onInvalidWindow(InvalidWindowException ex) {
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(new ProfileErrorResponse(
                        "INVALID_WINDOW",
                        "days=" + ex.getInvalidDays() + " is not in the accepted range [1, 365] (Req 7.3)"));
    }

    /**
     * Maps Spring's {@link MethodArgumentTypeMismatchException} to HTTP 400 when the {@code days}
     * parameter cannot be converted to {@code int} (e.g. {@code days=abc} or {@code days=1.5}).
     *
     * <p>Spring's built-in type conversion rejects non-integer values before the handler method
     * is invoked; this handler intercepts that failure and returns the same 400 shape as
     * {@link #onInvalidWindow}, stating the accepted integer range (Req 7.3).
     *
     * @param ex the type-mismatch exception (carries the parameter name and the rejected value)
     * @return 400 response with error code {@code "INVALID_WINDOW"} and the accepted range
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ProfileErrorResponse> onTypeMismatch(MethodArgumentTypeMismatchException ex) {
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(new ProfileErrorResponse(
                        "INVALID_WINDOW",
                        "'" + ex.getName() + "' must be an integer in the accepted range [1, 365]"
                                + " (received: '" + ex.getValue() + "') (Req 7.3)"));
    }
}
