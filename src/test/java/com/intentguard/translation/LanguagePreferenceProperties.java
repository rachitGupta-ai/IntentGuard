// Feature: indian-language-translation, Property 10: Language_Preference management is well-defined
package com.intentguard.translation;

import static org.assertj.core.api.Assertions.assertThat;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

/**
 * Feature: indian-language-translation, Property 10: Language_Preference management is well-defined.
 *
 * <p>For any operator: when no preference is saved, the preference reads as English (Req 1.3);
 * setting any {@code Supported_Language} makes subsequent reads return that language (Req 1.2); and
 * setting any tag outside the {@code Supported_Language} set is rejected while the current
 * preference is retained (Req 1.5).
 *
 * <p>Exercises {@link LanguagePreferenceService} against an in-memory
 * {@link InMemoryLanguagePreferenceRepository} so the read/write behaviour is deterministic and no
 * live Mongo is required. Generators cover operator ids, the full {@code Supported_Language} set
 * (including English), and out-of-set (invalid) tags.
 *
 * <p>Validates: Requirements 1.2, 1.3, 1.5.
 */
class LanguagePreferenceProperties {

    private final SupportedLanguages supportedLanguages = SupportedLanguages.defaults();

    // ---- Req 1.3: an operator with no saved preference reads as English --------------------------

    @Property(tries = 200)
    void unsavedPreferenceReadsAsEnglish(@ForAll("operatorIds") String operatorId) {
        LanguagePreferenceService service = newService();

        // Nothing was ever set for this operator, so the preference falls back to English (Req 1.3).
        assertThat(service.getPreference(operatorId)).isEqualTo(SupportedLanguages.ENGLISH);
    }

    // ---- Req 1.2: setting a Supported_Language makes subsequent reads return it -------------------

    @Property(tries = 200)
    void settingSupportedLanguageIsReturnedBySubsequentReads(
            @ForAll("operatorIds") String operatorId,
            @ForAll("supportedTags") LanguageTag tag) {

        LanguagePreferenceService service = newService();

        LanguagePreferenceUpdate update = service.setPreference(operatorId, tag);

        // A Supported_Language is accepted and every subsequent read returns exactly it (Req 1.2).
        assertThat(update.accepted()).isTrue();
        assertThat(update.preference()).isEqualTo(tag);
        assertThat(service.getPreference(operatorId)).isEqualTo(tag);
        assertThat(service.getPreference(operatorId)).isEqualTo(tag); // stable on repeat reads
    }

    // ---- Req 1.5: an out-of-set tag is rejected and the current preference is retained ------------

    @Property(tries = 200)
    void outOfSetTagIsRejectedAndRetainsDefaultPreference(
            @ForAll("operatorIds") String operatorId,
            @ForAll("invalidTags") LanguageTag invalidTag) {

        LanguagePreferenceService service = newService();

        // With no prior selection the current preference is English; a rejection must retain it.
        LanguagePreferenceUpdate update = service.setPreference(operatorId, invalidTag);

        assertThat(update.accepted()).isFalse();
        assertThat(update.status())
                .isEqualTo(LanguagePreferenceUpdate.Status.REJECTED_UNSUPPORTED);
        assertThat(update.preference()).isEqualTo(SupportedLanguages.ENGLISH);
        assertThat(service.getPreference(operatorId)).isEqualTo(SupportedLanguages.ENGLISH);
    }

    @Property(tries = 200)
    void outOfSetTagIsRejectedAndRetainsPreviouslySetLanguage(
            @ForAll("operatorIds") String operatorId,
            @ForAll("supportedTags") LanguageTag current,
            @ForAll("invalidTags") LanguageTag invalidTag) {

        LanguagePreferenceService service = newService();

        // Establish a current Supported_Language preference first (Req 1.2).
        assertThat(service.setPreference(operatorId, current).accepted()).isTrue();

        // Now an out-of-set tag must be rejected and the current preference retained (Req 1.5).
        LanguagePreferenceUpdate rejection = service.setPreference(operatorId, invalidTag);

        assertThat(rejection.accepted()).isFalse();
        assertThat(rejection.status())
                .isEqualTo(LanguagePreferenceUpdate.Status.REJECTED_UNSUPPORTED);
        assertThat(rejection.preference()).isEqualTo(current);
        assertThat(service.getPreference(operatorId)).isEqualTo(current);
    }

    // ---- Worked example: full lifecycle over a single operator -----------------------------------

    @Example
    void lifecycleDefaultThenSetThenRejectRetains() {
        LanguagePreferenceService service = newService();
        String operator = "op-lifecycle";

        // Default is English (Req 1.3).
        assertThat(service.getPreference(operator)).isEqualTo(SupportedLanguages.ENGLISH);

        // Set Hindi and read it back (Req 1.2).
        LanguageTag hindi = LanguageTag.of("hi");
        assertThat(service.setPreference(operator, hindi).saved()).isTrue();
        assertThat(service.getPreference(operator)).isEqualTo(hindi);

        // Reject an unsupported tag; Hindi is retained (Req 1.5).
        LanguagePreferenceUpdate rejection = service.setPreference(operator, LanguageTag.of("zz"));
        assertThat(rejection.status())
                .isEqualTo(LanguagePreferenceUpdate.Status.REJECTED_UNSUPPORTED);
        assertThat(rejection.preference()).isEqualTo(hindi);
        assertThat(service.getPreference(operator)).isEqualTo(hindi);
    }

    // ---- factory ---------------------------------------------------------------------------------

    private LanguagePreferenceService newService() {
        return new LanguagePreferenceService(
                new InMemoryLanguagePreferenceRepository(), supportedLanguages);
    }

    // ---- generators ------------------------------------------------------------------------------

    @Provide
    Arbitrary<String> operatorIds() {
        Arbitrary<String> named =
                Arbitraries.of("alice", "bob", "op-1", "operator-42", "admin", "carol");
        Arbitrary<String> random =
                Arbitraries.strings().alpha().numeric().ofMinLength(1).ofMaxLength(16);
        return Arbitraries.oneOf(named, random);
    }

    @Provide
    Arbitrary<LanguageTag> supportedTags() {
        // The full configured Supported_Language set, including English (Req 6.2).
        return Arbitraries.of("en", "hi", "bn", "te", "mr", "ta", "gu", "kn", "ml", "pa", "or")
                .map(LanguageTag::of);
    }

    @Provide
    Arbitrary<LanguageTag> invalidTags() {
        // Non-blank tags that are never members of the Supported_Language set (case-insensitively).
        Arbitrary<String> named =
                Arbitraries.of("fr", "de", "es", "zh", "ja", "ru", "ar", "zz", "xx", "klingon", "und");
        Arbitrary<String> random =
                Arbitraries.strings().alpha().ofMinLength(2).ofMaxLength(10);
        return Arbitraries.oneOf(named, random)
                .map(LanguageTag::of)
                .filter(tag -> !supportedLanguages.isSupported(tag));
    }
}
