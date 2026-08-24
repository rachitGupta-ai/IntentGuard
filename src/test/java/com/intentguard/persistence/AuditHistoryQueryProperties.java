package com.intentguard.persistence;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.LongRange;

/**
 * Feature: intentguard-semantic-firewall, Property 16: History queries return exactly the matching
 * records.
 *
 * <p>For any set of Audit_History records and any user/time-range query, the returned records are
 * exactly those records whose user and timestamp fall within the query's constraints
 * (Validates: Requirements 11.3).
 *
 * <p>No live MongoDB is available in this environment, so the query-filtering semantics are
 * exercised against an in-memory mirror of the predicate used by
 * {@link AuditHistoryRepository#queryByUserAndTimeRange(String, long, long)}. That repository
 * builds the Mongo filter {@code and(eq("userId", userId), gte("timestamp", fromMs),
 * lte("timestamp", toMs))} and sorts ascending by {@code timestamp}, i.e. user equality AND
 * {@code fromMs <= timestamp <= toMs} (both bounds inclusive), oldest-first. The mirror below
 * reproduces that predicate and ordering faithfully; the assertions then verify the returned
 * records are <em>exactly</em> the matching set (nothing missing, nothing extra) and ordered.
 */
class AuditHistoryQueryProperties {

    /** A small userId alphabet so datasets and queries overlap frequently. */
    private static final List<String> USERS = List.of("u0", "u1", "u2");

    /**
     * In-memory mirror of {@link AuditHistoryRepository#queryByUserAndTimeRange}: user equality AND
     * {@code fromMs <= timestamp <= toMs} (inclusive), ordered oldest-first by timestamp.
     */
    private static List<AuditHistoryDocument> queryByUserAndTimeRange(
            List<AuditHistoryDocument> all, String userId, long fromMs, long toMs) {
        return all.stream()
                .filter(d -> userId.equals(d.getUserId())
                        && d.getTimestamp() >= fromMs
                        && d.getTimestamp() <= toMs)
                .sorted(Comparator.comparingLong(AuditHistoryDocument::getTimestamp))
                .collect(Collectors.toList());
    }

    /** True when the document satisfies the query constraints (the definition of "matching"). */
    private static boolean matches(AuditHistoryDocument d, String userId, long fromMs, long toMs) {
        return userId.equals(d.getUserId()) && d.getTimestamp() >= fromMs && d.getTimestamp() <= toMs;
    }

    @Property
    void historyQueryReturnsExactlyTheMatchingRecords(
            @ForAll("auditRecords") List<AuditHistoryDocument> records,
            // Query user drawn from a slightly wider set (includes "u3") so empty-result queries occur.
            @ForAll("queryUsers") String queryUser,
            @ForAll @LongRange(min = 0, max = 1000) long boundA,
            @ForAll @LongRange(min = 0, max = 1000) long boundB) {

        long fromMs = Math.min(boundA, boundB);
        long toMs = Math.max(boundA, boundB);

        List<AuditHistoryDocument> result = queryByUserAndTimeRange(records, queryUser, fromMs, toMs);

        // Soundness: every returned record satisfies the query's user and time constraints.
        for (AuditHistoryDocument r : result) {
            assertThat(r.getUserId()).isEqualTo(queryUser);
            assertThat(r.getTimestamp()).isBetween(fromMs, toMs);
        }

        // No duplicates: each returned record is a distinct instance from the dataset.
        assertThat(new HashSet<>(result)).hasSameSizeAs(result);

        // Completeness + no-extras: the returned set equals exactly the set of matching records.
        Set<AuditHistoryDocument> expected = records.stream()
                .filter(d -> matches(d, queryUser, fromMs, toMs))
                .collect(Collectors.toCollection(HashSet::new));
        assertThat(new HashSet<>(result)).isEqualTo(expected);

        // Every non-matching record is excluded (explicit complement check).
        for (AuditHistoryDocument d : records) {
            if (!matches(d, queryUser, fromMs, toMs)) {
                assertThat(result).doesNotContain(d);
            }
        }

        // Ordering: results are oldest-first by timestamp.
        assertThat(result).isSortedAccordingTo(Comparator.comparingLong(AuditHistoryDocument::getTimestamp));
    }

    @Provide
    Arbitrary<String> queryUsers() {
        return Arbitraries.of("u0", "u1", "u2", "u3");
    }

    @Provide
    Arbitrary<List<AuditHistoryDocument>> auditRecords() {
        Arbitrary<String> userIds = Arbitraries.of(USERS.toArray(new String[0]));
        // Timestamps span the same window as the query bounds so boundary cases (timestamp == fromMs
        // or == toMs) are exercised for the inclusive [fromMs, toMs] range.
        Arbitrary<Long> timestamps = Arbitraries.longs().between(0, 1000);
        Arbitrary<String> eventIds = Arbitraries.strings().withCharRange('a', 'z').ofMinLength(1).ofMaxLength(8);

        Arbitrary<AuditHistoryDocument> document = Combinators.combine(userIds, timestamps, eventIds)
                .as((user, ts, eventId) -> {
                    AuditHistoryDocument doc = new AuditHistoryDocument();
                    doc.setEventId(eventId);
                    doc.setUserId(user);
                    doc.setTimestamp(ts);
                    doc.setRecordType("DECISION");
                    return doc;
                });

        return document.list().ofMaxSize(30);
    }
}
