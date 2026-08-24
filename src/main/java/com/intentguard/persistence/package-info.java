/**
 * MongoDB persistence layer for the IntentGuard Datastore. Provides the {@code MongoClient} /
 * {@code MongoDatabase} Spring beans (configured from {@code intentguard.mongo.*}) with a POJO
 * codec registry, the document classes for the five collections ({@code audit_history},
 * {@code behavioral_profiles}, {@code intent_sessions}, {@code threshold_config},
 * {@code scenario_baselines}), and the repositories over them.
 *
 * <p>Reads of hot-path config and profiles use a {@link com.intentguard.persistence.LastKnownGoodCache}
 * so a transient Datastore read failure falls back to the last-known-good value rather than
 * failing the decision path (Req 3.5, 4.5, 11.1, 11.2). Connection establishment is lazy, so the
 * beans construct and the application starts even when the Datastore is temporarily unreachable.
 */
package com.intentguard.persistence;
