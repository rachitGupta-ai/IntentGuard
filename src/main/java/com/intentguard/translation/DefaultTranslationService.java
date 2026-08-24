package com.intentguard.translation;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import jakarta.annotation.PreDestroy;

/**
 * Default {@link TranslationService} implementation: pure orchestration around a pluggable
 * {@link TranslationProvider}, a {@link TranslationCache}, the {@link SupportedLanguages} guard, and
 * the {@link TechnicalTokenProtector} integrity guarantee.
 *
 * <p>The {@link #translate} flow applies the following steps in order, each short-circuiting with a
 * distinct {@link TranslationOutcome} (see the design's Components section):
 * <ol>
 *   <li><strong>English passthrough</strong> (Req 2.2): when the target is English, return the input
 *       unchanged and issue no provider request &rarr; {@link TranslationOutcome#ENGLISH_PASSTHROUGH}.</li>
 *   <li><strong>Unsupported-language guard</strong> (Req 6.4): when either tag is outside the
 *       configured Supported_Language set, return the original English content &rarr;
 *       {@link TranslationOutcome#UNSUPPORTED_LANGUAGE}. This is checked before provider resolution,
 *       so an unsupported language is reported as such even when the provider is unavailable.</li>
 *   <li><strong>Cache reuse</strong> (Req 9.3): a prior Translated_Text for the same
 *       {@code (Source_Text, target)} pair is returned without a new provider request &rarr;
 *       {@link TranslationOutcome#CACHED}.</li>
 *   <li><strong>Mask &rarr; provider &rarr; restore &rarr; integrity check</strong> (Req 2.3, 7.1,
 *       7.4): Technical_Tokens are masked, the provider call is bounded by the configured timeout,
 *       tokens are restored, and if any token was lost the original Source_Text is returned &rarr;
 *       {@link TranslationOutcome#TOKEN_INTEGRITY_FALLBACK} (no failure recorded).</li>
 *   <li><strong>Timeout / error fallback</strong> (Req 2.4, 2.5, 9.1): a provider that exceeds the
 *       budget yields {@link TranslationOutcome#PROVIDER_TIMEOUT}; an error / empty result yields
 *       {@link TranslationOutcome#PROVIDER_ERROR}; both return the original English content.</li>
 * </ol>
 *
 * <p>The active provider is selected by identity from
 * {@link TranslationRuntimeConfig#getActive()} so an Administrator hot-reload takes effect on the
 * next request (Req 8.3). The selected provider's {@link TranslationProvider#id() identity} is
 * captured on a successful translation for the Translation_Record written by a later task (Req 8.7,
 * 10.1); persistence and sensitive-content gating are intentionally out of scope here (tasks 6.1,
 * 6.2), but the successful-translation seam ({@link #onTranslated}) keeps the design open for them.
 */
@Service
public class DefaultTranslationService implements TranslationService {

    private static final Logger log = System.getLogger(DefaultTranslationService.class.getName());

    private final Map<String, TranslationProvider> providersById;
    private final TranslationCache cache;
    private final TranslationRuntimeConfig runtimeConfig;
    private final SupportedLanguages supportedLanguages;
    private final TechnicalTokenProtector tokenProtector;
    private final DomainGlossary glossary;
    private final TranslationRecordRepository recordRepository;
    private final ExecutorService executor;

    /**
     * Direct-construction constructor used by unit and property tests that exercise the pure
     * orchestration without a Datastore. Persistence is disabled (no {@link TranslationRecord} is
     * written) and no domain glossary is configured, so the translation still returns exactly as
     * before.
     */
    public DefaultTranslationService(
            List<TranslationProvider> providers,
            TranslationCache cache,
            TranslationRuntimeConfig runtimeConfig,
            SupportedLanguages supportedLanguages,
            TechnicalTokenProtector tokenProtector) {
        this(providers, cache, runtimeConfig, supportedLanguages, tokenProtector,
                (TranslationRecordRepository) null, DomainGlossary.empty());
    }

    /**
     * Direct-construction constructor used by tests that exercise the domain-glossary term mapping
     * (Req 12.2, Property 18) without a Datastore. Persistence is disabled; the supplied
     * {@link DomainGlossary} is applied so each configured term is rendered as its configured
     * Translated_Text.
     */
    public DefaultTranslationService(
            List<TranslationProvider> providers,
            TranslationCache cache,
            TranslationRuntimeConfig runtimeConfig,
            SupportedLanguages supportedLanguages,
            TechnicalTokenProtector tokenProtector,
            DomainGlossary glossary) {
        this(providers, cache, runtimeConfig, supportedLanguages, tokenProtector,
                (TranslationRecordRepository) null, glossary);
    }

