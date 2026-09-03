package com.intentguard.api;

import com.intentguard.persistence.BehavioralProfileDocument;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Read-only projection of a user's Behavioral_Profile for the User Profiling Screen.
 *
 * <p>When {@link #present} is {@code false} no profile has been persisted for the user; the
 * remaining fields carry zero/null/empty sentinel values and MUST NOT be displayed (Req 6.4, 6.5).
 *
 * <p>When {@link #present} is {@code true} the fields are populated from the persisted document.
 * {@link #vocabulary} and {@link #sequenceStats} are fully ordered lists (descending count, ties
 * broken by ascending lexical key) — the frontend takes the top 10 for display (Req 6.2, 6.3).
 * No 10-cap is applied here so callers can choose a different limit without re-sorting.
 *
 * @param present       whether a Behavioral_Profile exists for this user (Req 6.4, 6.5)
 * @param state         {@code LEARNING} or {@code ACTIVE}; null when absent (Req 6.1)
 * @param eventCount    total events the profile was built from; 0 when absent (Req 6.1)
 * @param vocabulary    command-token occurrence entries, ordered desc count / asc key (Req 6.2)
 * @param sequenceStats n-gram sequence occurrence entries, ordered desc count / asc key (Req 6.3)
 */
public record BehavioralProfileView(
        boolean present,
        String state,
        long eventCount,
        List<CountEntry> vocabulary,
        List<CountEntry> sequenceStats) {

    /** Comparator: descending count, ties broken by ascending lexical key. */
    private static final Comparator<CountEntry> ENTRY_ORDER =
            Comparator.comparingInt(CountEntry::count).reversed()
                    .thenComparing(CountEntry::key);

    /**
     * Returns an "absent" view used when no profile has been persisted for the user (Req 6.4, 6.5).
     */
    public static BehavioralProfileView absent() {
        return new BehavioralProfileView(false, null, 0L, List.of(), List.of());
    }

    /**
     * Projects a persisted {@link BehavioralProfileDocument} into a view.
     *
     * <p>Vocabulary and sequence-stats maps are converted to fully ordered {@link CountEntry}
     * lists (descending count, ties ascending key). Null maps are treated as empty (Req 6.2, 6.3).
     *
     * @param d the persisted profile document; must not be null
     * @return a view with {@link #present} = {@code true}
     */
    public static BehavioralProfileView from(BehavioralProfileDocument d) {
        return new BehavioralProfileView(
                true,
                d.getState(),
                d.getEventCount(),
                toOrderedList(d.getVocabulary()),
                toOrderedList(d.getSequenceStats()));
    }

    /**
     * Converts a {@code Map<String, Integer>} to a {@link CountEntry} list ordered by descending
     * count then ascending lexical key. A null map returns an empty list.
     */
    private static List<CountEntry> toOrderedList(Map<String, Integer> map) {
        if (map == null || map.isEmpty()) {
            return List.of();
        }
        List<CountEntry> entries = new ArrayList<>(map.size());
        for (Map.Entry<String, Integer> e : map.entrySet()) {
            if (e.getKey() != null && e.getValue() != null) {
                entries.add(new CountEntry(e.getKey(), e.getValue()));
            }
        }
        entries.sort(ENTRY_ORDER);
        return List.copyOf(entries);
    }
}
