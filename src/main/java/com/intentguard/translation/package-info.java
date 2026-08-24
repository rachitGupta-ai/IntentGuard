/**
 * Indian-language translation layer around the Control_Tower and Enforcement_Engine (Req 2, 3, 6-11).
 *
 * <p>This package holds the translation domain model and orchestration. It begins with the
 * language identity primitives ({@link com.intentguard.translation.LanguageTag} and the
 * {@link com.intentguard.translation.SupportedLanguages} holder defining the default
 * {@code Supported_Language} set with native-script display names), which are the single source of
 * truth for the unsupported-language guard and for STT/TTS language acceptance (Req 6.1, 6.2, 6.4).
 *
 * <p>The layer is deliberately peripheral: the Enforcement_Engine continues to score, audit, and
 * match policies in the Engine_Language (English). Translation is a best-effort presentation-and-input
 * convenience that falls back to English on any provider failure and never alters Technical_Tokens.
 */
package com.intentguard.translation;