    /**
     * Direct-construction constructor used by persistence-focused tests: persists a
     * {@link TranslationRecord} through the supplied optional repository but configures no glossary.
     */
    public DefaultTranslationService(
            List<TranslationProvider> providers,
            TranslationCache cache,
            TranslationRuntimeConfig runtimeConfig,
            SupportedLanguages supportedLanguages,
            TechnicalTokenProtector tokenProtector,
            ObjectProvider<TranslationRecordRepository> recordRepositoryProvider) {
        this(providers, cache, runtimeConfig, supportedLanguages, tokenProtector,
                recordRepositoryProvider == null ? null : recordRepositoryProvider.getIfAvailable(),
                DomainGlossary.empty());
    }

    /**
     * Spring-injected constructor that additionally persists a {@link TranslationRecord} on a
     * successful translation (Req 10.1) and applies the configured {@link DomainGlossary} (Req 12.2).
     * The repository is resolved through an {@link ObjectProvider} so it is <em>optional</em>: when no
     * {@link TranslationRecordRepository} bean is present the service still functions and simply
     * persists nothing. The glossary bean defaults to {@link DomainGlossary#empty()} (see
     * {@link TranslationConfig}), so an unconfigured deployment behaves exactly as before.
     */
    @Autowired
    public DefaultTranslationService(
            List<TranslationProvider> providers,
            TranslationCache cache,
            TranslationRuntimeConfig runtimeConfig,
            SupportedLanguages supportedLanguages,
            TechnicalTokenProtector tokenProtector,
            ObjectProvider<TranslationRecordRepository> recordRepositoryProvider,
            DomainGlossary glossary) {
        this(providers, cache, runtimeConfig, supportedLanguages, tokenProtector,
                recordRepositoryProvider == null ? null : recordRepositoryProvider.getIfAvailable(),
                glossary);
    }

    private DefaultTranslationService(
            List<TranslationProvider> providers,
            TranslationCache cache,
            TranslationRuntimeConfig runtimeConfig,
            SupportedLanguages supportedLanguages,
            TechnicalTokenProtector tokenProtector,
            TranslationRecordRepository recordRepository,
            DomainGlossary glossary) {
        this.providersById = providers == null ? Map.of() : providers.stream()
                .collect(Collectors.toUnmodifiableMap(TranslationProvider::id, Function.identity(),
                        (first, second) -> first));
        this.cache = cache;
        this.runtimeConfig = runtimeConfig;
        this.supportedLanguages = supportedLanguages;
        this.tokenProtector = tokenProtector;
        this.glossary = glossary == null ? DomainGlossary.empty() : glossary;
        this.recordRepository = recordRepository;
        this.executor = Executors.newCachedThreadPool(daemonThreadFactory());
    }

    @Override
    public TranslationResult translate(String sourceText, LanguageTag sourceLang, LanguageTag targetLang,
            boolean sensitive) {
        String source = sourceText == null ? "" : sourceText;

        // 1. English passthrough (Req 2.2): the target is the Engine_Language, nothing to translate.
        if (SupportedLanguages.ENGLISH.equals(targetLang)) {
            return new TranslationResult(source, false, TranslationOutcome.ENGLISH_PASSTHROUGH);
        }

        // 2. Unsupported-language guard (Req 6.4): checked before touching the provider, so an
        // unsupported tag is reported as such even when the provider is unavailable.
        if (!supportedLanguages.isSupported(sourceLang) || !supportedLanguages.isSupported(targetLang)) {
            return new TranslationResult(source, false, TranslationOutcome.UNSUPPORTED_LANGUAGE);
        }

        // 3. Text-translation capability gate (Req 8.4, 8.6): when the Translation_Provider
        // credential was absent at startup, the text-translation capability is disabled for the life
        // of the process. Present Operator_Facing_Content in English and NEVER invoke a provider.
        // The flag is fixed at startup (Req 8.6), so a provider hot-reload can never re-enable a
        // capability whose credential was absent at startup. Speech is gated independently (Req 8.5),
        // so a missing translation credential disables only text translation while speech stays
        // available when its own credential is present.
        if (!runtimeConfig.isTextTranslationEnabled()) {
            return new TranslationResult(source, false, TranslationOutcome.ENGLISH_PASSTHROUGH);
        }

        TranslationRuntimeConfig.Snapshot config = runtimeConfig.getActive();

        // 4. Sensitive-content gate (Req 11.3): when content is marked sensitive and configuration
        // does not permit translating sensitive content, present it in English and NEVER transmit it
        // to the Translation_Provider. This is checked before the cache lookup so a previously cached
        // Translated_Text can never leak a translation of sensitive content, and before any provider
        // call so the content never leaves the process.
        if (sensitive && !config.sensitiveContentTranslatable()) {
            return new TranslationResult(source, false, TranslationOutcome.ENGLISH_PASSTHROUGH);
        }

        // 5-8. Cache, mask (Technical_Token + glossary), provider call, restore, integrity check,
        // and record (Req 2.3-2.5, 7.1, 7.4, 9.1, 9.3, 12.2): shared with the inbound path.
        return performProviderTranslation(
                source, sourceLang, targetLang, config, TranslationRecordKind.OUTBOUND_CONTENT);
    }

