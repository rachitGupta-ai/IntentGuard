package com.intentguard.api;

/**
 * A key/count pair used within {@link BehavioralProfileView} to represent ordered vocabulary
 * and sequence-statistics entries.
 *
 * <p>Lists of {@code CountEntry} are ordered by descending {@link #count} with ties broken by
 * ascending lexical {@link #key} (Req 6.2, 6.3).
 *
 * @param key   the command token or n-gram key
 * @param count occurrence count within the behavioral profile
 */
public record CountEntry(String key, int count) {}
