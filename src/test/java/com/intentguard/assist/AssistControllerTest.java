package com.intentguard.assist;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Unit tests for {@link AssistController}.
 *
 * <p>Uses standalone MockMvc setup with a mocked {@link NlAssistService} to test
 * each endpoint and all exception handler mappings.
 *
 * <p><b>Property 13: Request validation produces HTTP 400</b>
 * <p><b>Validates: Requirements 9.5</b>
 */
@ExtendWith(MockitoExtension.class)
class AssistControllerTest {

    @Mock
    NlAssistService assistService;

    MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        AssistController controller = new AssistController(assistService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    // --- Happy-path endpoint tests ---

    @Test
    void queryEndpoint_returnsAlternatives() throws Exception {
        List<CommandAlternative> alternatives = List.of(
                new CommandAlternative("ls -la", "List all files with details", 0),
                new CommandAlternative("find . -type f", "Find all files recursively", 1)
        );
        AssistResponse response = new AssistResponse("sess-1", "list files", alternatives);
        when(assistService.query(eq("operator-1"), any(AssistRequest.class))).thenReturn(response);

        mockMvc.perform(post("/api/assist")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Operator-Id", "operator-1")
                        .content("""
                                {"query": "list files", "languageTag": "en"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessionId").value("sess-1"))
                .andExpect(jsonPath("$.queryEcho").value("list files"))
                .andExpect(jsonPath("$.alternatives").isArray())
                .andExpect(jsonPath("$.alternatives.length()").value(2))
                .andExpect(jsonPath("$.alternatives[0].command").value("ls -la"))
                .andExpect(jsonPath("$.alternatives[0].explanation").value("List all files with details"))
                .andExpect(jsonPath("$.alternatives[0].index").value(0))
                .andExpect(jsonPath("$.alternatives[1].command").value("find . -type f"))
                .andExpect(jsonPath("$.alternatives[1].index").value(1));
    }

    @Test
    void selectEndpoint_returnsScoreAndAction() throws Exception {
        SelectResponse response = new SelectResponse(
                "sess-1", "ls -la", 0.25, "ALLOW", "Low risk command", false);
        when(assistService.select(eq("operator-1"), any(SelectRequest.class))).thenReturn(response);

        mockMvc.perform(post("/api/assist/select")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Operator-Id", "operator-1")
                        .content("""
                                {"sessionId": "sess-1", "commandIndex": 0}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessionId").value("sess-1"))
                .andExpect(jsonPath("$.command").value("ls -la"))
                .andExpect(jsonPath("$.score").value(0.25))
                .andExpect(jsonPath("$.action").value("ALLOW"))
                .andExpect(jsonPath("$.explanation").value("Low risk command"))
                .andExpect(jsonPath("$.blocked").value(false));
    }

    @Test
    void confirmEndpoint_returnsExecutionResult() throws Exception {
        ConfirmResponse response = new ConfirmResponse(
                "sess-1", "ls -la", "file1.txt\nfile2.txt\n", "", 0, true, null);
        when(assistService.confirm(eq("operator-1"), any(ConfirmRequest.class))).thenReturn(response);

        mockMvc.perform(post("/api/assist/confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Operator-Id", "operator-1")
                        .content("""
                                {"sessionId": "sess-1", "commandIndex": 0}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessionId").value("sess-1"))
                .andExpect(jsonPath("$.command").value("ls -la"))
                .andExpect(jsonPath("$.stdout").value("file1.txt\nfile2.txt\n"))
                .andExpect(jsonPath("$.stderr").value(""))
                .andExpect(jsonPath("$.exitCode").value(0))
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.suggestion").isEmpty());
    }

    @Test
    void closeSession_returns204() throws Exception {
        doNothing().when(assistService).closeSession("operator-1", "sess-1");

        mockMvc.perform(delete("/api/assist/sessions/sess-1")
                        .header("X-Operator-Id", "operator-1"))
                .andExpect(status().isNoContent());

        verify(assistService).closeSession("operator-1", "sess-1");
    }

    // --- Exception handler tests ---

    @Test
    void rateLimitException_returns429() throws Exception {
        when(assistService.query(eq("operator-1"), any(AssistRequest.class)))
                .thenThrow(new AssistRateLimitException("Rate limit exceeded", 45000L));

        mockMvc.perform(post("/api/assist")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Operator-Id", "operator-1")
                        .content("""
                                {"query": "list files"}
                                """))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error").value("Rate limit exceeded"))
                .andExpect(jsonPath("$.retryAfterMs").value("45000"));
    }

    @Test
    void validationError_returns400() throws Exception {
        when(assistService.select(eq("operator-1"), any(SelectRequest.class)))
                .thenThrow(new IllegalArgumentException("commandIndex out of range"));

        mockMvc.perform(post("/api/assist/select")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Operator-Id", "operator-1")
                        .content("""
                                {"sessionId": "sess-1", "commandIndex": 0}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("commandIndex out of range"));
    }

    @Test
    void sessionNotFound_returns404() throws Exception {
        when(assistService.select(eq("operator-1"), any(SelectRequest.class)))
                .thenThrow(new AssistSessionNotFoundException("sess-999"));

        mockMvc.perform(post("/api/assist/select")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Operator-Id", "operator-1")
                        .content("""
                                {"sessionId": "sess-999", "commandIndex": 0}
                                """))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("Assist session not found: sess-999"));
    }

    @Test
    void blocked_returns403() throws Exception {
        when(assistService.confirm(eq("operator-1"), any(ConfirmRequest.class)))
                .thenThrow(new AssistBlockedException("Command was BLOCKed and cannot be executed."));

        mockMvc.perform(post("/api/assist/confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Operator-Id", "operator-1")
                        .content("""
                                {"sessionId": "sess-1", "commandIndex": 0}
                                """))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("Command was BLOCKed and cannot be executed."));
    }

    @Test
    void translationFailure_returns502() throws Exception {
        when(assistService.query(eq("operator-1"), any(AssistRequest.class)))
                .thenThrow(new AssistTranslationException("Translation failed for hi"));

        mockMvc.perform(post("/api/assist")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Operator-Id", "operator-1")
                        .content("""
                                {"query": "फ़ाइलें दिखाओ", "languageTag": "hi"}
                                """))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.error").value("Translation failed for hi"));
    }

    @Test
    void generationFailure_returns502() throws Exception {
        when(assistService.query(eq("operator-1"), any(AssistRequest.class)))
                .thenThrow(new AssistGenerationException("LLM service timed out"));

        mockMvc.perform(post("/api/assist")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Operator-Id", "operator-1")
                        .content("""
                                {"query": "restart nginx"}
                                """))
                .andExpect(status().isBadGateway())
                // The user-facing error is a clean, generic "temporarily unavailable" message;
                // the raw provider detail is preserved separately under "detail".
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("temporarily unavailable")))
                .andExpect(jsonPath("$.detail").value("LLM service timed out"));
    }

    @Test
    void blocklistRejection_returns422() throws Exception {
        when(assistService.query(eq("operator-1"), any(AssistRequest.class)))
                .thenThrow(new AssistBlocklistException(
                        "All generated commands were blocked by safety filters."));

        mockMvc.perform(post("/api/assist")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Operator-Id", "operator-1")
                        .content("""
                                {"query": "format the disk"}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value(
                        "All generated commands were blocked by safety filters."));
    }
}
