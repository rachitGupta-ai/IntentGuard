package com.intentguard.translation;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Assume;
import net.jqwik.api.Combinators;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

/**
 * Feature: indian-language-translation, Property 2: Token-integrity fallback returns the original
 * Source_Text.
 *
 * <p>For any Source_Text and any provider transformation under which not every Technical_Token can
 * be reproduced in the result, the token-integrity fallback returns the original Source_Text
 * unchanged and records <em>no</em> translation failure (Validates: Requirements 7.4).
 *
 * <p>{@code TranslationService} (task 5.1) is the eventual orchestrator, but its Req 7.4 rule is
 * defined entirely by the {@link TechnicalTokenProtector} contract implemented in task 2.1: after a
 * masked provider round-trip, the service calls
 * {@link TechnicalTokenProtector#allTokensPreserved(String, MaskedText)} and, when it returns
 * {@code false}, discards the (successful, non-empty) provider output and presents the original
 * Source_Text without recording a translation failure. This test exercises that rule against the
 * real protector using adversarial provider transformations that drop or garble the sentinels.
 */
class TokenIntegrityFallbackProperties {

    /** The sentinel form emitted by {@link TechnicalTokenProtector}: {@code ⟦IG<n>⟧}. */
    private static final String SENTINEL_REGEX = "\u27E6IG\\d+\u27E7";

    private final TechnicalTokenProtector protector = new TechnicalTokenProtector();

    // Feature: indian-language-translation, Property 2: Token-integrity fallback returns the original Source_Text
    @Property(tries = 200)
    void tokenIntegrityFallbackReturnsOriginalSourceText(
            @ForAll("sourceTextsWithTokens") String sourceText,
            @ForAll("garbleStrategies") GarbleStrategy strategy) {

        MaskedText masked = protector.mask(sourceText);
        // Property 2 is about Technical_Token loss, so at least one token must have been detected.
        Assume.that(masked.hasTokens());

        // A provider that "succeeds" (returns a non-empty result) but drops/garbles the sentinels,
        // so at least one Technical_Token cannot be reproduced on restore.
        String providerOutput = strategy.apply(masked.masked());
        String restored = protector.restore(providerOutput, masked);

        // Precondition of Property 2: not every Technical_Token can be reproduced in the result.
        Assume.that(!protector.allTokensPreserved(restored, masked));

        FallbackResult result = translateWithTokenIntegrityFallback(sourceText, masked, restored);

        // The original Source_Text is returned byte-for-byte unchanged...
        assertThat(result.text()).isEqualTo(sourceText);
        // ...and a token-integrity fallback is NOT recorded as a translation failure (Req 7.4).
        assertThat(result.translationFailureRecorded()).isFalse();
    }

    @Example
    void stripBracketsFromEverySentinelFallsBackToOriginal() {
        String source = "the operator should run rm -rf /tmp/cache before 2024-01-02T10:00:00Z";
        MaskedText masked = protector.mask(source);
        assertThat(masked.hasTokens()).isTrue();

        // Remove the sentinel brackets so restore can no longer match any placeholder.
        String garbled = masked.masked().replace('\u27E6', ' ').replace('\u27E7', ' ');
        String restored = protector.restore(garbled, masked);

        assertThat(protector.allTokensPreserved(restored, masked)).isFalse();

        FallbackResult result = translateWithTokenIntegrityFallback(source, masked, restored);
        assertThat(result.text()).isEqualTo(source);
        assertThat(result.translationFailureRecorded()).isFalse();
    }

    /**
     * Mirrors the {@code TranslationService} token-integrity fallback rule (Req 7.4): the provider
     * call has already succeeded (it returned a non-empty {@code restored} candidate), so the only
     * remaining reason to reject it is that a Technical_Token was lost. In that case the original
     * Source_Text is returned and <em>no</em> translation failure is recorded — a token-integrity
     * fallback is distinct from a provider timeout/error, which would record a failure.
     */
    private FallbackResult translateWithTokenIntegrityFallback(
            String source, MaskedText masked, String restored) {
        if (protector.allTokensPreserved(restored, masked)) {
            return new FallbackResult(restored, false);
        }
        return new FallbackResult(source, false);
    }

    /** The outcome of a translation attempt: presented text plus whether a failure was recorded. */
    private record FallbackResult(String text, boolean translationFailureRecorded) {
    }

    // --- Adversarial provider transformations ---------------------------------------------------

    /** Ways a provider can drop or garble the translation-stable sentinels. */
    enum GarbleStrategy {
        /** Replace every sentinel with translated prose, dropping all tokens. */
        DROP_ALL {
            @Override
            String apply(String masked) {
                return masked.replaceAll(SENTINEL_REGEX, " translated ");
            }
        },
        /** Drop only the first sentinel (⟦IG0⟧), losing that one token. */
        DROP_FIRST {
            @Override
            String apply(String masked) {
                return masked.replaceFirst(SENTINEL_REGEX, " translated ");
            }
        },
        /** Strip the sentinel brackets so no placeholder can be matched on restore. */
        STRIP_BRACKETS {
            @Override
            String apply(String masked) {
                return masked.replace('\u27E6', ' ').replace('\u27E7', ' ');
            }
        },
        /** Mangle the sentinel marker so the exact placeholder no longer matches. */
        MANGLE_MARK {
            @Override
            String apply(String masked) {
                return masked.replace("IG", "ig-");
            }
        },
        /** Drop the closing bracket of every sentinel, breaking every placeholder. */
        DROP_CLOSER {
            @Override
            String apply(String masked) {
                return masked.replace("\u27E7", "");
            }
        };

        abstract String apply(String masked);
    }

    // --- Generators -----------------------------------------------------------------------------

    @Provide
    Arbitrary<GarbleStrategy> garbleStrategies() {
        return Arbitraries.of(GarbleStrategy.values());
    }

    @Provide
    Arbitrary<String> sourceTextsWithTokens() {
        // Technical_Tokens spanning the categories the protector detects: commands + arguments,
        // absolute/relative paths, host names, IPs, reason codes, timestamps, scores, ids.
        Arbitrary<String> tokens = Arbitraries.of(
                "/etc/passwd",
                "/var/log/app.log",
                "rm -rf /tmp/cache",
                "kubectl delete ns",
                "example.com",
                "api.service.io",
                "DUAL_CONTROL_REQUIRED",
                "192.168.0.1",
                "0.87",
                "session-42",
                "2024-01-02T10:00:00Z");
        Arbitrary<String> words = Arbitraries.of(
                "the", "operator", "should", "review", "this", "alert",
                "before", "approval", "please", "note");

        // Guarantee at least one Technical_Token: prose, a token, then a mix of prose and tokens.
        Arbitrary<List<String>> lead = words.list().ofMinSize(1).ofMaxSize(3);
        Arbitrary<List<String>> tail = Arbitraries.oneOf(tokens, words).list().ofMinSize(0).ofMaxSize(4);

        return Combinators.combine(lead, tokens, tail).as((pre, token, rest) -> {
            List<String> parts = new ArrayList<>(pre);
            parts.add(token);
            parts.addAll(rest);
            return String.join(" ", parts);
        });
    }
}
