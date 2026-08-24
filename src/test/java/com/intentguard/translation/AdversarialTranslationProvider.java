package com.intentguard.translation;

import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

/**
 * In-memory {@link TranslationProvider} test fake that behaves adversarially: it arbitrarily
 * rewrites the non-token ("prose") portion of the masked text, and — depending on its mode — either
 * faithfully passes the Technical_Token sentinels through or drops/garbles them.
 *
 * <p>This fake is the core stressor for the two Technical_Token correctness properties:
 * <ul>
 *   <li><b>{@link SentinelHandling#PRESERVE}</b> rewrites the surrounding prose (uppercasing and
 *       reversing it, and appending a marker) while leaving every {@code ⟦IG#⟧} sentinel intact.
 *       After a restore, every Technical_Token must still appear byte-for-byte — exercising
 *       Property 1 (tokens preserved byte-for-byte).</li>
 *   <li><b>{@link SentinelHandling#DROP}</b> additionally removes the sentinels, so a restore
 *       cannot reproduce every token and the {@code TranslationService} must fall back to the
 *       original Source_Text — exercising Property 2 (token-integrity fallback).</li>
 * </ul>
 *
 * <p>The default sentinel pattern matches the design's opaque sentinel shape ({@code ⟦IG0⟧},
 * {@code ⟦IG1⟧}, ...) so the fake is self-contained and does not depend on the
 * {@code TechnicalTokenProtector} implementation. A different pattern may be supplied for tests
 * that use a different sentinel scheme.
 */
public final class AdversarialTranslationProvider implements TranslationProvider {

    /** Default sentinel pattern matching the design's {@code ⟦IG#⟧} placeholders. */
    public static final Pattern DEFAULT_SENTINEL_PATTERN = Pattern.compile("\u27E6IG\\d+\u27E7");

    /** Whether the fake preserves or destroys the Technical_Token sentinels. */
    public enum SentinelHandling {
        /** Rewrite prose but keep every sentinel intact (stresses Property 1). */
        PRESERVE,
        /** Rewrite prose and remove every sentinel (stresses Property 2 fallback). */
        DROP
    }

    private final String id;
    private final SentinelHandling handling;
    private final Pattern sentinelPattern;
    private final AtomicInteger invocations = new AtomicInteger();

    /** Creates an adversarial fake that rewrites prose but preserves sentinels. */
    public AdversarialTranslationProvider() {
        this(SentinelHandling.PRESERVE);
    }

    /**
     * Creates an adversarial fake with the given sentinel handling and default id/pattern.
     *
     * @param handling whether to preserve or drop the sentinels
     */
    public AdversarialTranslationProvider(SentinelHandling handling) {
        this("adversarial-fake", handling, DEFAULT_SENTINEL_PATTERN);
    }

    /**
     * Fully-configurable constructor.
     *
     * @param id              the provider identity to report from {@link #id()}
     * @param handling        whether to preserve or drop the sentinels
     * @param sentinelPattern the pattern identifying Technical_Token sentinels in the masked text
     */
    public AdversarialTranslationProvider(String id, SentinelHandling handling, Pattern sentinelPattern) {
        this.id = id;
        this.handling = handling;
        this.sentinelPattern = sentinelPattern;
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public Optional<String> translate(String maskedText, LanguageTag source, LanguageTag target) {
        invocations.incrementAndGet();
        if (maskedText == null) {
            return Optional.empty();
        }
        return switch (handling) {
            case PRESERVE -> Optional.of(rewritePreservingSentinels(maskedText));
            case DROP -> Optional.of(dropSentinels(rewritePreservingSentinels(maskedText)));
        };
    }

    /**
     * Rewrites the prose between sentinels while emitting each matched sentinel unchanged, so the
     * sentinels survive but the surrounding text is arbitrarily mangled.
     */
    private String rewritePreservingSentinels(String maskedText) {
        StringBuilder out = new StringBuilder();
        var matcher = sentinelPattern.matcher(maskedText);
        int last = 0;
        while (matcher.find()) {
            out.append(mangle(maskedText.substring(last, matcher.start())));
            out.append(matcher.group()); // sentinel passed through untouched
            last = matcher.end();
        }
        out.append(mangle(maskedText.substring(last)));
        return out.toString();
    }

    /** Arbitrary, deterministic non-token rewrite: uppercase, reverse, and mark. */
    private static String mangle(String prose) {
        if (prose.isEmpty()) {
            return prose;
        }
        String upper = prose.toUpperCase(Locale.ROOT);
        return "\u00AB" + new StringBuilder(upper).reverse() + "\u00BB";
    }

    /** Removes every sentinel from the given text, garbling token restoration. */
    private String dropSentinels(String text) {
        return sentinelPattern.matcher(text).replaceAll("");
    }

    /**
     * The number of times {@link #translate} has been invoked on this fake.
     *
     * @return the invocation count
     */
    public int invocationCount() {
        return invocations.get();
    }
}
