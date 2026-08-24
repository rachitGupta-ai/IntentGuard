package com.intentguard.translation;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * In-memory {@link TranslationProvider} test fake that returns native Indian-script (non-ASCII,
 * UTF-8) output while faithfully preserving any {@code TechnicalTokenProtector} sentinels present in
 * the masked input.
 *
 * <p>This fake is the stressor for Property 16 (script fidelity preserved end-to-end): it models a
 * real Translation_Provider that rewrites the translatable prose into a target Indian script but
 * leaves the opaque {@code ⟦IG#⟧} sentinels untouched. The configured {@link #nativeOutput} is the
 * native-script "translation" it emits; any sentinels found in the masked text are appended so that
 * a subsequent {@code restore} reproduces every Technical_Token byte-for-byte and the
 * {@code TranslationService} yields {@link TranslationOutcome#TRANSLATED} rather than a
 * token-integrity fallback.
 *
 * <p>Because the sentinels use {@code U+27E6 / U+27E7} — far outside every Indic script block — the
 * native output can never collide with the sentinel scheme, so the emitted string's non-sentinel
 * portion is exactly the configured native-script content, code point for code point.
 */
public final class NativeScriptTranslationProvider implements TranslationProvider {

    /** Sentinel pattern matching the design's {@code ⟦IG#⟧} placeholders. */
    private static final Pattern SENTINEL_PATTERN = Pattern.compile("\u27E6IG\\d+\u27E7");

    private final String id;
    private final String nativeOutput;
    private final AtomicInteger invocations = new AtomicInteger();

    /** Creates a native-script fake with the default id {@code "native-script-fake"}. */
    public NativeScriptTranslationProvider(String nativeOutput) {
        this("native-script-fake", nativeOutput);
    }

    /**
     * Creates a native-script fake with an explicit id.
     *
     * @param id           the provider identity to report from {@link #id()}
     * @param nativeOutput the native Indian-script string this fake returns as its translation
     */
    public NativeScriptTranslationProvider(String id, String nativeOutput) {
        this.id = id;
        this.nativeOutput = nativeOutput;
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public Optional<String> translate(String maskedText, LanguageTag source, LanguageTag target) {
        invocations.incrementAndGet();
        // Emit the native-script translation, then re-append any masked sentinels so that a restore
        // reproduces every Technical_Token (the prose between/around tokens is replaced by native
        // script, exactly as a real provider would rewrite translatable text).
        StringBuilder out = new StringBuilder(nativeOutput);
        if (maskedText != null) {
            Matcher matcher = SENTINEL_PATTERN.matcher(maskedText);
            while (matcher.find()) {
                out.append(matcher.group());
            }
        }
        return Optional.of(out.toString());
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