    @Override
    public TranslationResult translateInbound(String sourceText, LanguageTag sourceLang) {
        String source = sourceText == null ? "" : sourceText;
        LanguageTag targetLang = SupportedLanguages.ENGLISH;

        // An English submission needs no translation: the engine opens the session on it directly.
        // Unlike outbound, this is keyed on the SOURCE being English (the target is always English),
        // so the outbound English-target passthrough short-circuit does not apply here.
        if (SupportedLanguages.ENGLISH.equals(sourceLang)) {
            return new TranslationResult(source, false, TranslationOutcome.ENGLISH_PASSTHROUGH);
        }

        // Unsupported-language guard (Req 6.4): a source outside the Supported_Language set cannot be
        // translated to Engine_Language; report it before touching the provider.
        if (!supportedLanguages.isSupported(sourceLang)) {
            return new TranslationResult(source, false, TranslationOutcome.UNSUPPORTED_LANGUAGE);
        }

        // Text-translation capability gate (Req 8.4, 8.6): with the credential absent at startup
        // there is no way to produce Engine_Language text; report a provider error so the inbound
        // orchestration rejects the submission and prompts the operator to submit in English.
        if (!runtimeConfig.isTextTranslationEnabled()) {
            return new TranslationResult(source, false, TranslationOutcome.PROVIDER_ERROR);
        }

        TranslationRuntimeConfig.Snapshot config = runtimeConfig.getActive();
        return performProviderTranslation(
                source, sourceLang, targetLang, config, TranslationRecordKind.INBOUND_INTENT);
    }

    /**
     * The shared cache &rarr; mask &rarr; provider &rarr; restore &rarr; integrity &rarr; record flow
     * used by both the outbound {@link #translate} path and the inbound {@link #translateInbound}
     * path. The caller applies the direction-specific short-circuits (English passthrough,
     * unsupported-language guard, sensitive-content gate, capability gate) before delegating here.
     * The {@code kind} distinguishes an {@link TranslationRecordKind#OUTBOUND_CONTENT} record from an
     * {@link TranslationRecordKind#INBOUND_INTENT} record for the persisted provenance (Req 10.1).
     */
    private TranslationResult performProviderTranslation(String source, LanguageTag sourceLang,
            LanguageTag targetLang, TranslationRuntimeConfig.Snapshot config, TranslationRecordKind kind) {
        // Cache reuse (Req 9.3): reuse a prior Translated_Text without a new provider request.
        Optional<String> cached = cache.lookup(source, targetLang);
        if (cached.isPresent()) {
            return new TranslationResult(cached.get(), true, TranslationOutcome.CACHED);
        }

        TranslationProvider provider = providersById.get(config.translationProviderId());
        if (provider == null) {
            // No provider configured/available for the active identity: fail to original (Req 2.5).
            log.log(Level.DEBUG, "No Translation_Provider registered for id {0}; presenting original",
                    config.translationProviderId());
            return new TranslationResult(source, false, TranslationOutcome.PROVIDER_ERROR);
        }

        // Mask Technical_Tokens before the text ever leaves the process (Req 2.3, 7.1).
        MaskedText masked = tokenProtector.mask(source);

        // Mask any configured domain-glossary terms so the provider never sees them and the
        // configured Translated_Text is rendered verbatim on restore (Req 12.2, Property 18). Uses a
        // distinct sentinel scheme from the Technical_Token protector, so the two never collide; when
        // no glossary is configured for the target this is a no-op and the text is unchanged.
        DomainGlossary.Masked glossaryMasked = glossary.mask(masked.masked(), targetLang);

        ProviderCall call = callProvider(provider, glossaryMasked.masked(), sourceLang, targetLang, config);
        if (call.outcome() != null) {
            // Timeout or error: fall back to the original content (Req 2.4, 2.5, 9.1).
            return new TranslationResult(source, false, call.outcome());
        }

        // Restore glossary terms to their configured Translated_Text (Req 12.2), then restore
        // Technical_Tokens and verify byte-for-byte integrity (Req 7.1, 7.4). Glossary restore runs
        // first so its sentinels are resolved before the Technical_Token restore pass.
        String glossaryRestored = glossary.restore(call.result(), glossaryMasked);
        String restored = tokenProtector.restore(glossaryRestored, masked);
        if (!tokenProtector.allTokensPreserved(restored, masked)) {
            // A Technical_Token was lost or garbled: return the original Source_Text and record
            // NO translation failure (Req 7.4).
            return new TranslationResult(source, false, TranslationOutcome.TOKEN_INTEGRITY_FALLBACK);
        }

        // Success: reuse on subsequent identical requests (Req 9.3) and record provenance.
        cache.store(source, targetLang, restored);
        onTranslated(source, restored, sourceLang, targetLang, provider.id(), kind);
        return new TranslationResult(restored, true, TranslationOutcome.TRANSLATED);
    }

