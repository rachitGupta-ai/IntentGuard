package com.intentguard.api;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

import com.intentguard.persistence.BehavioralProfileDocument;
import com.intentguard.persistence.IntentSessionDocument;
import com.intentguard.translation.LanguageTag;
import com.intentguard.translation.SupportedLanguages;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

/**
 * Property-based tests for {@link KnownUsersView}, {@link MultilingualEntryView}, and
 * {@link BehavioralProfileView} — covering Properties 1, 2, 5, and 10 from the User Profiling
 * Screen design.
 *
 * <p>All tests use jqwik at {@code @Property(tries = 100)}, are package-private, use AssertJ, and
 * carry the {@code Feature: user-profiling-screen, Property N: ...} tag.
 */
class UserProfilingViewPropertiesTest {

    // ---- P1: KnownUsersView — case-insensitive deduplication -----------------------------------

    /**
     * Feature: user-profiling-screen, Property 1: Known_User set is the case-insensitive union
     * across stores, deduped.
     *
     * <p>For any collection of raw ids (including nulls, blanks, and case variants of the same id),
     * the result of {@link KnownUsersView#from(Collection)} contains no two entries that are equal
     * under case-insensitive comparison (Validates: Requirements 1.1, 1.2).
     */
    @Property(tries = 100)
    void knownUserSetContainsNoDuplicatesCaseInsensitively(
            @ForAll("rawIdCollections") List<String> rawIds) {

        // Feature: user-profiling-screen, Property 1: Known_User set is the case-insensitive union
        // across stores, deduped
        List<String> users = KnownUsersView.from(rawIds).users();

        // No two entries in the result share the same lowercase key.
        List<String> lowercased = users.stream()
                .map(id -> id.toLowerCase(Locale.ROOT))
                .toList();
        assertThat(lowercased).doesNotHaveDuplicates();

        // Every non-blank raw id that was present must be represented (case-insensitively).
        for (String raw : rawIds) {
            if (raw != null && !raw.isBlank()) {
                String lower = raw.toLowerCase(Locale.ROOT);
                assertThat(lowercased)
                        .as("raw id '%s' (normalised '%s') must be represented in the result", raw, lower)
                        .contains(lower);
            }
        }

        // Null and blank ids must never appear in the result.
        assertThat(users).allSatisfy(u -> {
            assertThat(u).isNotNull();
            assertThat(u.isBlank()).isFalse();
        });
    }

    // ---- P2: KnownUsersView — case-insensitive ascending order ---------------------------------

    /**
     * Feature: user-profiling-screen, Property 2: Known_User ordering is case-insensitive ascending.
     *
     * <p>For any collection of raw ids, the list returned by {@link KnownUsersView#from(Collection)}
     * is sorted in case-insensitive ascending order (Validates: Requirements 1.3).
     */
    @Property(tries = 100)
    void knownUserListIsSortedCaseInsensitiveAscending(
            @ForAll("rawIdCollections") List<String> rawIds) {

        // Feature: user-profiling-screen, Property 2: Known_User ordering is case-insensitive ascending
        List<String> users = KnownUsersView.from(rawIds).users();

        for (int i = 0; i < users.size() - 1; i++) {
            String current = users.get(i);
            String next = users.get(i + 1);

            String currentLower = current.toLowerCase(Locale.ROOT);
            String nextLower = next.toLowerCase(Locale.ROOT);

            // Each element must compare ≤ the next, case-insensitively.
            int cmp = currentLower.compareTo(nextLower);
            if (cmp == 0) {
                // Ties broken by raw value ascending.
                assertThat(current.compareTo(next))
                        .as("tie on '%s' vs '%s' must be broken by raw ascending order", current, next)
                        .isLessThanOrEqualTo(0);
            } else {
                assertThat(cmp)
                        .as("'%s' (lower: '%s') should precede '%s' (lower: '%s')",
                                current, currentLower, next, nextLower)
                        .isLessThan(0);
            }
        }
    }

    // ---- P5: MultilingualEntryView — technical-token preservation ------------------------------

