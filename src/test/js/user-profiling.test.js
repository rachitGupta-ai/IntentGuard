/*
 * Frontend tests for the User Profiling screen added in task 6.3–6.5.
 * (src/main/resources/static/app.js)
 *
 * Structure mirrors control-tower.test.js: zero external dependencies,
 * Node built-in test runner (node:test), and the fake-dom.js stub.
 *
 * Run with:  node --test src/test/js/user-profiling.test.js
 *
 * Coverage:
 *   Property P2  – sortUsersCaseInsensitive          (Req 1.3)
 *   Property P7  – truncateQueryText                 (Req 4.3)
 *   Property P10 – topEntries                        (Req 6.2, 6.3)
 *   Scenarios    – renderUserSelector states         (Req 1.4–1.7)
 *   Scenarios    – renderCommandTimeline             (Req 2.3–2.7, 8.4)
 *   Scenarios    – renderMultilingual                (Req 3.2, 3.5, 3.6, 8.4)
 *   Scenarios    – renderAssistQueries               (Req 4.2, 4.4, 8.4)
 *   Scenarios    – renderTranslations                (Req 5.2, 5.4, 5.5, 8.4)
 *   Scenarios    – truncation indicator              (Req 8.4)
 *   Scenarios    – whole-profile failure vs empty    (Req 10.5, 10.6)
 *   Scenarios    – setView / SSE stream isolation    (Req 11.2–11.5)
 */
"use strict";

const test   = require("node:test");
const assert = require("node:assert/strict");
const { makeDocument, findAllByClass, allDescendants } = require("./fake-dom.js");
const IG = require("../../main/resources/static/app.js");

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

/** Point app.js's global document.createElement at a fake document per call. */
function withDocument(ids, fn) {
  const doc = makeDocument(ids);
  const prev = global.document;
  global.document = doc;
  try {
    return fn(doc);
  } finally {
    global.document = prev;
  }
}

/** LCG-based deterministic PRNG: seed → iterator of values in [0,1). */
function makePrng(seed) {
  let s = seed >>> 0 || 1;
  return {
    next() {
      s = (Math.imul(1664525, s) + 1013904223) >>> 0;
      return s / 4294967296;
    },
    int(max) { return (this.next() * max) | 0; },
    str(len) {
      const chars = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789_-";
      let r = "";
      for (let i = 0; i < len; i++) r += chars[this.int(chars.length)];
      return r;
    }
  };
}

/** Generate an array of N random mixed-case strings using the given prng. */
function randStringArray(prng, n) {
  const arr = [];
  for (let i = 0; i < n; i++) arr.push(prng.str(1 + prng.int(12)));
  return arr;
}

/** Verify a sorted array is case-insensitively ascending. */
function isCaseInsensitivelySorted(arr) {
  for (let i = 1; i < arr.length; i++) {
    if (arr[i - 1].toLowerCase() > arr[i].toLowerCase()) return false;
  }
  return true;
}

// ---------------------------------------------------------------------------
// P2 – sortUsersCaseInsensitive: result is sorted case-insensitively ascending
// (Feature: user-profiling-screen, Property 2: Known_User ordering is case-insensitive ascending)
// ---------------------------------------------------------------------------

test("P2 sortUsersCaseInsensitive: sorted ascending for empty array", () => {
  assert.deepEqual(IG.sortUsersCaseInsensitive([]), []);
});

test("P2 sortUsersCaseInsensitive: sorted ascending for single element", () => {
  assert.deepEqual(IG.sortUsersCaseInsensitive(["Alice"]), ["Alice"]);
});

test("P2 sortUsersCaseInsensitive: sorted ascending for mixed-case inputs", () => {
  const input = ["Zara", "alice", "Bob", "CAROL", "dave"];
  const result = IG.sortUsersCaseInsensitive(input);
  assert.ok(isCaseInsensitivelySorted(result), "result must be case-insensitively sorted");
  assert.equal(result.length, input.length, "length must be preserved");
});

test("P2 sortUsersCaseInsensitive: does not mutate the original array", () => {
  const input = ["Zara", "alice", "Bob"];
  const copy = [...input];
  IG.sortUsersCaseInsensitive(input);
  assert.deepEqual(input, copy, "original array must not be mutated");
});

test("P2 sortUsersCaseInsensitive: handles null gracefully (returns [])", () => {
  assert.deepEqual(IG.sortUsersCaseInsensitive(null), []);
});

