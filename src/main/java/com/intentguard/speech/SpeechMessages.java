package com.intentguard.speech;

import java.util.Map;

import com.intentguard.translation.LanguageTag;

/**
 * Localized operator-facing messages the {@link SpeechService} returns on speech-recognition
 * timeout (Req 4.3) and error (Req 4.4), and when audio is rejected for a mismatched language
 * (Req 4.5). Each message is presented in the Operator's {@code Language_Preference}; when a
 * message is not available for a language the English text is used as a safe fallback, mirroring
 * the feature-wide "fail to English" rule.
 *
 * <p>These are short, fixed UI strings (not machine translations), stored in each language's native
 * script encoded as UTF-8 (Req 6.3). Instances are immutable and thread-safe.
 */
final class SpeechMessages {

    /** Prompt shown when speech recognition times out and the audio is discarded (Req 4.3). */
    private static final Map<String, String> RETRY_PROMPT = Map.ofEntries(
            Map.entry("en", "Speech recognition timed out. Please try again."),
            Map.entry("hi", "वाक् पहचान का समय समाप्त हो गया। कृपया पुनः प्रयास करें।"),
            Map.entry("bn", "কণ্ঠস্বর শনাক্তকরণের সময় শেষ হয়েছে। অনুগ্রহ করে আবার চেষ্টা করুন।"),
            Map.entry("te", "వాక్కు గుర్తింపు గడువు ముగిసింది. దయచేసి మళ్లీ ప్రయత్నించండి."),
            Map.entry("mr", "उच्चार ओळखण्याची वेळ संपली. कृपया पुन्हा प्रयत्न करा."),
            Map.entry("ta", "பேச்சு அறிதல் நேரம் முடிந்தது. மீண்டும் முயற்சிக்கவும்."),
            Map.entry("gu", "વાણી ઓળખવાનો સમય પૂરો થયો. કૃપા કરીને ફરી પ્રયાસ કરો."),
            Map.entry("kn", "ಧ್ವನಿ ಗುರುತಿಸುವಿಕೆ ಸಮಯ ಮೀರಿದೆ. ದಯವಿಟ್ಟು ಮತ್ತೆ ಪ್ರಯತ್ನಿಸಿ."),
            Map.entry("ml", "സംഭാഷണം തിരിച്ചറിയൽ സമയം കഴിഞ്ഞു. ദയവായി വീണ്ടും ശ്രമിക്കുക."),
            Map.entry("pa", "ਬੋਲੀ ਪਛਾਣ ਦਾ ਸਮਾਂ ਖਤਮ ਹੋ ਗਿਆ। ਕਿਰਪਾ ਕਰਕੇ ਦੁਬਾਰਾ ਕੋਸ਼ਿਸ਼ ਕਰੋ।"),
            Map.entry("or", "ସ୍ୱର ଚିହ୍ନଟ ସମୟ ସମାପ୍ତ ହେଲା। ଦୟାକରି ପୁଣି ଚେଷ୍ଟା କରନ୍ତୁ।"));