    /**
     * Invokes the provider bounded by the configured translation timeout, classifying the failure
     * mode. The provider adapters never throw across their boundary (they map errors to
     * {@link Optional#empty()}); this method additionally bounds a slow/hanging provider so a
     * timeout is distinguished from an error for the recorded outcome.
     *
     * @return a {@link ProviderCall} carrying either the translated masked text (outcome
     *         {@code null}) or the classified failure outcome
     */
    private ProviderCall callProvider(TranslationProvider provider, String maskedText,
            LanguageTag sourceLang, LanguageTag targetLang, TranslationRuntimeConfig.Snapshot config) {
        Future<Optional<String>> future =
                executor.submit(() -> provider.translate(maskedText, sourceLang, targetLang));
        try {
            Optional<String> result = future.get(config.translationTimeoutMs(), TimeUnit.MILLISECONDS);
            if (result == null || result.isEmpty()) {
                return ProviderCall.failed(TranslationOutcome.PROVIDER_ERROR);
            }
            return ProviderCall.ok(result.get());
        } catch (TimeoutException timeout) {
            future.cancel(true);
            log.log(Level.DEBUG, "Translation_Provider exceeded the {0}ms budget; presenting English",
                    config.translationTimeoutMs());
            return ProviderCall.failed(TranslationOutcome.PROVIDER_TIMEOUT);
        } catch (InterruptedException interrupted) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            return ProviderCall.failed(TranslationOutcome.PROVIDER_ERROR);
        } catch (Exception error) {
            future.cancel(true);
            log.log(Level.DEBUG, "Translation_Provider call failed; presenting English", error);
            return ProviderCall.failed(TranslationOutcome.PROVIDER_ERROR);
        }
    }

    /**
     * Hook invoked on a successful translation with the full provenance (Source_Text,
     * Translated_Text, source/target tags, and Translation_Provider identity). Persists a
     * {@link TranslationRecord} of the given {@link TranslationRecordKind} capturing that provenance
     * (Req 8.7, 10.1) — {@link TranslationRecordKind#OUTBOUND_CONTENT} for the outbound path and
     * {@link TranslationRecordKind#INBOUND_INTENT} for an inbound Declared_Intent translation.
     *
     * <p><strong>Best-effort persistence (Req 10.2):</strong> a persistence failure is caught and
     * logged and MUST NOT prevent the caller from returning the Translated_Text. When no
     * {@link TranslationRecordRepository} is configured (direct-construction tests, or a Datastore
     * that is unavailable at wiring time) nothing is persisted and the translation still returns.
     */
    protected void onTranslated(String sourceText, String translatedText,
            LanguageTag sourceLang, LanguageTag targetLang, String providerId, TranslationRecordKind kind) {
        if (recordRepository == null) {
            return;
        }
        try {
            TranslationRecord record = new TranslationRecord(
                    sourceText,
                    translatedText,
                    sourceLang,
                    targetLang,
                    providerId,
                    kind,
                    System.currentTimeMillis());
            recordRepository.save(record);
        } catch (RuntimeException persistenceFailure) {
            // Req 10.2: a record-persistence failure still presents the Translated_Text.
            log.log(Level.WARNING,
                    "Failed to persist Translation_Record; presenting Translated_Text anyway",
                    persistenceFailure);
        }
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }

    private static ThreadFactory daemonThreadFactory() {
        return runnable -> {
            Thread thread = new Thread(runnable, "translation-service");
            thread.setDaemon(true);
            return thread;
        };
    }

    /** Internal carrier for a bounded provider call: either a result or a classified failure. */
    private record ProviderCall(String result, TranslationOutcome outcome) {
        static ProviderCall ok(String result) {
            return new ProviderCall(result, null);
        }

        static ProviderCall failed(TranslationOutcome outcome) {
            return new ProviderCall(null, outcome);
        }
    }
}