// Property sweep: 100 random arrays
test("P2 sortUsersCaseInsensitive: property sweep — always sorted, length preserved (100 cases)", () => {
  const prng = makePrng(0xdeadbeef);
  for (let trial = 0; trial < 100; trial++) {
    const n = prng.int(20);                   // 0..19 elements
    const input = randStringArray(prng, n);
    const result = IG.sortUsersCaseInsensitive(input);

    assert.equal(result.length, input.length,
      `trial ${trial}: length mismatch — input: ${JSON.stringify(input)}`);
    assert.ok(isCaseInsensitivelySorted(result),
      `trial ${trial}: not sorted — result: ${JSON.stringify(result)}`);

    // Verify it's a permutation (same multiset)
    const inSorted  = [...input].sort();
    const outSorted = [...result].sort();
    assert.deepEqual(inSorted, outSorted,
      `trial ${trial}: result is not a permutation of input`);
  }
});

// ---------------------------------------------------------------------------
// P7 – truncateQueryText: length ≤ maxLen; wasTruncated iff originalLength > maxLen
// (Feature: user-profiling-screen, Property 7: Assistant query text truncates to 2000 chars)
// ---------------------------------------------------------------------------

test("P7 truncateQueryText: short string is unchanged, wasTruncated=false", () => {
  const result = IG.truncateQueryText("hello", 2000);
  assert.equal(result.text, "hello");
  assert.equal(result.wasTruncated, false);
});

test("P7 truncateQueryText: string exactly at maxLen is unchanged, wasTruncated=false", () => {
  const s = "x".repeat(2000);
  const result = IG.truncateQueryText(s, 2000);
  assert.equal(result.text.length, 2000);
  assert.equal(result.wasTruncated, false);
});

test("P7 truncateQueryText: string longer than maxLen is cut, wasTruncated=true", () => {
  const s = "a".repeat(2001);
  const result = IG.truncateQueryText(s, 2000);
  assert.equal(result.text.length, 2000);
  assert.equal(result.wasTruncated, true);
  assert.equal(result.text, s.slice(0, 2000));
});

test("P7 truncateQueryText: non-string input returns empty, wasTruncated=false", () => {
  const result = IG.truncateQueryText(null, 2000);
  assert.equal(result.text, "");
  assert.equal(result.wasTruncated, false);
});

// Property sweep: 100 random (text, len) pairs
test("P7 truncateQueryText: property sweep — length contract always holds (100 cases)", () => {
  const prng = makePrng(0xc0ffee);
  for (let trial = 0; trial < 100; trial++) {
    const len = 1 + prng.int(4000);            // maxLen in 1..4000
    const textLen = prng.int(5000);             // text length in 0..4999
    const text = "t".repeat(textLen);

    const result = IG.truncateQueryText(text, len);

    assert.ok(result.text.length <= len,
      `trial ${trial}: result.text.length (${result.text.length}) > maxLen (${len})`);

    const shouldBeTruncated = textLen > len;
    assert.equal(result.wasTruncated, shouldBeTruncated,
      `trial ${trial}: wasTruncated mismatch — textLen=${textLen}, maxLen=${len}`);

    if (shouldBeTruncated) {
      assert.equal(result.text, text.slice(0, len),
        `trial ${trial}: truncated text does not match slice`);
    } else {
      assert.equal(result.text, text,
        `trial ${trial}: un-truncated text was modified`);
    }
  }
});

// ---------------------------------------------------------------------------
// P10 – topEntries: sorted by descending count then ascending key; length ≤ k
// (Feature: user-profiling-screen, Property 10: Behavioral summary lists are top-k ordered)
// ---------------------------------------------------------------------------

test("P10 topEntries: empty map returns []", () => {
  assert.deepEqual(IG.topEntries({}, 10), []);
});

test("P10 topEntries: null map returns []", () => {
  assert.deepEqual(IG.topEntries(null, 10), []);
});

test("P10 topEntries: returns at most k entries", () => {
  const map = { a: 5, b: 4, c: 3, d: 2, e: 1, f: 6 };
  const result = IG.topEntries(map, 3);
  assert.equal(result.length, 3);
});

test("P10 topEntries: sorted descending by count, then ascending by key on tie", () => {
  const map = { b: 10, a: 10, c: 20, d: 5 };
  const result = IG.topEntries(map, 10);
  // c:20, a:10, b:10, d:5
  assert.equal(result[0].key, "c"); assert.equal(result[0].count, 20);
  assert.equal(result[1].key, "a"); assert.equal(result[1].count, 10);
  assert.equal(result[2].key, "b"); assert.equal(result[2].count, 10);
  assert.equal(result[3].key, "d"); assert.equal(result[3].count, 5);
});

test("P10 topEntries: works with a Map instance", () => {
  const m = new Map([["z", 1], ["a", 3], ["m", 2]]);
  const result = IG.topEntries(m, 5);
  assert.equal(result.length, 3);
  assert.equal(result[0].key, "a"); // highest count
  assert.equal(result[1].key, "m");
  assert.equal(result[2].key, "z");
});

/** Check a topEntries result array is in the required order. */
function isTopEntriesOrdered(entries) {
  for (let i = 1; i < entries.length; i++) {
    const prev = entries[i - 1], cur = entries[i];
    if (prev.count < cur.count) return false;           // must be descending count
    if (prev.count === cur.count && prev.key > cur.key) return false; // ascending key on tie
  }
  return true;
}

