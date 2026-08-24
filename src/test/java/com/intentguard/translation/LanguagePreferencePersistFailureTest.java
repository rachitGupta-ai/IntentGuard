package com.intentguard.translation;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.mock;

import com.mongodb.client.MongoDatabase;

/**
 * Unit test for the {@code Language_Preference} persistence-failure path (Req 1.6).
 *
 * <p>When persisting an accepted {@code Supported_Language} selection fails, the
 * {@link LanguagePreferenceService} keeps the selection for the current session and signals that it
 * could not be saved. A subsequent {@code getPreference} within the same session must return the
 * selected tag (session retention), even though it was never durably stored.
 */
class LanguagePreferencePersistFailureTest {

    private static final String OPERATOR = "operator-1";
    private static final LanguageTag HINDI = LanguageTag.of("hi");

    @Test
    void persistFailureRetainsSelectionForSessionAndSignalsNotSaved() {
        FailingLanguagePreferenceRepository repository = new FailingLanguagePreferenceRepository();
        LanguagePreferenceService service =
                new LanguagePreferenceService(repository, SupportedLanguages.defaults());

        LanguagePreferenceUpdate update = service.setPreference(OPERATOR, HINDI);

        // The selection was accepted (a Supported_Language) but not durably saved (Req 1.6).
        assertThat(update.status())
                .isEqualTo(LanguagePreferenceUpdate.Status.SAVED_IN_SESSION_ONLY);
        assertThat(update.accepted()).isTrue();
        assertThat(update.saved()).isFalse();
        assertThat(update.preference()).isEqualTo(HINDI);

        // The failing save was actually attempted.
        assertThat(repository.saveAttempts).isEqualTo(1);
    }

    @Test
    void sessionSelectionIsReturnedOnSubsequentReadDespitePersistFailure() {
        FailingLanguagePreferenceRepository repository = new FailingLanguagePreferenceRepository();
        LanguagePreferenceService service =
                new LanguagePreferenceService(repository, SupportedLanguages.defaults());

        service.setPreference(OPERATOR, HINDI);

        // Session retention: the selection is honoured for the remainder of the session even though
        // it was never persisted (Req 1.6). The repository read returns nothing (no saved doc).
        assertThat(service.getPreference(OPERATOR)).isEqualTo(HINDI);
    }

    /**
     * A {@link LanguagePreferenceRepository} whose {@code save} always throws, modelling a
     * persistence failure. {@code findByOperatorId} returns empty (nothing was durably stored) so
     * the read path is driven purely by the session cache.
     */
    private static final class FailingLanguagePreferenceRepository
            extends LanguagePreferenceRepository {

        private int saveAttempts;

        FailingLanguagePreferenceRepository() {
            super(mock(MongoDatabase.class));
        }

        @Override
        public java.util.Optional<LanguagePreferenceDocument> findByOperatorId(String operatorId) {
            return java.util.Optional.empty();
        }

        @Override
        public void save(LanguagePreferenceDocument preference) {
            saveAttempts++;
            throw new RuntimeException("simulated persistence failure");
        }
    }
}
