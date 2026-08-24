package com.intentguard.domain;

/**
 * The provenance of the intent a {@code CommandEvent} was scored against.
 *
 * <ul>
 *   <li>{@code NONE} - no Declared_Intent or Inferred_Intent was available; Semantic_Inconsistency
 *       is excluded (Req 4.4, 5.6).</li>
 *   <li>{@code DECLARED} - scored against a human-declared Intent_Session (Req 4.1).</li>
 *   <li>{@code INFERRED} - scored against an Inferred_Intent (stretch, Req 14); weighted lower
 *       than a Declared_Intent.</li>
 * </ul>
 */
public enum IntentSource {
    NONE,
    DECLARED,
    INFERRED
}