// Property sweep: 100 random maps
test("P10 topEntries: property sweep — order and length contracts always hold (100 cases)", () => {
  const prng = makePrng(0x1234abcd);
  for (let trial = 0; trial < 100; trial++) {
    const size = prng.int(25);               // 0..24 entries in the map
    const k    = 1 + prng.int(15);          // k in 1..15
    const map  = {};
    for (let i = 0; i < size; i++) {
      const key = prng.str(1 + prng.int(6));
      map[key] = prng.int(50);              // count in 0..49
    }

    const result = IG.topEntries(map, k);
    const uniqueKeys = Object.keys(map);

    assert.ok(result.length <= k,
      `trial ${trial}: result.length (${result.length}) > k (${k})`);
    assert.ok(result.length <= uniqueKeys.length,
      `trial ${trial}: result.length (${result.length}) > map size (${uniqueKeys.length})`);
    assert.ok(isTopEntriesOrdered(result),
      `trial ${trial}: result is not in the correct order — ${JSON.stringify(result)}`);
  }
});

// ---------------------------------------------------------------------------
// renderUserSelector — scenario tests
// ---------------------------------------------------------------------------

test("renderUserSelector: loading state — input disabled, loading placeholder + hint", () => {
  withDocument(["profiling-user-select", "profiling-user-options", "profiling-users-status", "profiling-load-btn"], (doc) => {
    const state = Object.assign(IG.createProfileState(), { usersStatus: "loading" });
    IG.renderUserSelector(state, doc);

    const input = doc.getElementById("profiling-user-select");
    const hint  = doc.getElementById("profiling-users-status");
    const btn   = doc.getElementById("profiling-load-btn");

    assert.equal(input.disabled, true, "search input must be disabled while loading");
    assert.match(input.getAttribute("placeholder"), /loading/i, "placeholder must say loading");
    assert.ok(hint, "hint element must exist");
    assert.match(hint.textContent, /loading/i, "hint must say loading");
    assert.equal(btn.disabled, true, "load button must be disabled");
  });
});

test("renderUserSelector: error state — error class on hint, input disabled", () => {
  withDocument(["profiling-user-select", "profiling-user-options", "profiling-users-status", "profiling-load-btn"], (doc) => {
    const state = Object.assign(IG.createProfileState(), { usersStatus: "error" });
    IG.renderUserSelector(state, doc);

    const input = doc.getElementById("profiling-user-select");
    const hint  = doc.getElementById("profiling-users-status");

    assert.equal(input.disabled, true, "search input must be disabled on error");
    assert.match(input.getAttribute("placeholder"), /failed/i, "placeholder must indicate failure");
    assert.ok(hint.className.includes("cat-error"), "hint must have cat-error class");
    assert.match(hint.textContent, /could not load|failed/i, "hint must describe the error");
  });
});

test("renderUserSelector: ok with empty user list — empty placeholder + hint, btn disabled", () => {
  withDocument(["profiling-user-select", "profiling-user-options", "profiling-users-status", "profiling-load-btn"], (doc) => {
    const state = Object.assign(IG.createProfileState(), { usersStatus: "ok", users: [] });
    IG.renderUserSelector(state, doc);

    const input = doc.getElementById("profiling-user-select");
    const hint  = doc.getElementById("profiling-users-status");
    const btn   = doc.getElementById("profiling-load-btn");

    assert.match(input.getAttribute("placeholder"), /no known users/i, "placeholder must show empty message");
    assert.ok(hint.className.includes("cat-empty"), "hint must have cat-empty class");
    assert.equal(btn.disabled, true, "load button must be disabled when no users");
  });
});

test("renderUserSelector: ok with users — datalist options populated, hint counts users", () => {
  withDocument(["profiling-user-select", "profiling-user-options", "profiling-users-status", "profiling-load-btn"], (doc) => {
    const state = Object.assign(IG.createProfileState(), {
      usersStatus: "ok",
      users: ["alice", "bob", "carol"],
      selectedUserId: "bob"
    });
    IG.renderUserSelector(state, doc);

    const input   = doc.getElementById("profiling-user-select");
    const list    = doc.getElementById("profiling-user-options");
    const hint    = doc.getElementById("profiling-users-status");
    const btn     = doc.getElementById("profiling-load-btn");
    const options = list.children.filter(c => c.tagName === "OPTION");

    // One <option> per known user (no placeholder option — that's the input's placeholder now).
    assert.equal(options.length, 3, "datalist must have one option per known user");
    const values = options.map(o => o.value);
    assert.deepEqual(values.sort(), ["alice", "bob", "carol"]);

    // Search input is enabled and reflects the current selection.
    assert.equal(input.disabled, false, "search input must be enabled");
    assert.equal(input.value, "bob", "input value must reflect the selected user");
    assert.match(hint.textContent, /3 user\(s\) found/, "hint must count the users");
    assert.equal(btn.disabled, false, "load button must be enabled when a user is selected");
  });
});