    /** Message shown when the provider reports a speech-recognition error (Req 4.4). */
    private static final Map<String, String> RECOGNITION_FAILED = Map.ofEntries(
            Map.entry("en", "Speech recognition failed. Please try again or type your intent."),
            Map.entry("hi", "वाक् पहचान विफल रही। कृपया पुनः प्रयास करें या अपना इरादा टाइप करें।"),
            Map.entry("bn", "কণ্ঠস্বর শনাক্তকরণ ব্যর্থ হয়েছে। অনুগ্রহ করে আবার চেষ্টা করুন বা টাইপ করুন।"),
            Map.entry("te", "వాక్కు గుర్తింపు విఫలమైంది. దయచేసి మళ్లీ ప్రయత్నించండి లేదా టైప్ చేయండి."),
            Map.entry("mr", "उच्चार ओळख अयशस्वी झाली. कृपया पुन्हा प्रयत्न करा किंवा टाइप करा."),
            Map.entry("ta", "பேச்சு அறிதல் தோல்வியடைந்தது. மீண்டும் முயற்சிக்கவும் அல்லது தட்டச்சு செய்யவும்."),
            Map.entry("gu", "વાણી ઓળખ નિષ્ફળ ગઈ. કૃપા કરીને ફરી પ્રયાસ કરો અથવા ટાઇપ કરો."),
            Map.entry("kn", "ಧ್ವನಿ ಗುರುತಿಸುವಿಕೆ ವಿಫಲವಾಗಿದೆ. ದಯವಿಟ್ಟು ಮತ್ತೆ ಪ್ರಯತ್ನಿಸಿ ಅಥವಾ ಟೈಪ್ ಮಾಡಿ."),
            Map.entry("ml", "സംഭാഷണം തിരിച്ചറിയൽ പരാജയപ്പെട്ടു. ദയവായി വീണ്ടും ശ്രമിക്കുക അല്ലെങ്കിൽ ടൈപ്പ് ചെയ്യുക."),
            Map.entry("pa", "ਬੋਲੀ ਪਛਾਣ ਅਸਫਲ ਰਹੀ। ਕਿਰਪਾ ਕਰਕੇ ਦੁਬਾਰਾ ਕੋਸ਼ਿਸ਼ ਕਰੋ ਜਾਂ ਟਾਈਪ ਕਰੋ।"),
            Map.entry("or", "ସ୍ୱର ଚିହ୍ନଟ ବିଫଳ ହେଲା। ଦୟାକରି ପୁଣି ଚେଷ୍ଟା କରନ୍ତୁ କିମ୍ବା ଟାଇପ୍ କରନ୍ତୁ।"));

    /** Message shown when submitted audio does not match the selected language (Req 4.5). */
    private static final Map<String, String> LANGUAGE_MISMATCH = Map.ofEntries(
            Map.entry("en", "The audio language does not match your selected language."),
            Map.entry("hi", "ऑडियो की भाषा आपकी चुनी हुई भाषा से मेल नहीं खाती।"),
            Map.entry("bn", "অডিওর ভাষা আপনার নির্বাচিত ভাষার সাথে মেলে না।"),
            Map.entry("te", "ఆడియో భాష మీరు ఎంచుకున్న భాషతో సరిపోలడం లేదు."),
            Map.entry("mr", "ऑडिओची भाषा तुमच्या निवडलेल्या भाषेशी जुळत नाही."),
            Map.entry("ta", "ஒலியின் மொழி நீங்கள் தேர்ந்தெடுத்த மொழியுடன் பொருந்தவில்லை."),
            Map.entry("gu", "ઓડિયોની ભાષા તમારી પસંદ કરેલી ભાષા સાથે મેળ ખાતી નથી."),
            Map.entry("kn", "ಆಡಿಯೋ ಭಾಷೆ ನೀವು ಆಯ್ಕೆ ಮಾಡಿದ ಭಾಷೆಗೆ ಹೊಂದಿಕೆಯಾಗುವುದಿಲ್ಲ."),
            Map.entry("ml", "ഓഡിയോയുടെ ഭാഷ നിങ്ങൾ തിരഞ്ഞെടുത്ത ഭാഷയുമായി പൊരുത്തപ്പെടുന്നില്ല."),
            Map.entry("pa", "ਆਡੀਓ ਦੀ ਭਾਸ਼ਾ ਤੁਹਾਡੀ ਚੁਣੀ ਭਾਸ਼ਾ ਨਾਲ ਮੇਲ ਨਹੀਂ ਖਾਂਦੀ।"),
            Map.entry("or", "ଅଡିଓର ଭାଷା ଆପଣ ବାଛିଥିବା ଭାଷା ସହିତ ମେଳ ଖାଉ ନାହିଁ।"));

    private SpeechMessages() {
    }

    /** Localized speech-recognition-timeout retry prompt for {@code language} (Req 4.3). */
    static String retryPrompt(LanguageTag language) {
        return localized(RETRY_PROMPT, language);
    }

    /** Localized speech-recognition-failed message for {@code language} (Req 4.4). */
    static String recognitionFailed(LanguageTag language) {
        return localized(RECOGNITION_FAILED, language);
    }

    /** Localized language-mismatch rejection message for {@code language} (Req 4.5). */
    static String languageMismatch(LanguageTag language) {
        return localized(LANGUAGE_MISMATCH, language);
    }

    private static String localized(Map<String, String> table, LanguageTag language) {
        String key = language == null ? "en" : language.value();
        return table.getOrDefault(key, table.get("en"));
    }
}
