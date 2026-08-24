package com.intentguard.api;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import com.intentguard.domain.Actor;
import com.intentguard.domain.IntentSource;
import com.intentguard.intent.InboundIntentResult;
import com.intentguard.intent.InboundIntentService;
import com.intentguard.intent.IntentSession;
import com.intentguard.intent.IntentSessionManager;
import com.intentguard.persistence.IntentSessionDocument;
import com.intentguard.persistence.IntentSessionRepository;
import com.intentguard.translation.LanguageTag;

/**
 * Unit tests for {@link IntentSessionController} using mocked collaborators (Req 3.1, 3.3, 3.4,
 * 10.4). The controller methods are exercised directly with a mocked {@link InboundIntentService},
 * {@link IntentSessionManager}, and {@link IntentSessionRepository} so the tests stay fast and
 * require no Spring context or MongoDB.
 */
class IntentSessionControllerTest {

    private InboundIntentService inboundIntentService;
    private IntentSessionManager sessionManager;
    private IntentSessionRepository sessionRepository;
    private IntentSessionController controller;

    @BeforeEach
    void setUp() {
        inboundIntentService = mock(InboundIntentService.class);
        sessionManager = mock(IntentSessionManager.class);
        sessionRepository = mock(IntentSessionRepository.class);
        controller = new IntentSessionController(inboundIntentService, sessionManager, sessionRepository);
    }

    @Test
    void openSessionNonEnglishRunsInboundFlowAndReturns201WithBothTexts() {
        IntentSession opened = new IntentSession(
                "sess-1",
                "alice",
                "delete the temp files",           // English (engine language)
                "\u0905\u0938\u094d\u0925\u093e\u092f\u0940 \u092b\u093c\u093e\u0907\u0932", // original Source_Text
                "hi",
                IntentSource.DECLARED,
                1_000L,
                null,
                true);
        when(inboundIntentService.submit(
                        eq("alice"), any(), eq(LanguageTag.of("hi")), eq(Actor.human("alice"))))
                .thenReturn(InboundIntentResult.sessionOpened(opened));

        ResponseEntity<?> response = controller.openSession(
                new OpenSessionRequest("alice", "\u0905\u0938\u094d\u0925\u093e\u092f\u0940 \u092b\u093c\u093e\u0907\u0932", "hi"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isInstanceOf(SessionView.class);
        SessionView view = (SessionView) response.getBody();
        assertThat(view.sessionId()).isEqualTo("sess-1");
        assertThat(view.declaredIntent()).isEqualTo("delete the temp files");
        assertThat(view.originalDeclaredIntent())
                .isEqualTo("\u0905\u0938\u094d\u0925\u093e\u092f\u0940 \u092b\u093c\u093e\u0907\u0932");
        assertThat(view.declaredIntentLanguageTag()).isEqualTo("hi");
        verify(inboundIntentService)
                .submit(eq("alice"), any(), eq(LanguageTag.of("hi")), eq(Actor.human("alice")));
    }

    @Test
    void openSessionEnglishSubmissionPassesNullSourceLanguage() {
        IntentSession opened = new IntentSession(
                "sess-2", "bob", "list running processes", IntentSource.DECLARED, 2_000L, null, true);
        when(inboundIntentService.submit(eq("bob"), any(), eq(null), eq(Actor.human("bob"))))
                .thenReturn(InboundIntentResult.sessionOpened(opened));

        ResponseEntity<?> response = controller.openSession(
                new OpenSessionRequest("bob", "list running processes", null));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        verify(inboundIntentService).submit(eq("bob"), any(), eq(null), eq(Actor.human("bob")));
    }

    @Test
    void openSessionTranslationFailureReturns422WithLocalizedMessage() {
        when(inboundIntentService.submit(eq("alice"), any(), eq(LanguageTag.of("hi")), any()))
                .thenReturn(InboundIntentResult.rejected("\u0905\u0928\u0941\u0935\u093e\u0926 \u0935\u093f\u092b\u0932"));

        ResponseEntity<?> response = controller.openSession(
                new OpenSessionRequest("alice", "some intent", "hi"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(response.getBody()).isInstanceOf(RejectedSubmissionResponse.class);
        RejectedSubmissionResponse body = (RejectedSubmissionResponse) response.getBody();
        assertThat(body.message()).isEqualTo("\u0905\u0928\u0941\u0935\u093e\u0926 \u0935\u093f\u092b\u0932");
    }

    @Test
    void openSessionBlankOperatorFallsBackToDefault() {
        IntentSession opened = new IntentSession(
                "sess-3", "admin", "check disk usage", IntentSource.DECLARED, 3_000L, null, true);
        when(inboundIntentService.submit(eq("admin"), any(), eq(null), eq(Actor.human("admin"))))
                .thenReturn(InboundIntentResult.sessionOpened(opened));

        ResponseEntity<?> response = controller.openSession(
                new OpenSessionRequest("  ", "check disk usage", "  "));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        verify(inboundIntentService).submit(eq("admin"), any(), eq(null), eq(Actor.human("admin")));
    }

    @Test
    void getSessionByIdReturnsBothTexts() {
        IntentSessionDocument document = new IntentSessionDocument();
        document.setSessionId("sess-9");
        document.setUserId("alice");
        document.setDeclaredIntent("delete the temp files");
        document.setOriginalDeclaredIntent("\u0905\u0938\u094d\u0925\u093e\u092f\u0940");
        document.setDeclaredIntentLanguageTag("hi");
        document.setIntentSource(IntentSource.DECLARED.name());
        document.setStartedAt(4_000L);
        document.setOpen(true);
        when(sessionRepository.findBySessionId("sess-9")).thenReturn(Optional.of(document));

        ResponseEntity<SessionView> response = controller.getSession("sess-9");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().declaredIntent()).isEqualTo("delete the temp files");
        assertThat(response.getBody().originalDeclaredIntent()).isEqualTo("\u0905\u0938\u094d\u0925\u093e\u092f\u0940");
        assertThat(response.getBody().declaredIntentLanguageTag()).isEqualTo("hi");
    }

    @Test
    void getSessionByIdUnknownReturns404() {
        when(sessionRepository.findBySessionId("missing")).thenReturn(Optional.empty());

        ResponseEntity<SessionView> response = controller.getSession("missing");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void getActiveSessionReturnsBothTexts() {
        IntentSession session = new IntentSession(
                "sess-10",
                "alice",
                "restart the service",
                "\u09b8\u09be\u09b0\u09cd\u09ad\u09bf\u09b8", // Bengali source
                "bn",
                IntentSource.DECLARED,
                5_000L,
                null,
                true);
        when(sessionManager.activeSessionFor("alice")).thenReturn(Optional.of(session));

        ResponseEntity<SessionView> response = controller.getActiveSession("alice");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().declaredIntent()).isEqualTo("restart the service");
        assertThat(response.getBody().originalDeclaredIntent()).isEqualTo("\u09b8\u09be\u09b0\u09cd\u09ad\u09bf\u09b8");
        assertThat(response.getBody().declaredIntentLanguageTag()).isEqualTo("bn");
        verifyNoInteractions(sessionRepository);
    }

    @Test
    void getActiveSessionNoneReturns404() {
        when(sessionManager.activeSessionFor("nobody")).thenReturn(Optional.empty());

        ResponseEntity<SessionView> response = controller.getActiveSession("nobody");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }
}
