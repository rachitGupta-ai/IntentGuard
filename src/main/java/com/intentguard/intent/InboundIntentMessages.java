package com.intentguard.intent;

import java.util.Map;

import com.intentguard.translation.LanguageTag;

/**
 * Localized operator-facing prompts returned by {@link InboundIntentService} when an inbound
 * Declared_Intent translation fails and the submission is rejected (Req 3.3, 3.4).
 *
 * <p>Each prompt is presented in the Operator's {@code Language_Preference} and asks the operator to
 * retry or submit the intent in English. Indian-language strings are stored in their native script
 * encoded as UTF-8 (Req 6.3). When no localized string is configured for a language the English text
 * is used as the fallback, so a prompt is always available. Instances are immutable and thread-safe.
 */
final class InboundIntentMessages {

    /**
     * Prompt shown when an inbound Declared_Intent could not be translated to the Engine_Language,
     * asking the operator to retry or submit in English (Req 3.3, 3.4). English and Hindi are
     * provided in-language; other Supported_Languages fall back to the English prompt via
     * {@link #translationFailed(LanguageTag)} until in-language copy is added.
     */
    private static final Map<String, String> TRANSLATION_FAILED = Map.ofEntries(
            Map.entry("en", "Translation failed. Please try again or submit your intent in English."),
            Map.entry("hi", "\u0905\u0928\u0941\u0935\u093e\u0926 \u0935\u093f\u092b\u0932 \u0930\u0939\u093e\u0964 "
                    + "\u0915\u0943\u092a\u092f\u093e \u092a\u0941\u0928\u0903 \u092a\u094d\u0930\u092f\u093e\u0938 "
                    + "\u0915\u0930\u0947\u0902 \u092f\u093e \u0905\u092a\u0928\u093e \u0907\u0930\u093e\u0926\u093e "
                    + "\u0905\u0902\u0917\u094d\u0930\u0947\u091c\u093c\u0940 \u092e\u0947\u0902 \u0926\u0930\u094d\u091c "
                    + "\u0915\u0930\u0947\u0902\u0964"));

    private InboundIntentMessages() {
    }

    /**
     * Localized inbound-translation-failed prompt for {@code language}, falling back to English when
     * no localized string is configured (Req 3.3, 3.4).
     *
     * @param language the Operator's {@code Language_Preference}; {@code null} yields English
     * @return the localized retry/English prompt; never {@code null} or blank
     */
    static String translationFailed(LanguageTag language) {
        String key = language == null ? "en" : language.value();
        return TRANSLATION_FAILED.getOrDefault(key, TRANSLATION_FAILED.get("en"));
    }
}