    /**
     * Feature: user-profiling-screen, Property 5: Multilingual projection preserves technical
     * tokens byte-for-byte.
     *
     * <p>For any {@link IntentSessionDocument} that passes the attribution, non-blank source-text,
     * and non-English supported-language guards, the {@code sourceText} in the resulting
     * {@link MultilingualEntryView} equals the document's {@code originalDeclaredIntent} exactly
     * — no trimming, no rewriting, no token substitution (Validates: Requirements 3.3).
     */
    @Property(tries = 100)
    void multilingualProjectionPreservesTechnicalTokensByteForByte(
            @ForAll("attributableNonEnglishSessions") IntentSessionDocument session) {

        // Feature: user-profiling-screen, Property 5: Multilingual projection preserves technical
        // tokens byte-for-byte
        SupportedLanguages langs = SupportedLanguages.defaults();
        Optional<MultilingualEntryView> result = MultilingualEntryView.from(session, langs);

        // The session generator only produces sessions that satisfy the projection guards, so the
        // result must always be present.
        assertThat(result)
                .as("session with attributable non-English supported-language tag must project to a view")
                .isPresent();

        MultilingualEntryView view = result.get();

        // sourceText is a verbatim copy — not trimmed, not rewritten (Req 3.3).
        assertThat(view.sourceText())
                .as("sourceText must equal originalDeclaredIntent byte-for-byte")
                .isEqualTo(session.getOriginalDeclaredIntent());

        // Every technical token embedded in the source text must survive byte-for-byte.
        for (String token : technicalTokensIn(session.getOriginalDeclaredIntent())) {
            assertThat(view.sourceText())
                    .as("technical token '%s' must survive byte-for-byte in sourceText", token)
                    .contains(token);
        }

        // sessionId and timestamp are carried through unchanged.
        assertThat(view.sessionId()).isEqualTo(session.getSessionId());
        assertThat(view.timestamp()).isEqualTo(session.getStartedAt());
    }

    // ---- P10: BehavioralProfileView — ordering of vocabulary/sequenceStats --------------------

    /**
     * Feature: user-profiling-screen, Property 10: Behavioral summary lists are top-k ordered by
     * descending count then ascending key.
     *
     * <p>For any {@link BehavioralProfileDocument} with a non-null vocabulary and/or sequenceStats
     * map (including maps with fewer than 10 entries, maps with duplicate counts, and empty maps),
     * both lists returned by {@link BehavioralProfileView#from(BehavioralProfileDocument)} are
     * sorted by descending count then ascending key (Validates: Requirements 6.2, 6.3).
     */
    @Property(tries = 100)
    void behavioralProfileListsAreSortedByDescendingCountThenAscendingKey(
            @ForAll("behavioralProfileDocuments") BehavioralProfileDocument doc) {

        // Feature: user-profiling-screen, Property 10: Behavioral summary lists are top-k ordered
        // by descending count then ascending key
        BehavioralProfileView view = BehavioralProfileView.from(doc);

        assertThat(view.present()).isTrue();
        assertSortedByDescCountThenAscKey(view.vocabulary(), "vocabulary");
        assertSortedByDescCountThenAscKey(view.sequenceStats(), "sequenceStats");
    }

    // ---- Assertions ------------------------------------------------------------------------

    /**
     * Asserts that {@code entries} are sorted by descending count then ascending key.
     */
    private static void assertSortedByDescCountThenAscKey(List<CountEntry> entries, String label) {
        for (int i = 0; i < entries.size() - 1; i++) {
            CountEntry a = entries.get(i);
            CountEntry b = entries.get(i + 1);

            if (a.count() == b.count()) {
                assertThat(a.key().compareTo(b.key()))
                        .as("%s[%d] key '%s' must precede %s[%d] key '%s' (same count %d, ascending key)",
                                label, i, a.key(), label, i + 1, b.key(), a.count())
                        .isLessThanOrEqualTo(0);
            } else {
                assertThat(a.count())
                        .as("%s[%d] count %d must be ≥ %s[%d] count %d (descending count order)",
                                label, i, a.count(), label, i + 1, b.count())
                        .isGreaterThanOrEqualTo(b.count());
            }
        }
    }

    /**
     * Extracts well-known technical tokens present in {@code text}. Returns the subset that are
     * actually found in the text so assertions are only made for tokens that are present.
     */
    private static List<String> technicalTokensIn(String text) {
        List<String> found = new ArrayList<>();
        for (String candidate : TECHNICAL_TOKEN_POOL) {
            if (text.contains(candidate)) {
                found.add(candidate);
            }
        }
        return found;
    }

    private static final List<String> TECHNICAL_TOKEN_POOL = List.of(
            "/var/log", "/etc/passwd", "/tmp/cache", "/home/user",
            "192.168.1.1", "10.0.0.1", "127.0.0.1",
            "nginx -t", "systemctl restart", "kubectl get pods",
            "rm -rf", "git push", "docker run");

    // ---- Generators -----------------------------------------------------------------------

