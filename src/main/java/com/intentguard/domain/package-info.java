/**
 * Shared domain kernel for IntentGuard. Contains the core records and enums used across the
 * ingest, scoring, decision, intent, and profile modules: {@code CommandEvent}, raw signal
 * types, scoring types ({@code ComponentResult}, {@code DivergenceResult}, {@code ScoringContext},
 * {@code ScoringConfig}), the {@code Decision}/{@code Verdict} outcome types, {@code Actor}
 * identity, and the enums that classify input origin, signal source, and intent source.
 */
package com.intentguard.domain;
