package com.intentguard.api;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import com.intentguard.translation.LanguagePreferenceService;
import com.intentguard.translation.LanguagePreferenceUpdate;
import com.intentguard.translation.LanguageTag;
import com.intentguard.translation.SupportedLanguages;
import com.intentguard.translation.TranslationOutcome;
import com.intentguard.translation.TranslationResult;
import com.intentguard.translation.TranslationService;

/**
 * Unit tests for {@link TranslationController} using mocked collaborators (Req 1.1, 1.2, 1.5, 1.6,
 * 2.1). The controller methods are exercised directly with a mocked {@link TranslationService} and
 * {@link LanguagePreferenceService} so the tests stay fast and require no Spring context or MongoDB.
 */
class TranslationControllerTest {

    private TranslationService translationService;
    private LanguagePreferenceService languagePreferenceService;
    private TranslationController controller;

    @BeforeEach
    void setUp() {
        translationService = mock(TranslationService.class);
        languagePreferenceService = mock(LanguagePreferenceService.class);
        controller = new TranslationController(translationService, languagePreferenceService);
    }

    @Test
    void translateContentReturnsTranslatedTextAndOutcome() {
        String hindi = "\u0905\u0928\u0941\u0935\u093e\u0926"; // अनुवाद
        when(translationService.translate(
                        eq("delete /tmp/log"), eq(SupportedLanguages.ENGLISH), eq(LanguageTag.of("hi")), eq(false)))
                .thenReturn(new TranslationResult(hindi, true, TranslationOutcome.TRANSLATED));

        ResponseEntity<TranslatedContentResponse> response = controller.translateContent(
                new TranslateContentRequest("delete /tmp/log", "hi", null, false));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().text()).isEqualTo(hindi);
        assertThat(response.getBody().translated()).isTrue();
        assertThat(response.getBody().outcome()).isEqualTo("TRANSLATED");
        verify(translationService)
                .translate(eq("delete /tmp/log"), eq(SupportedLanguages.ENGLISH), eq(LanguageTag.of("hi")), eq(false));
    }

    @Test
    void translateContentPassesExplicitSourceLanguageAndSensitiveFlag() {
        when(translationService.translate(
                        eq("secret"), eq(LanguageTag.of("hi")), eq(LanguageTag.of("bn")), eq(true)))
                .thenReturn(new TranslationResult("secret", false, TranslationOutcome.ENGLISH_PASSTHROUGH));

        ResponseEntity<TranslatedContentResponse> response = controller.translateContent(
                new TranslateContentRequest("secret", "bn", "hi", true));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().translated()).isFalse();
        assertThat(response.getBody().outcome()).isEqualTo("ENGLISH_PASSTHROUGH");
        verify(translationService)
                .translate(eq("secret"), eq(LanguageTag.of("hi")), eq(LanguageTag.of("bn")), eq(true));
    }

    @Test
    void setLanguagePreferenceSavedReturns200WithSavedTrue() {
        when(languagePreferenceService.setPreference("alice", LanguageTag.of("hi")))
                .thenReturn(new LanguagePreferenceUpdate(
                        LanguagePreferenceUpdate.Status.SAVED, LanguageTag.of("hi")));

        ResponseEntity<LanguagePreferenceView> response = controller.setLanguagePreference(
                new LanguagePreferenceRequest("alice", "hi"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().languageTag()).isEqualTo("hi");
        assertThat(response.getBody().saved()).isTrue();
        assertThat(response.getBody().status()).isEqualTo("SAVED");
    }

    @Test
    void setLanguagePreferenceSessionOnlyReturns200WithSavedFalse() {
        when(languagePreferenceService.setPreference("alice", LanguageTag.of("bn")))
                .thenReturn(new LanguagePreferenceUpdate(
                        LanguagePreferenceUpdate.Status.SAVED_IN_SESSION_ONLY, LanguageTag.of("bn")));

        ResponseEntity<LanguagePreferenceView> response = controller.setLanguagePreference(
                new LanguagePreferenceRequest("alice", "bn"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().languageTag()).isEqualTo("bn");
        assertThat(response.getBody().saved()).isFalse();
        assertThat(response.getBody().status()).isEqualTo("SAVED_IN_SESSION_ONLY");
    }

    @Test
    void setLanguagePreferenceUnsupportedReturns422AndRetainsCurrent() {
        // Rejected: the service echoes the retained current preference (English here).
        when(languagePreferenceService.setPreference("alice", LanguageTag.of("zz")))
                .thenReturn(new LanguagePreferenceUpdate(
                        LanguagePreferenceUpdate.Status.REJECTED_UNSUPPORTED, SupportedLanguages.ENGLISH));

        ResponseEntity<LanguagePreferenceView> response = controller.setLanguagePreference(
                new LanguagePreferenceRequest("alice", "zz"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().languageTag()).isEqualTo("en");
        assertThat(response.getBody().saved()).isFalse();
        assertThat(response.getBody().status()).isEqualTo("REJECTED_UNSUPPORTED");
    }

    @Test
    void setLanguagePreferenceBlankOperatorFallsBackToDefault() {
        when(languagePreferenceService.setPreference("admin", LanguageTag.of("ta")))
                .thenReturn(new LanguagePreferenceUpdate(
                        LanguagePreferenceUpdate.Status.SAVED, LanguageTag.of("ta")));

        ResponseEntity<LanguagePreferenceView> response = controller.setLanguagePreference(
                new LanguagePreferenceRequest("  ", "ta"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().operatorId()).isEqualTo("admin");
        verify(languagePreferenceService).setPreference("admin", LanguageTag.of("ta"));
    }
}
