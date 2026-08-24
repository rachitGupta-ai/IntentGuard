// Feature: indian-language-translation, Property 1: Technical_Tokens are preserved byte-for-byte
package com.intentguard.translation;

import static org.assertj.core.api.Assertions.assertThat;

import com.intentguard.translation.AdversarialTranslationProvider.SentinelHandling;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Assume;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

/**
 * Feature: indian-language-translation, Property 1: Technical_Tokens are preserved byte-for-byte.
 *
 * <p>For any Source_Text and any adversarial Translation_Provider that arbitrarily rewrites the
 * non-token prose, every Technical_Token present in the Source_Text appears byte-for-byte in the
 * produced Translated_Text. This exercises {@link TechnicalTokenProtector} — the single most
 * important correctness component of the translation layer — against the
 * {@link AdversarialTranslationProvider} in {@link SentinelHandling#PRESERVE} mode, which uppercases,
 * reverses, and marks the surrounding prose while leaving the opaque {@code ⟦IG#⟧} sentinels intact.
 *
 * <p>The set of Technical_Tokens for a Source_Text is exactly what
 * {@link TechnicalTokenProtector#mask(String)} detects; the property asserts that masking followed
 * by an adversarial provider call and a restore reproduces each detected token verbatim, and that
 * {@link TechnicalTokenProtector#allTokensPreserved(String, MaskedText)} agrees.
 *
 * <p>Validates: Requirements 2.3, 5.2, 7.1.
 */
class TechnicalTokenPreservationProperties {

    // ---- Property 1: detected Technical_Tokens survive an adversarial translation ---------------

    @Property(tries = 200)
    void technicalTokensSurviveAdversarialTranslation(
            @ForAll("sourceTexts") String source,
            @ForAll("nonEnglishTargets") LanguageTag target) {

        TechnicalTokenProtector protector = new TechnicalTokenProtector();
        MaskedText masked = protector.mask(source);

        // The property is meaningful only when the Source_Text actually contains Technical_Tokens.
        Assume.that(masked.hasTokens());

        AdversarialTranslationProvider provider =
                new AdversarialTranslationProvider(SentinelHandling.PRESERVE);
        String providerOutput =
                provider.translate(masked.masked(), SupportedLanguages.ENGLISH, target).orElseThrow();

        String restored = protector.restore(providerOutput, masked);

        // Every detected Technical_Token appears byte-for-byte in the restored output, even though
        // the adversarial provider rewrote all of the surrounding non-token prose.
        assertThat(protector.allTokensPreserved(restored, masked)).isTrue();
        for (String token : masked.tokens()) {
            assertThat(restored)
                    .as("Technical_Token '%s' must survive byte-for-byte", token)
                    .contains(token);
        }
        assertThat(provider.invocationCount()).isEqualTo(1);
    }

    // ---- Worked examples: concrete mixed content with hand-verified tokens ----------------------

    @Example
    void mixedContentPreservesEveryInjectedTechnicalToken() {
        // Prose words separate each Technical_Token so command detection does not absorb its
        // neighbours; every bracketed substring below is a Technical_Token that must survive.
        String source = "please run git status then review the file /etc/passwd on host "
                + "db.prod.internal with score 0.91 at 2024-01-15T02:30:00Z code "
                + "DUAL_CONTROL_REQUIRED for session-42";
        String[] injectedTokens = {
                "git status",
                "/etc/passwd",
                "db.prod.internal",
                "0.91",
                "2024-01-15T02:30:00Z",
                "DUAL_CONTROL_REQUIRED",
                "session-42"
        };

        TechnicalTokenProtector protector = new TechnicalTokenProtector();
        MaskedText masked = protector.mask(source);

        AdversarialTranslationProvider provider =
                new AdversarialTranslationProvider(SentinelHandling.PRESERVE);
        String providerOutput =
                provider.translate(masked.masked(), SupportedLanguages.ENGLISH, LanguageTag.of("hi"))
                        .orElseThrow();
        String restored = protector.restore(providerOutput, masked);

        for (String token : injectedTokens) {
            assertThat(restored)
                    .as("injected Technical_Token '%s' must survive byte-for-byte", token)
                    .contains(token);
        }
        assertThat(protector.allTokensPreserved(restored, masked)).isTrue();
    }

