package com.intentguard.api;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Response DTO for {@code GET /api/users}. Contains the distinct Known_User list assembled from
 * all persisted user-keyed stores (Audit_History, Behavioral_Profile, Intent_Session,
 * Assist_Audit QUERY records). Translation_Record contributes no identifiers (no userId stored).
 *
 * <p>Identifiers are deduplicated case-insensitively (Req 1.2) and returned in case-insensitive
 * ascending order (Req 1.3). Where two raw ids differ only by case, the lexicographically smallest
 * raw id is kept as the representative — guaranteeing the result is deterministic regardless of
 * iteration order (Req 1.1).
 *
 * @param users distinct Known_User identifiers, case-insensitively deduplicated and sorted
 */
public record KnownUsersView(List<String> users) {

    /**
     * Builds a {@code KnownUsersView} from the raw union of user identifiers across all stores.
     *
     * <p>Algorithm (Req 1.1, 1.2, 1.3):
     * <ol>
     *   <li>Null or blank ids are ignored.</li>
     *   <li>Remaining ids are grouped by their {@code toLowerCase(Locale.ROOT)} key.</li>
     *   <li>Within each group the lexicographically smallest raw id is kept (determinism).</li>
     *   <li>The resulting representatives are sorted in case-insensitive ascending order
     *       (i.e. by {@code id.toLowerCase(Locale.ROOT)}, then by raw id as a tiebreaker).</li>
     * </ol>
     *
     * @param rawIdsAcrossStores raw user identifiers from all contributing stores; may contain
     *                           nulls, blanks, or duplicates
     * @return a new {@code KnownUsersView} with the deduplicated, sorted list
     */
    public static KnownUsersView from(Collection<String> rawIdsAcrossStores) {
        // Group by lowercased key, keeping the lexicographically smallest raw id per group.
        Map<String, String> byLower = new HashMap<>();
        if (rawIdsAcrossStores != null) {
            for (String id : rawIdsAcrossStores) {
                if (id == null || id.isBlank()) {
                    continue;
                }
                String key = id.toLowerCase(Locale.ROOT);
                byLower.merge(key, id, (existing, candidate) ->
                        candidate.compareTo(existing) < 0 ? candidate : existing);
            }
        }

        // Sort representatives case-insensitively ascending (ties broken by raw value).
        List<String> sorted = new ArrayList<>(byLower.values());
        sorted.sort(Comparator
                .comparing((String s) -> s.toLowerCase(Locale.ROOT))
                .thenComparing(Comparator.naturalOrder()));

        return new KnownUsersView(sorted);
    }
}
