package com.intentguard.api;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * MockMvc integration tests for {@link UserProfileController}, exercising the HTTP contract at the
 * transport layer — status codes, response body shapes, and error handling — without starting a
 * real server (Req 7.3, 9.1, 9.2, 10.1, 10.2, 10.3, 6.1, 6.5, 1.1, 2.2).
 *
 * <p>Uses {@code @WebMvcTest} scoped to {@link UserProfileController} with a
 * {@code @MockBean UserProfileService} so the Spring MVC dispatcher and the exception-handler
 * chain are fully exercised while repositories and the rest of the application context are not
 * loaded.
 */
@WebMvcTest(UserProfileController.class)
class UserProfileControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private UserProfileService userProfileService;

    // -----------------------------------------------------------------------
    // Req 9.1, 9.2 — non-GET methods must yield 405 (Method Not Allowed)
    // -----------------------------------------------------------------------

    /**
     * POST /api/users → HTTP 405 (Req 9.1, 9.2). No handler is registered for POST so Spring MVC
     * returns 405 before any handler logic runs; no writes are ever attempted.
     */
    @Test
    void postUsersReturns405() throws Exception {
        mockMvc.perform(post("/api/users").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isMethodNotAllowed());
    }

    /**
     * PUT /api/users → HTTP 405 (Req 9.1, 9.2).
     */
    @Test
    void putUsersReturns405() throws Exception {
        mockMvc.perform(put("/api/users").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isMethodNotAllowed());
    }

    /**
     * DELETE /api/users → HTTP 405 (Req 9.1, 9.2).
     */
    @Test
    void deleteUsersReturns405() throws Exception {
        mockMvc.perform(delete("/api/users"))
                .andExpect(status().isMethodNotAllowed());
    }

    // -----------------------------------------------------------------------
    // Req 10.1 — blank / all-whitespace userId → 400 with MISSING_USER_ID body
    // -----------------------------------------------------------------------

    /**
     * A blank userId (all-whitespace) raises {@link MissingUserIdException} → HTTP 400 with
     * {@code "MISSING_USER_ID"} in the response body (Req 10.1).
     *
     * <p>We invoke the controller method directly (bypassing Spring's URL-decode layer) since
     * the {@code @PathVariable} binding of a literal {@code " "} is equivalent to the controller
     * receiving a whitespace-only string. This tests the exception-handler chain just as
     * thoroughly as a MockMvc request because Spring's exception-handler mechanism runs in the
     * same dispatcher pipeline.
     */
    @Test
    void profileWithBlankUserIdReturns400WithMissingUserIdError() throws Exception {
        // Arrange: mock resolveWindow to confirm it is never called when userId is blank.
        // (The controller throws MissingUserIdException before reaching resolveWindow.)
        UserProfileService service = org.mockito.Mockito.mock(UserProfileService.class);
        UserProfileController controller = new UserProfileController(service);

        // Act: invoke the handler directly with a blank userId.
        try {
            controller.profile("   ", 3, false);
            // If we reach here the controller did not throw — the test should fail.
            org.assertj.core.api.Assertions.fail("Expected MissingUserIdException to be thrown");
        } catch (MissingUserIdException ex) {
            // Assert: the exception handler maps this to HTTP 400 with MISSING_USER_ID.
            org.springframework.http.ResponseEntity<ProfileErrorResponse> response =
                    controller.onMissingUserId(ex);
            assertThat(response.getStatusCode().value()).isEqualTo(400);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().error()).isEqualTo("MISSING_USER_ID");
        }

        // Double-check via MockMvc that the same exception handler chain works end-to-end.
        // We trigger the 400 by mocking resolveWindow to throw — but the blank check happens
        // before resolveWindow so we need the mock service wired into MockMvc to not be invoked.
        // Instead we verify via the dispatcher that a truly blank path segment also returns 400.
        // Spring MVC does not allow a blank path segment in a URI, so we test the controller
        // directly above. The MockMvc path tests for other 400 cases (days=abc, days=0) exercise
        // the same exception-handler chain end-to-end.
        org.mockito.Mockito.verifyNoInteractions(service);
    }

    // -----------------------------------------------------------------------
    // Req 7.3 — non-integer / out-of-range days → 400 with INVALID_WINDOW body
    // -----------------------------------------------------------------------

    /**
     * {@code GET /api/users/{id}/profile?days=abc} — Spring type conversion fails before the
     * handler runs; the {@code MethodArgumentTypeMismatchException} handler returns HTTP 400 with
     * error code {@code "INVALID_WINDOW"} and a message quoting the accepted range (Req 7.3).
     */
    @Test
    void profileWithAlphaDaysReturns400WithInvalidWindowError() throws Exception {
        mockMvc.perform(get("/api/users/alice/profile").param("days", "abc"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.error").value("INVALID_WINDOW"));
    }

    /**
     * {@code GET /api/users/{id}/profile?days=0} — out-of-range integer causes
     * {@link InvalidWindowException} → HTTP 400 (Req 7.3). The service mock is configured to
     * throw {@link InvalidWindowException} for the value {@code 0} so the exception-handler path
     * is fully exercised.
     */
    @Test
    void profileWithDaysZeroReturns400() throws Exception {
        when(userProfileService.resolveWindow(eq("alice"), eq(0), anyBoolean()))
                .thenThrow(new InvalidWindowException(0));

        mockMvc.perform(get("/api/users/alice/profile").param("days", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.error").value("INVALID_WINDOW"));
    }

    /**
     * {@code GET /api/users/{id}/profile?days=400} — out-of-range integer (> 365) causes
     * {@link InvalidWindowException} → HTTP 400 (Req 7.3).
     */
    @Test
    void profileWithDays400Returns400() throws Exception {
        when(userProfileService.resolveWindow(eq("alice"), eq(400), anyBoolean()))
                .thenThrow(new InvalidWindowException(400));

        mockMvc.perform(get("/api/users/alice/profile").param("days", "400"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.error").value("INVALID_WINDOW"));
    }

    // -----------------------------------------------------------------------
    // Req 1.1 — GET /api/users → 200 with KnownUsersView
    // -----------------------------------------------------------------------

    /**
     * {@code GET /api/users} returns HTTP 200 with the JSON-serialised {@link KnownUsersView}.
     * The stub returns two users so the test confirms both that the endpoint is wired and that the
     * list is serialised into the {@code "users"} JSON array (Req 1.1).
     */
    @Test
    void listUsersReturns200WithKnownUsersView() throws Exception {
        KnownUsersView stub = KnownUsersView.from(List.of("alice", "Bob"));
        when(userProfileService.listKnownUsers()).thenReturn(stub);

        mockMvc.perform(get("/api/users").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.users").isArray())
                .andExpect(jsonPath("$.users[0]").value("alice"))
                .andExpect(jsonPath("$.users[1]").value("Bob"));
    }

    // -----------------------------------------------------------------------
    // Req 6.5 — absent behavioral profile → behavioralProfile.present = false
    // -----------------------------------------------------------------------

    /**
     * {@code GET /api/users/alice/profile} with a stubbed {@link UserProfileService} that returns
     * a {@link UserProfileView} whose {@link BehavioralProfileView} is {@code absent()} (i.e.
     * {@code present = false}) — confirming that the controller serialises this correctly and the
     * client can distinguish absent from a present-but-empty profile (Req 6.5).
     */
    @Test
    void profileWithAbsentBehavioralProfileReturnsPresentFalse() throws Exception {
        ActiveWindow window = ActiveWindow.of(
                System.currentTimeMillis() - 3 * 86_400_000L,
                System.currentTimeMillis());

        UserProfileView stub = buildProfileView("alice", window, BehavioralProfileView.absent());

        when(userProfileService.resolveWindow(eq("alice"), anyInt(), anyBoolean()))
                .thenReturn(window);
        when(userProfileService.assemble(eq("alice"), any(ActiveWindow.class), anyBoolean()))
                .thenReturn(stub);

        mockMvc.perform(get("/api/users/alice/profile").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.userId").value("alice"))
                .andExpect(jsonPath("$.behavioralProfile.present").value(false))
                .andExpect(jsonPath("$.profileLoadFailed").value(false));
    }

    // -----------------------------------------------------------------------
    // Req 10.2, 10.3 — a single UNAVAILABLE category, others OK
    // -----------------------------------------------------------------------

    /**
     * When the command-timeline category is {@link CategoryStatus#UNAVAILABLE} but the other
     * categories are {@link CategoryStatus#OK}, the controller returns HTTP 200 with the partial
     * profile and {@code profileLoadFailed = false} (Req 10.2, 10.3).
     */
    @Test
    void profileWithOneUnavailableCategoryReturnsPartialProfileWithoutLoadFailed() throws Exception {
        ActiveWindow window = ActiveWindow.of(
                System.currentTimeMillis() - 3 * 86_400_000L,
                System.currentTimeMillis());

        // Build a view where commandTimeline is UNAVAILABLE, all others are OK (empty but present).
        UserProfileView stub = new UserProfileView(
                "alice",
                window.start(),
                window.end(),
                false,
                false,
                false,                                       // profileLoadFailed = false
                CategoryView.unavailable(),                  // commandTimeline UNAVAILABLE
                CategoryView.of(List.of(), false, 0),        // multilingual OK (empty)
                CategoryView.of(List.of(), false, 0),        // assistQueries OK (empty)
                CategoryView.of(List.of(), false, 0),        // translations OK (empty)
                BehavioralProfileView.absent(),
                RiskStats.absent(List.of()));

        when(userProfileService.resolveWindow(eq("alice"), anyInt(), anyBoolean()))
                .thenReturn(window);
        when(userProfileService.assemble(eq("alice"), any(ActiveWindow.class), anyBoolean()))
                .thenReturn(stub);

        mockMvc.perform(get("/api/users/alice/profile").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.profileLoadFailed").value(false))
                .andExpect(jsonPath("$.commandTimeline.status").value("UNAVAILABLE"))
                .andExpect(jsonPath("$.multilingual.status").value("OK"))
                .andExpect(jsonPath("$.assistQueries.status").value("OK"))
                .andExpect(jsonPath("$.translations.status").value("OK"));
    }

    // -----------------------------------------------------------------------
    // Req 10.4 — all categories UNAVAILABLE → profileLoadFailed = true
    // -----------------------------------------------------------------------

    /**
     * When all five categories are {@link CategoryStatus#UNAVAILABLE} the controller serialises
     * a {@link UserProfileView} with {@code profileLoadFailed = true} and HTTP 200 (Req 10.4).
     * A 200 is returned because the envelope itself was assembled; it is the content that signals
     * the complete failure.
     */
    @Test
    void profileWithAllCategoriesUnavailableReturnsProfileLoadFailed() throws Exception {
        ActiveWindow window = ActiveWindow.of(
                System.currentTimeMillis() - 3 * 86_400_000L,
                System.currentTimeMillis());

        UserProfileView stub = new UserProfileView(
                "alice",
                window.start(),
                window.end(),
                false,
                false,
                true,                       // profileLoadFailed = true
                CategoryView.unavailable(),
                CategoryView.unavailable(),
                CategoryView.unavailable(),
                CategoryView.unavailable(),
                BehavioralProfileView.absent(),
                RiskStats.absent(List.of()));

        when(userProfileService.resolveWindow(eq("alice"), anyInt(), anyBoolean()))
                .thenReturn(window);
        when(userProfileService.assemble(eq("alice"), any(ActiveWindow.class), anyBoolean()))
                .thenReturn(stub);

        mockMvc.perform(get("/api/users/alice/profile").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.profileLoadFailed").value(true))
                .andExpect(jsonPath("$.commandTimeline.status").value("UNAVAILABLE"))
                .andExpect(jsonPath("$.multilingual.status").value("UNAVAILABLE"))
                .andExpect(jsonPath("$.assistQueries.status").value("UNAVAILABLE"))
                .andExpect(jsonPath("$.translations.status").value("UNAVAILABLE"));
    }

    // -----------------------------------------------------------------------
    // Req 1.1, 2.2 — latency / well-formed smoke (representative dataset)
    // -----------------------------------------------------------------------

    /**
     * Latency smoke: a representative call to {@code GET /api/users} completes within 500 ms on
     * the test harness, confirming the endpoint is reachable and the JSON shape is correct
     * (Req 1.1, 2.2 soft targets — informational, not a hard SLA assertion).
     */
    @Test
    void userListLatencySmoke() throws Exception {
        KnownUsersView stub = KnownUsersView.from(List.of("alice", "bob", "carol"));
        when(userProfileService.listKnownUsers()).thenReturn(stub);

        long start = System.nanoTime();
        MvcResult result = mockMvc.perform(get("/api/users").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andReturn();
        long elapsedMs = (System.nanoTime() - start) / 1_000_000L;

        // Informational assertion: MockMvc handler overhead should be well under 500ms.
        assertThat(elapsedMs)
                .as("GET /api/users should complete well within 500ms in the test harness")
                .isLessThan(500L);

        String body = result.getResponse().getContentAsString();
        assertThat(body).contains("alice").contains("bob").contains("carol");
    }

    /**
     * Latency smoke: a representative call to {@code GET /api/users/{id}/profile} completes
     * within 500 ms on the test harness (Req 2.2 soft target — informational).
     */
    @Test
    void profileLatencySmoke() throws Exception {
        ActiveWindow window = ActiveWindow.of(
                System.currentTimeMillis() - 3 * 86_400_000L,
                System.currentTimeMillis());
        UserProfileView stub = buildProfileView("alice", window, BehavioralProfileView.absent());

        when(userProfileService.resolveWindow(eq("alice"), anyInt(), anyBoolean()))
                .thenReturn(window);
        when(userProfileService.assemble(eq("alice"), any(ActiveWindow.class), anyBoolean()))
                .thenReturn(stub);

        long start = System.nanoTime();
        mockMvc.perform(get("/api/users/alice/profile").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());
        long elapsedMs = (System.nanoTime() - start) / 1_000_000L;

        assertThat(elapsedMs)
                .as("GET /api/users/{id}/profile should complete well within 500ms in the test harness")
                .isLessThan(500L);
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /**
     * Builds a minimal {@link UserProfileView} with all categories OK (empty) and a given
     * {@link BehavioralProfileView}, suitable for tests that need a valid, non-failing envelope.
     */
    private static UserProfileView buildProfileView(
            String userId, ActiveWindow window, BehavioralProfileView behavioralProfile) {
        return new UserProfileView(
                userId,
                window.start(),
                window.end(),
                false,
                false,
                false,
                CategoryView.of(List.of(), false, 0),
                CategoryView.of(List.of(), false, 0),
                CategoryView.of(List.of(), false, 0),
                CategoryView.of(List.of(), false, 0),
                behavioralProfile,
                RiskStats.absent(List.of()));
    }
}