    @Example
    void adversarialProviderTrulyRewritesNonTokenProse() {
        // Confirms the fake is genuinely adversarial: it destroys the lowercase prose (so the token
        // survival is a real guarantee, not a no-op passthrough) while keeping every sentinel.
        String source = "please confirm the alert about rm -rf on host api.service.io";

        TechnicalTokenProtector protector = new TechnicalTokenProtector();
        MaskedText masked = protector.mask(source);

        AdversarialTranslationProvider provider =
                new AdversarialTranslationProvider(SentinelHandling.PRESERVE);
        String providerOutput =
                provider.translate(masked.masked(), SupportedLanguages.ENGLISH, LanguageTag.of("ta"))
                        .orElseThrow();

        // The adversarial rewrite uppercases prose, so the lowercase words no longer appear verbatim.
        assertThat(providerOutput).doesNotContain("please confirm the alert about");

        String restored = protector.restore(providerOutput, masked);
        assertThat(restored).contains("api.service.io");
        assertThat(protector.allTokensPreserved(restored, masked)).isTrue();
    }

    // ---- generators ------------------------------------------------------------------------------

    @Provide
    Arbitrary<String> sourceTexts() {
        // Interleave Technical_Tokens and plain prose words; most lists contain at least one token,
        // and the property Assumes token presence for the rest.
        Arbitrary<String> parts = Arbitraries.oneOf(technicalTokens(), proseWords());
        return parts.list().ofMinSize(3).ofMaxSize(10).map(list -> String.join(" ", list));
    }

    @Provide
    Arbitrary<String> technicalTokens() {
        Arbitrary<String> commands = Arbitraries.of(
                "git status", "git commit", "kubectl apply", "docker ps",
                "rm -rf", "curl", "ssh", "npm install", "apt-get update");
        Arbitrary<String> unixPaths = Arbitraries.of(
                "/etc/passwd", "/var/log/syslog", "src/main/App.java", "/usr/local/bin");
        Arbitrary<String> windowsPaths = Arbitraries.of("C:\\Users\\admin", "D:\\data\\out.txt");
        Arbitrary<String> hosts = Arbitraries.of("example.com", "api.service.io", "db.prod.internal");
        Arbitrary<String> urls = Arbitraries.of("https://example.com/data", "http://api.host.net/v1");
        Arbitrary<String> ips = Arbitraries.of("10.0.0.1", "192.168.1.100");
        Arbitrary<String> scores = Arbitraries.of("0.87", "0.91", "42", "3.14159");
        Arbitrary<String> timestamps = Arbitraries.of("2024-01-15T02:30:00Z", "2023-12-31T23:59:59Z");
        Arbitrary<String> reasonCodes = Arbitraries.of("DUAL_CONTROL_REQUIRED", "BLAST_RADIUS_EXCEEDED");
        Arbitrary<String> ids = Arbitraries.of("session-42", "req-99", "IntentSessionManager.open()");
        return Arbitraries.oneOf(commands, unixPaths, windowsPaths, hosts, urls, ips, scores,
                timestamps, reasonCodes, ids);
    }

    @Provide
    Arbitrary<String> proseWords() {
        // Plain words that are not known executables and are not themselves Technical_Tokens.
        return Arbitraries.of("the", "operator", "should", "review", "and", "then",
                "please", "confirm", "before", "session", "alert", "message", "about", "with");
    }

    @Provide
    Arbitrary<LanguageTag> nonEnglishTargets() {
        return Arbitraries.of("hi", "bn", "te", "mr", "ta", "gu", "kn", "ml", "pa", "or")
                .map(LanguageTag::of);
    }
}