    /**
     * Generates lists of raw user ids that include: valid lowercase ids, uppercase/mixed-case
     * variants of the same id, blank strings, and nulls — to exercise the full deduplication and
     * filtering logic.
     */
    @Provide
    Arbitrary<List<String>> rawIdCollections() {
        Arbitrary<String> validId = Arbitraries.strings()
                .withCharRange('a', 'z')
                .ofMinLength(1).ofMaxLength(12);

        // A mix of valid ids, cased variants, blanks, and nulls.
        Arbitrary<String> rawEntry = Arbitraries.oneOf(
                validId,
                validId.map(String::toUpperCase),
                validId.map(id -> id.substring(0, 1).toUpperCase(Locale.ROOT) + id.substring(1)),
                Arbitraries.just(""),
                Arbitraries.just("   "),
                Arbitraries.just(null)
        );

        return rawEntry.list().ofMinSize(0).ofMaxSize(20);
    }

    /**
     * Generates {@link IntentSessionDocument} instances that satisfy all three inclusion guards
     * required by {@link MultilingualEntryView#from}: non-blank userId, non-blank
     * originalDeclaredIntent (containing at least one technical token), and a non-English
     * {@link SupportedLanguages} tag.
     */
    @Provide
    Arbitrary<IntentSessionDocument> attributableNonEnglishSessions() {
        // Non-English supported language tags (all tags from SupportedLanguages.defaults() minus "en").
        Arbitrary<String> nonEnglishTag = Arbitraries.of("hi", "bn", "te", "mr", "ta", "gu", "kn", "ml", "pa", "or");

        // Source text with an embedded technical token so the token-preservation assertion has teeth.
        Arbitrary<String> token = Arbitraries.of(TECHNICAL_TOKEN_POOL.toArray(new String[0]));
        Arbitrary<String> prose = Arbitraries.strings().alpha().ofMinLength(3).ofMaxLength(20);
        Arbitrary<String> sourceText = Combinators.combine(prose, token, prose)
                .as((pre, tok, post) -> pre + " " + tok + " " + post);

        Arbitrary<String> sessionId = Arbitraries.strings().alpha().ofMinLength(4).ofMaxLength(16);
        Arbitrary<String> userId = Arbitraries.strings().alpha().ofMinLength(2).ofMaxLength(12);
        Arbitrary<Long> timestamp = Arbitraries.longs().between(1_000_000_000_000L, 2_000_000_000_000L);

        // englishText: sometimes present, sometimes absent — to exercise both translationAvailable paths.
        Arbitrary<String> englishText = Arbitraries.oneOf(
                Arbitraries.strings().alpha().ofMinLength(3).ofMaxLength(40),
                Arbitraries.just(null),
                Arbitraries.just("")
        );

        return Combinators.combine(sessionId, userId, sourceText, englishText, nonEnglishTag, timestamp)
                .as((sid, uid, src, eng, tag, ts) -> {
                    IntentSessionDocument doc = new IntentSessionDocument();
                    doc.setSessionId(sid);
                    doc.setUserId(uid);
                    doc.setOriginalDeclaredIntent(src);
                    doc.setDeclaredIntent(eng);
                    doc.setDeclaredIntentLanguageTag(tag);
                    doc.setStartedAt(ts);
                    return doc;
                });
    }

    /**
     * Generates {@link BehavioralProfileDocument} instances with vocabulary and sequenceStats maps
     * of varying sizes (0 to 20 entries, including fewer than 10) with potentially duplicate counts
     * and varying key lengths — exercising the full sort order including tie-breaking by key.
     */
    @Provide
    Arbitrary<BehavioralProfileDocument> behavioralProfileDocuments() {
        Arbitrary<Map<String, Integer>> statsMap = mapOfCountEntries();

        return Combinators.combine(statsMap, statsMap)
                .as((vocab, seqStats) -> {
                    BehavioralProfileDocument doc = new BehavioralProfileDocument();
                    doc.setUserId("test-user");
                    doc.setState("ACTIVE");
                    doc.setEventCount(200);
                    doc.setVocabulary(vocab);
                    doc.setSequenceStats(seqStats);
                    return doc;
                });
    }

    /**
     * Generates a {@code Map<String, Integer>} with 0 to 20 entries, counts in [1, 100], and
     * keys drawn from a small alphabet (to produce duplicates and exercise the key tie-breaking).
     */
    private static Arbitrary<Map<String, Integer>> mapOfCountEntries() {
        Arbitrary<String> key = Arbitraries.strings()
                .withChars("abcdefghij")
                .ofMinLength(1).ofMaxLength(6);
        Arbitrary<Integer> count = Arbitraries.integers().between(1, 100);

        return Combinators.combine(key, count)
                .as(Map::entry)
                .list().ofMinSize(0).ofMaxSize(20)
                .map(entries -> {
                    Map<String, Integer> map = new HashMap<>();
                    for (Map.Entry<String, Integer> e : entries) {
                        map.put(e.getKey(), e.getValue());
                    }
                    return map;
                });
    }
}
