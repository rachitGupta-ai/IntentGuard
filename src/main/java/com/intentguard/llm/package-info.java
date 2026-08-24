/**
 * LLM_Service module. Wraps the Google Gemini Java SDK for Semantic_Inconsistency scoring and
 * human-readable Explanation text, with strict timeouts and deterministic fallbacks so the
 * pipeline degrades gracefully when Gemini is slow or unavailable.
 */
package com.intentguard.llm;
