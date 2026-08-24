package com.intentguard.translation;

/**
 * The kind of translation/speech operation captured by a {@link TranslationRecord} (Req 10.1).
 *
 * <ul>
 *   <li>{@code OUTBOUND_CONTENT} - Operator_Facing_Content translated from the Engine_Language
 *       into an Operator's Language_Preference for display or live-alert delivery.</li>
 *   <li>{@code INBOUND_INTENT} - a Declared_Intent submitted in a Supported_Language and translated
 *       into the Engine_Language before the Intent_Session is opened.</li>
 *   <li>{@code STT} - a speech-to-text recognition request handled by the Speech_Provider.</li>
 *   <li>{@code TTS} - a text-to-speech synthesis request handled by the Speech_Provider.</li>
 * </ul>
 */
public enum TranslationRecordKind {
    OUTBOUND_CONTENT,
    INBOUND_INTENT,
    STT,
    TTS
}