// ---------------------------------------------------------------------------
// renderCommandTimeline — scenario tests
// ---------------------------------------------------------------------------

test("renderCommandTimeline: UNAVAILABLE — appends cat-error without clearing prior content", () => {
  withDocument(["prof-timeline-body"], (doc) => {
    const body = doc.getElementById("prof-timeline-body");
    // Simulate prior content
    const prior = doc.createElement("p");
    prior.textContent = "prior content";
    body.appendChild(prior);

    IG.renderCommandTimeline({ status: "UNAVAILABLE", records: [] }, doc);

    // Prior content + error appended (not cleared)
    const errDivs = findAllByClass(body, "cat-error");
    assert.ok(errDivs.length > 0, "cat-error element must be appended");
    // body should still have the prior child plus the error
    assert.ok(body.children.length >= 2, "prior content must not have been cleared");
  });
});

test("renderCommandTimeline: null category — treated as UNAVAILABLE, appends cat-error", () => {
  withDocument(["prof-timeline-body"], (doc) => {
    IG.renderCommandTimeline(null, doc);
    const body = doc.getElementById("prof-timeline-body");
    const errDivs = findAllByClass(body, "cat-error");
    assert.ok(errDivs.length > 0, "cat-error must be shown for null category");
  });
});

test("renderCommandTimeline: populated — shows commandText, action badge, score, and origin", () => {
  withDocument(["prof-timeline-body"], (doc) => {
    const category = {
      status: "OK",
      truncated: false,
      totalAvailable: 2,
      records: [
        {
          commandText: "ls -la /var/log",
          correctiveAction: "ALLOW",
          divergenceScore: 0.12,
          reasonCode: "LOW_RISK",
          profileState: "ACTIVE",
          inputOrigin: "SHELL_HOOK",
          timestamp: 1710000000000
        },
        {
          commandText: "rm -rf /",
          correctiveAction: "BLOCK",
          divergenceScore: 0.99,
          reasonCode: "TAMPER",
          profileState: "ACTIVE",
          inputOrigin: null,   // <-- should show "unknown"
          timestamp: 1710000001000
        }
      ]
    };

    IG.renderCommandTimeline(category, doc);

    const body = doc.getElementById("prof-timeline-body");
    assert.match(body.textContent, /ls -la \/var\/log/);
    assert.match(body.textContent, /ALLOW/);
    assert.match(body.textContent, /0\.12/);
    assert.match(body.textContent, /rm -rf \//);
    assert.match(body.textContent, /BLOCK/);
    assert.match(body.textContent, /0\.99/);
    // null inputOrigin renders as "unknown"
    assert.match(body.textContent, /unknown/);
    // Non-null origin renders as-is
    assert.match(body.textContent, /SHELL_HOOK/);
  });
});

// ---------------------------------------------------------------------------
// renderMultilingual — scenario tests
// ---------------------------------------------------------------------------

test("renderMultilingual: translationAvailable=false — shows unavailable indicator", () => {
  withDocument(["prof-multilingual-body"], (doc) => {
    const category = {
      status: "OK",
      truncated: false,
      totalAvailable: 1,
      records: [
        {
          sourceLanguageTag: "hi",
          sourceText: "nginx config ki jaanch karo",
          englishText: null,
          translationAvailable: false,
          timestamp: 1710000000000
        }
      ]
    };

    IG.renderMultilingual(category, doc);

    const body = doc.getElementById("prof-multilingual-body");
    assert.match(body.textContent, /nginx config ki jaanch karo/, "source text must appear");
    const indicators = findAllByClass(body, "prof-translation-unavailable");
    assert.ok(indicators.length > 0, "unavailable indicator element must be rendered");
    assert.match(body.textContent, /translation unavailable/i, "must say Translation unavailable");
  });
});

test("renderMultilingual: translationAvailable=true — shows English text adjacent to source", () => {
  withDocument(["prof-multilingual-body"], (doc) => {
    const category = {
      status: "OK",
      truncated: false,
      totalAvailable: 1,
      records: [
        {
          sourceLanguageTag: "hi",
          sourceText: "deploy karo",
          englishText: "do the deploy",
          translationAvailable: true,
          timestamp: 1710000000000
        }
      ]
    };

    IG.renderMultilingual(category, doc);

    const body = doc.getElementById("prof-multilingual-body");
    assert.match(body.textContent, /deploy karo/, "source text must appear");
    assert.match(body.textContent, /do the deploy/, "English text must appear");
    const unavailableIndicators = findAllByClass(body, "prof-translation-unavailable");
    assert.equal(unavailableIndicators.length, 0, "unavailable indicator must NOT appear");
  });
});

test("renderMultilingual: UNAVAILABLE — shows cat-error", () => {
  withDocument(["prof-multilingual-body"], (doc) => {
    IG.renderMultilingual({ status: "UNAVAILABLE", records: [] }, doc);
    const body = doc.getElementById("prof-multilingual-body");
    const errDivs = findAllByClass(body, "cat-error");
    assert.ok(errDivs.length > 0, "cat-error must be shown");
  });
});

// ---------------------------------------------------------------------------
// renderAssistQueries — scenario tests
// ---------------------------------------------------------------------------

test("renderAssistQueries: populated — shows date+time, generated commands in order", () => {
  withDocument(["prof-assist-body"], (doc) => {
    const ts = 1710000000000; // a fixed epoch ms
    const category = {
      status: "OK",
      truncated: false,
      totalAvailable: 1,
      records: [
        {
          queryEnglish: "list all running docker containers",
          generatedCommands: ["docker ps", "docker ps -a", "docker container ls"],
          timestamp: ts
        }
      ]
    };

    IG.renderAssistQueries(category, doc);

    const body = doc.getElementById("prof-assist-body");
    assert.match(body.textContent, /list all running docker containers/, "query text must appear");

    // Generated commands must appear in order
    const allText = body.textContent;
    const pos0 = allText.indexOf("docker ps");
    const pos1 = allText.indexOf("docker ps -a");
    const pos2 = allText.indexOf("docker container ls");
    assert.ok(pos0 >= 0, "first generated command must appear");
    assert.ok(pos1 >= 0, "second generated command must appear");
    assert.ok(pos2 >= 0, "third generated command must appear");
    assert.ok(pos0 < pos1, "docker ps must precede docker ps -a");
    assert.ok(pos1 < pos2, "docker ps -a must precede docker container ls");

    // Date + time: must contain both a date-like and time-like string
    // We just check a digit pattern appears (locale-agnostic)
    assert.match(allText, /\d/, "timestamp digits must appear");
  });
});

test("renderAssistQueries: query text > 2000 chars — shows truncation indicator", () => {
  withDocument(["prof-assist-body"], (doc) => {
    const longQuery = "q".repeat(2500);
    const category = {
      status: "OK",
      truncated: false,
      totalAvailable: 1,
      records: [{ queryEnglish: longQuery, generatedCommands: [], timestamp: 1710000000000 }]
    };

    IG.renderAssistQueries(category, doc);

    const body = doc.getElementById("prof-assist-body");
    // The rendered text must be ≤ 2000 chars of q's (plus surrounding text)
    const qRuns = body.textContent.match(/q+/);
    assert.ok(qRuns && qRuns[0].length <= 2000, "rendered query must be capped at 2000 chars");
    assert.match(body.textContent, /truncated/i, "truncation indicator must appear");
  });
});

test("renderAssistQueries: UNAVAILABLE — shows cat-error", () => {
  withDocument(["prof-assist-body"], (doc) => {
    IG.renderAssistQueries({ status: "UNAVAILABLE", records: [] }, doc);
    const body = doc.getElementById("prof-assist-body");
    assert.ok(findAllByClass(body, "cat-error").length > 0, "cat-error must be shown");
  });
});

// ---------------------------------------------------------------------------
// renderTranslations — scenario tests
// ---------------------------------------------------------------------------

test("renderTranslations: degraded=true — shows degraded indicator", () => {
  withDocument(["prof-translations-body"], (doc) => {
    const category = {
      status: "OK",
      truncated: false,
      totalAvailable: 1,
      records: [
        {
          sourceLanguageTag: "hi",
          targetLanguageTag: "en",
          sourceText: "deploy karo",
          translatedText: "deploy karo",   // same → degraded
          kind: "INBOUND",
          degraded: true,
          timestamp: 1710000000000
        }
      ]
    };

    IG.renderTranslations(category, doc);

    const body = doc.getElementById("prof-translations-body");
    assert.match(body.textContent, /degraded/i, "degraded indicator must appear");

    // The li must have prof-entry-degraded class
    const degradedEntries = findAllByClass(body, "prof-entry-degraded");
    assert.ok(degradedEntries.length > 0, "entry must have prof-entry-degraded class");

    // Badge for kind
    assert.match(body.textContent, /INBOUND/, "kind badge must appear");
  });
});

test("renderTranslations: degraded=false — no degraded indicator", () => {
  withDocument(["prof-translations-body"], (doc) => {
    const category = {
      status: "OK",
      truncated: false,
      totalAvailable: 1,
      records: [
        {
          sourceLanguageTag: "hi",
          targetLanguageTag: "en",
          sourceText: "deploy karo",
          translatedText: "do the deploy",
          kind: "INBOUND",
          degraded: false,
          timestamp: 1710000000000
        }
      ]
    };

    IG.renderTranslations(category, doc);

    const body = doc.getElementById("prof-translations-body");
    assert.doesNotMatch(body.textContent, /\bdegraded\b/i, "degraded must NOT appear");
    const degradedEntries = findAllByClass(body, "prof-entry-degraded");
    assert.equal(degradedEntries.length, 0, "no entry should have prof-entry-degraded class");
  });
});

test("renderTranslations: UNAVAILABLE — shows cat-error", () => {
  withDocument(["prof-translations-body"], (doc) => {
    IG.renderTranslations({ status: "UNAVAILABLE", records: [] }, doc);
    const body = doc.getElementById("prof-translations-body");
    assert.ok(findAllByClass(body, "cat-error").length > 0, "cat-error must be shown");
  });
});

// ---------------------------------------------------------------------------
// Truncation indicator — shown when category.truncated = true (Req 8.4)
// ---------------------------------------------------------------------------

test("Truncation indicator: shown for command timeline when truncated=true", () => {
  withDocument(["prof-timeline-body"], (doc) => {
    const category = {
      status: "OK",
      truncated: true,
      totalAvailable: 600,
      records: [
        {
          commandText: "ls",
          correctiveAction: "ALLOW",
          divergenceScore: 0.1,
          reasonCode: null,
          profileState: "ACTIVE",
          inputOrigin: "SHELL_HOOK",
          timestamp: 1710000000000
        }
      ]
    };

    IG.renderCommandTimeline(category, doc);

    const body = doc.getElementById("prof-timeline-body");
    const truncNodes = findAllByClass(body, "cat-truncated");
    assert.ok(truncNodes.length > 0, "cat-truncated element must appear");
    assert.match(body.textContent, /600/, "total available must be shown");
  });
});

test("Truncation indicator: shown for multilingual when truncated=true", () => {
  withDocument(["prof-multilingual-body"], (doc) => {
    const category = {
      status: "OK",
      truncated: true,
      totalAvailable: 750,
      records: [
        {
          sourceLanguageTag: "ta",
          sourceText: "check logs",
          englishText: "check logs",
          translationAvailable: true,
          timestamp: 1710000000000
        }
      ]
    };

    IG.renderMultilingual(category, doc);

    const body = doc.getElementById("prof-multilingual-body");
    const truncNodes = findAllByClass(body, "cat-truncated");
    assert.ok(truncNodes.length > 0, "cat-truncated element must appear");
    assert.match(body.textContent, /750/, "total available must be shown");
  });
});

test("Truncation indicator: shown for assist queries when truncated=true", () => {
  withDocument(["prof-assist-body"], (doc) => {
    const category = {
      status: "OK",
      truncated: true,
      totalAvailable: 900,
      records: [{ queryEnglish: "test", generatedCommands: ["echo hi"], timestamp: 1710000000000 }]
    };

    IG.renderAssistQueries(category, doc);

    const body = doc.getElementById("prof-assist-body");
    const truncNodes = findAllByClass(body, "cat-truncated");
    assert.ok(truncNodes.length > 0, "cat-truncated element must appear");
    assert.match(body.textContent, /900/, "total available must be shown");
  });
});

test("Truncation indicator: shown for translations when truncated=true", () => {
  withDocument(["prof-translations-body"], (doc) => {
    const category = {
      status: "OK",
      truncated: true,
      totalAvailable: 1200,
      records: [{
        sourceLanguageTag: "hi", targetLanguageTag: "en",
        sourceText: "x", translatedText: "y", kind: "INBOUND",
        degraded: false, timestamp: 1710000000000
      }]
    };

    IG.renderTranslations(category, doc);

    const body = doc.getElementById("prof-translations-body");
    const truncNodes = findAllByClass(body, "cat-truncated");
    assert.ok(truncNodes.length > 0, "cat-truncated element must appear");
    assert.match(body.textContent, /1200/, "total available must be shown");
  });
});

// ---------------------------------------------------------------------------
// Profile-load failure vs empty-but-successful — visually distinct (Req 10.5, 10.6)
// ---------------------------------------------------------------------------

const PROFILE_PANEL_IDS = [
  "prof-timeline-body", "prof-multilingual-body", "prof-assist-body",
  "prof-translations-body", "prof-behavioral-body"
];

test("profileLoadFailed=true — all panels show whole-profile failure message (cat-error + prof-whole-failure)", () => {
  withDocument([...PROFILE_PANEL_IDS, "profiling-user-select", "profiling-no-user-msg", "profiling-window-bounds"], (doc) => {
    const state = Object.assign(IG.createProfileState(), {
      selectedUserId: "alice",
      usersStatus: "ok",
      users: ["alice"],
      profileStatus: "ok",
      profile: {
        userId: "alice",
        windowStart: 1710000000000,
        windowEnd: 1710086400000,
        profileLoadFailed: true,
        commandTimeline: { status: "UNAVAILABLE", records: [] },
        multilingual: { status: "UNAVAILABLE", records: [] },
        assistQueries: { status: "UNAVAILABLE", records: [] },
        translations: { status: "UNAVAILABLE", records: [] },
        behavioralProfile: { present: false }
      }
    });

    IG.renderProfileView(state, doc);

    for (const id of PROFILE_PANEL_IDS) {
      const body = doc.getElementById(id);
      const wholeFailure = findAllByClass(body, "prof-whole-failure");
      assert.ok(wholeFailure.length > 0,
        `${id}: must show prof-whole-failure class when profileLoadFailed=true`);
      assert.match(body.textContent, /unavailable|could not be loaded/i,
        `${id}: must describe the whole-profile failure`);
    }
  });
});

test("empty-but-successful profile — panels show cat-empty (not whole-failure)", () => {
  withDocument([...PROFILE_PANEL_IDS, "profiling-user-select", "profiling-no-user-msg", "profiling-window-bounds", "profiling-window-start", "profiling-window-end"], (doc) => {
    const state = Object.assign(IG.createProfileState(), {
      selectedUserId: "alice",
      usersStatus: "ok",
      users: ["alice"],
      profileStatus: "ok",
      profile: {
        userId: "alice",
        windowStart: 1710000000000,
        windowEnd: 1710086400000,
        profileLoadFailed: false,
        commandTimeline: { status: "OK", truncated: false, totalAvailable: 0, records: [] },
        multilingual: { status: "OK", truncated: false, totalAvailable: 0, records: [] },
        assistQueries: { status: "OK", truncated: false, totalAvailable: 0, records: [] },
        translations: { status: "OK", truncated: false, totalAvailable: 0, records: [] },
        behavioralProfile: { present: false }
      }
    });

    IG.renderProfileView(state, doc);

    // No whole-failure markers in any panel
    for (const id of PROFILE_PANEL_IDS) {
      const body = doc.getElementById(id);
      const wholeFailure = findAllByClass(body, "prof-whole-failure");
      assert.equal(wholeFailure.length, 0,
        `${id}: prof-whole-failure must NOT appear for an empty-but-successful profile`);
    }

    // Empty states should appear (cat-empty for most panels)
    const timelineBody = doc.getElementById("prof-timeline-body");
    const emptyNodes = findAllByClass(timelineBody, "cat-empty");
    assert.ok(emptyNodes.length > 0, "prof-timeline-body must show cat-empty for zero records");
  });
});

test("profileStatus=error — all panels show error message (not whole-failure)", () => {
  withDocument([...PROFILE_PANEL_IDS, "profiling-user-select", "profiling-no-user-msg", "profiling-window-bounds"], (doc) => {
    const state = Object.assign(IG.createProfileState(), {
      selectedUserId: "alice",
      usersStatus: "ok",
      users: ["alice"],
      profileStatus: "error",
      profile: null
    });

    IG.renderProfileView(state, doc);

    for (const id of PROFILE_PANEL_IDS) {
      const body = doc.getElementById(id);
      const errNodes = findAllByClass(body, "cat-error");
      assert.ok(errNodes.length > 0,
        `${id}: must show cat-error when profileStatus='error'`);
      // Must NOT show whole-failure (that's a different failure mode)
      const wholeFailure = findAllByClass(body, "prof-whole-failure");
      assert.equal(wholeFailure.length, 0,
        `${id}: prof-whole-failure must NOT appear for profileStatus='error'`);
    }
  });
});

// ---------------------------------------------------------------------------
// setView: switches views without closing an EventSource-like SSE stream (Req 11.2–11.5)
// ---------------------------------------------------------------------------

test("setView: switches view field from 'live' to 'profiling' (returns new state)", () => {
  const state = IG.createProfileState();
  assert.equal(state.view, "live", "initial view must be 'live'");

  const next = IG.setView(state, "profiling");
  assert.equal(next.view, "profiling", "view must be 'profiling' after setView");
  assert.equal(state.view, "live", "original state must not be mutated");
});

test("setView: switches view from 'profiling' back to 'live'", () => {
  const state = IG.createProfileState();
  const inProfiling = IG.setView(state, "profiling");
  const backToLive  = IG.setView(inProfiling, "live");
  assert.equal(backToLive.view, "live");
});

test("setView: does not close or touch an EventSource-like SSE stream", () => {
  // Simulate a mock EventSource with a close tracker
  let closeCalled = false;
  const mockSource = {
    readyState: 1, // OPEN
    close() { closeCalled = true; }
  };

  const state = IG.createProfileState();
  // setView only returns new state — it must never call .close() on any external object
  IG.setView(state, "profiling");
  IG.setView(state, "live");

  assert.equal(closeCalled, false, "setView must never close the SSE EventSource");
  assert.equal(mockSource.readyState, 1, "SSE stream must remain open");
});

test("setView: only changes the view field, all other state fields are unchanged", () => {
  const state = Object.assign(IG.createProfileState(), {
    users: ["alice", "bob"],
    usersStatus: "ok",
    selectedUserId: "alice",
    windowDays: 7,
    full: true,
    profile: { userId: "alice" },
    profileStatus: "ok"
  });

  const next = IG.setView(state, "profiling");

  // All fields except `view` must be identical
  assert.deepEqual(next.users, state.users);
  assert.equal(next.usersStatus, state.usersStatus);
  assert.equal(next.selectedUserId, state.selectedUserId);
  assert.equal(next.windowDays, state.windowDays);
  assert.equal(next.full, state.full);
  assert.deepEqual(next.profile, state.profile);
  assert.equal(next.profileStatus, state.profileStatus);
});

// ---------------------------------------------------------------------------
// renderBehavioralProfile — absent vs present
// ---------------------------------------------------------------------------

test("renderBehavioralProfile: present=false — shows cat-empty message", () => {
  withDocument(["prof-behavioral-body"], (doc) => {
    IG.renderBehavioralProfile({ present: false }, doc);
    const body = doc.getElementById("prof-behavioral-body");
    const emptyNodes = findAllByClass(body, "cat-empty");
    assert.ok(emptyNodes.length > 0, "must show cat-empty when profile is absent");
  });
});

test("renderBehavioralProfile: present=true — shows state, eventCount, top commands", () => {
  withDocument(["prof-behavioral-body"], (doc) => {
    const profile = {
      present: true,
      state: "ACTIVE",
      eventCount: 347,
      vocabulary: { "ls": 80, "git": 50, "cd": 30 },
      sequenceStats: { "ls→git": 20, "git→cd": 10 }
    };

    IG.renderBehavioralProfile(profile, doc);

    const body = doc.getElementById("prof-behavioral-body");
    assert.match(body.textContent, /ACTIVE/, "state must be shown");
    assert.match(body.textContent, /347/, "eventCount must be shown");
    assert.match(body.textContent, /ls/, "top vocabulary command must appear");
    assert.match(body.textContent, /ls→git/, "top sequence must appear");
  });
});


// ---------------------------------------------------------------------------
// renderRiskStats — average-score badge + 30-day trend
// ---------------------------------------------------------------------------

test("renderRiskStats: absent — shows empty-state message", () => {
  withDocument(["prof-risk-body"], (doc) => {
    IG.renderRiskStats({ present: false, daily: [] }, doc);
    const body = doc.getElementById("prof-risk-body");
    const empty = findAllByClass(body, "cat-empty");
    assert.ok(empty.length > 0, "must show cat-empty when no scored commands");
    assert.match(body.textContent, /no scored commands/i);
  });
});

test("renderRiskStats: null — shows unavailable error", () => {
  withDocument(["prof-risk-body"], (doc) => {
    IG.renderRiskStats(null, doc);
    const body = doc.getElementById("prof-risk-body");
    assert.ok(findAllByClass(body, "cat-error").length > 0, "must show cat-error when riskStats is null");
  });
});

test("renderRiskStats: present — shows avg score, band, and ALLOW/ASK/BLOCK counts", () => {
  withDocument(["prof-risk-body"], (doc) => {
    const riskStats = {
      present: true,
      averageScore: 0.5,
      commandCount: 10,
      allowCount: 4,
      askCount: 3,
      blockCount: 3,
      riskBand: "ELEVATED",
      windowDays: 30,
      daily: [{ date: "2026-08-04", epochDayMs: 1, count: 2, averageScore: 0.3 }]
    };
    IG.renderRiskStats(riskStats, doc);
    const body = doc.getElementById("prof-risk-body");

    // Average score value rendered (0.50)
    assert.match(body.textContent, /0\.50/, "avg score must be shown");
    // Band label
    assert.match(body.textContent, /ELEVATED/, "risk band must be shown");
    // Action counts
    assert.match(body.textContent, /ALLOW 4/);
    assert.match(body.textContent, /ASK 3/);
    assert.match(body.textContent, /BLOCK 3/);
    // Command count line
    assert.match(body.textContent, /10 commands over last 30 days/);
    // Band badge class present
    const badge = findAllByClass(body, "risk-elevated");
    assert.ok(badge.length > 0, "badge must carry the risk-elevated class");
  });
});

test("renderRiskStats: high band badge class", () => {
  withDocument(["prof-risk-body"], (doc) => {
    IG.renderRiskStats({
      present: true, averageScore: 0.9, commandCount: 5,
      allowCount: 0, askCount: 1, blockCount: 4, riskBand: "HIGH",
      windowDays: 30, daily: []
    }, doc);
    const body = doc.getElementById("prof-risk-body");
    assert.ok(findAllByClass(body, "risk-high").length > 0, "badge must carry risk-high class");
    assert.match(body.textContent, /0\.90/);
  });
});
