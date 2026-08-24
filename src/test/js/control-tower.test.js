/*
 * UI interaction / snapshot tests for the IntentGuard Control_Tower front-end
 * (src/main/resources/static/app.js). Task 16.2.
 *
 * Zero external dependencies: uses Node's built-in test runner (node:test) and a
 * tiny fake DOM (./fake-dom.js). Run with:  node --test src/test/js
 *
 * Coverage maps to Requirement 12:
 *   - 12.1 active Intent_Sessions rendering
 *   - 12.2 risk timeline of Divergence_Scores
 *   - 12.3 intent-vs-action divergence for a selected session
 *   - 12.4 explanation display on flagged events
 *   - 12.5 ask-state confirm/block controls invoking resolveAsk
 */
"use strict";

const test = require("node:test");
const assert = require("node:assert/strict");
const { makeDocument, findAllByClass } = require("./fake-dom.js");
const IG = require("../../main/resources/static/app.js");

// app.js's el() helper creates nodes via the *global* document, while renderers
// read containers via the passed doc. Point both at one fake document per call.
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

function scoreEvent(over) {
  return Object.assign({
    eventId: "evt-1",
    userId: "alice",
    action: IG.ACTION.ALLOW,
    divergenceScore: 0.1,
    explanation: null,
    timestamp: 1710000000000
  }, over || {});
}

function sessionEvent(over) {
  return Object.assign({
    sessionId: "sess-1",
    userId: "alice",
    declaredIntent: "refactor the auth module",
    status: IG.SESSION_STATUS.OPENED,
    timestamp: 1710000000000
  }, over || {});
}

// ---------------------------------------------------------------------------
// Pure state transitions (no DOM)
// ---------------------------------------------------------------------------

test("applySessionEvent tracks a session and activeSessions filters CLOSED", () => {
  const state = IG.createState();
  IG.applySessionEvent(state, sessionEvent({ sessionId: "a", timestamp: 1 }));
  IG.applySessionEvent(state, sessionEvent({ sessionId: "b", timestamp: 2 }));
  IG.applySessionEvent(state, sessionEvent({ sessionId: "b", status: IG.SESSION_STATUS.CLOSED, timestamp: 3 }));

  const active = IG.activeSessions(state);
  assert.equal(active.length, 1);
  assert.equal(active[0].sessionId, "a");
});

test("applyScoreEvent records scores and tracks pending ASKs", () => {
  const state = IG.createState();
  IG.applyScoreEvent(state, scoreEvent({ eventId: "e1", action: IG.ACTION.ALLOW }));
  IG.applyScoreEvent(state, scoreEvent({ eventId: "e2", action: IG.ACTION.ASK, divergenceScore: 0.5 }));

  assert.equal(state.scores.length, 2);
  assert.ok(state.pendingAsks.e2, "ASK event is pending");
  assert.ok(!state.pendingAsks.e1, "ALLOW event is not pending");
});

test("a later non-ASK score resolves a pending ASK, and removePendingAsk clears it", () => {
  const state = IG.createState();
  IG.applyScoreEvent(state, scoreEvent({ eventId: "e2", action: IG.ACTION.ASK }));
  assert.ok(state.pendingAsks.e2);
  IG.applyScoreEvent(state, scoreEvent({ eventId: "e2", action: IG.ACTION.BLOCK }));
  assert.ok(!state.pendingAsks.e2, "non-ASK score for same event clears pending");

  IG.applyScoreEvent(state, scoreEvent({ eventId: "e3", action: IG.ACTION.ASK }));
  IG.removePendingAsk(state, "e3");
  assert.ok(!state.pendingAsks.e3);
});

test("scoresForSession filters to the session user; flaggedScores excludes ALLOW", () => {
  const state = IG.createState();
  IG.applySessionEvent(state, sessionEvent({ sessionId: "s", userId: "alice" }));
  IG.applyScoreEvent(state, scoreEvent({ eventId: "e1", userId: "alice", action: IG.ACTION.ALLOW }));
  IG.applyScoreEvent(state, scoreEvent({ eventId: "e2", userId: "bob", action: IG.ACTION.BLOCK }));
  IG.applyScoreEvent(state, scoreEvent({ eventId: "e3", userId: "alice", action: IG.ACTION.ASK }));

  const mine = IG.scoresForSession(state, "s");
  assert.deepEqual(mine.map((s) => s.eventId).sort(), ["e1", "e3"]);

  const flagged = IG.flaggedScores(state).map((s) => s.eventId);
  assert.deepEqual(flagged.sort(), ["e2", "e3"]);
});

// ---------------------------------------------------------------------------
// 12.1 Active Intent_Sessions rendering
// ---------------------------------------------------------------------------

test("12.1 renderSessions lists active sessions with intent + user and skips CLOSED", () => {
  withDocument(["sessions-list"], (doc) => {
    const state = IG.createState();
    IG.applySessionEvent(state, sessionEvent({ sessionId: "a", userId: "alice", declaredIntent: "deploy the release", timestamp: 10 }));
    IG.applySessionEvent(state, sessionEvent({ sessionId: "b", userId: "bob", status: IG.SESSION_STATUS.CLOSED, timestamp: 20 }));

    IG.renderSessions(state, doc);
    const list = doc.getElementById("sessions-list");
    const items = list.children.filter((c) => c.tagName === "LI");

    assert.equal(items.length, 1, "only the active session is rendered");
    assert.match(list.textContent, /deploy the release/);
    assert.match(list.textContent, /alice/);
    assert.doesNotMatch(list.textContent, /bob/);
    assert.equal(items[0].getAttribute("data-session-id"), "a");
  });
});

test("12.1 renderSessions shows an empty state when there are no sessions", () => {
  withDocument(["sessions-list"], (doc) => {
    IG.renderSessions(IG.createState(), doc);
    const list = doc.getElementById("sessions-list");
    assert.match(list.textContent, /No active sessions/i);
  });
});

// ---------------------------------------------------------------------------
// 12.2 Risk timeline
// ---------------------------------------------------------------------------

test("12.2 renderTimeline lists scored events newest-first with action + score", () => {
  // timeline-canvas is intentionally NOT registered so the sparkline is skipped.
  withDocument(["timeline-list"], (doc) => {
    const state = IG.createState();
    IG.applyScoreEvent(state, scoreEvent({ eventId: "e1", action: IG.ACTION.ALLOW, divergenceScore: 0.10, timestamp: 1 }));
    IG.applyScoreEvent(state, scoreEvent({ eventId: "e2", action: IG.ACTION.BLOCK, divergenceScore: 0.90, timestamp: 2 }));

    IG.renderTimeline(state, doc);
    const list = doc.getElementById("timeline-list");
    const items = list.children.filter((c) => c.tagName === "LI");

    assert.equal(items.length, 2, "one row per score");
    // Newest first: BLOCK row precedes ALLOW row.
    assert.match(items[0].textContent, /BLOCK/);
    assert.match(items[0].textContent, /0\.90/);
    assert.match(items[1].textContent, /ALLOW/);
    assert.match(items[1].textContent, /0\.10/);
  });
});

// ---------------------------------------------------------------------------
// 12.3 Intent-vs-action divergence for a selected session
// ---------------------------------------------------------------------------

test("12.3 renderDivergence shows only the selected session user's scores with explanations", () => {
  withDocument(["divergence-header", "divergence-list"], (doc) => {
    const state = IG.createState();
    IG.applySessionEvent(state, sessionEvent({ sessionId: "s", userId: "alice", declaredIntent: "patch the parser" }));
    state.selectedSessionId = "s";
    IG.applyScoreEvent(state, scoreEvent({ eventId: "a1", userId: "alice", action: IG.ACTION.ALLOW, divergenceScore: 0.2 }));
    IG.applyScoreEvent(state, scoreEvent({ eventId: "b1", userId: "bob", action: IG.ACTION.BLOCK, divergenceScore: 0.8 }));
    IG.applyScoreEvent(state, scoreEvent({ eventId: "a2", userId: "alice", action: IG.ACTION.BLOCK, divergenceScore: 0.95, explanation: "off-intent: touches credentials" }));

    IG.renderDivergence(state, doc);
    const header = doc.getElementById("divergence-header");
    const list = doc.getElementById("divergence-list");
    const items = list.children.filter((c) => c.tagName === "LI");

    assert.match(header.textContent, /patch the parser/);
    assert.match(header.textContent, /alice/);
    assert.equal(items.length, 2, "only alice's two scores are shown");
    assert.match(list.textContent, /a1/);
    assert.match(list.textContent, /a2/);
    assert.doesNotMatch(list.textContent, /b1/);
    // Flagged score shows its explanation in the divergence view.
    assert.match(list.textContent, /off-intent: touches credentials/);
  });
});

test("12.3 renderDivergence prompts to pick a session when none is selected", () => {
  withDocument(["divergence-header", "divergence-list"], (doc) => {
    IG.renderDivergence(IG.createState(), doc);
    assert.match(doc.getElementById("divergence-header").textContent, /No session selected/i);
  });
});

// ---------------------------------------------------------------------------
// 12.4 Explanation display on flagged events
// ---------------------------------------------------------------------------

test("12.4 renderExplanations shows flagged events with their explanation text", () => {
  withDocument(["explanations-list"], (doc) => {
    const state = IG.createState();
    IG.applyScoreEvent(state, scoreEvent({ eventId: "ok", action: IG.ACTION.ALLOW }));
    IG.applyScoreEvent(state, scoreEvent({ eventId: "blk", userId: "carol", action: IG.ACTION.BLOCK, explanation: "high sequence surprise and context mismatch" }));

    IG.renderExplanations(state, doc);
    const list = doc.getElementById("explanations-list");
    const items = list.children.filter((c) => c.tagName === "LI");

    assert.equal(items.length, 1, "only the flagged event appears");
    assert.match(list.textContent, /high sequence surprise and context mismatch/);
    assert.match(list.textContent, /carol/);
    assert.doesNotMatch(list.textContent, /\bok\b/);
  });
});

// ---------------------------------------------------------------------------
// 12.5 Ask-state confirm/block controls invoking resolveAsk
// ---------------------------------------------------------------------------

test("12.5 renderPendingAsks renders Confirm/Block controls that call onResolve with ALLOW/BLOCK", () => {
  withDocument(["asks-list"], (doc) => {
    const state = IG.createState();
    IG.applyScoreEvent(state, scoreEvent({ eventId: "ask-42", userId: "dave", action: IG.ACTION.ASK, divergenceScore: 0.55, explanation: "pasted payload in unrelated dir" }));

    const calls = [];
    IG.renderPendingAsks(state, doc, (id, action) => calls.push([id, action]));

    const list = doc.getElementById("asks-list");
    assert.match(list.textContent, /ask-42/);
    assert.match(list.textContent, /pasted payload in unrelated dir/);

    const confirmBtns = findAllByClass(list, "btn-confirm");
    const blockBtns = findAllByClass(list, "btn-block");
    assert.equal(confirmBtns.length, 1);
    assert.equal(blockBtns.length, 1);
    assert.equal(confirmBtns[0].textContent, "Confirm");
    assert.equal(blockBtns[0].textContent, "Block");

    confirmBtns[0].fire("click");
    blockBtns[0].fire("click");

    assert.deepEqual(calls, [
      ["ask-42", IG.ACTION.ALLOW],
      ["ask-42", IG.ACTION.BLOCK]
    ]);
  });
});

test("12.5 renderPendingAsks shows empty state when nothing awaits confirmation", () => {
  withDocument(["asks-list"], (doc) => {
    IG.renderPendingAsks(IG.createState(), doc);
    assert.match(doc.getElementById("asks-list").textContent, /Nothing awaiting confirmation/i);
  });
});

test("12.5 resolveAsk POSTs the admin choice to the resolve endpoint", async () => {
  let captured = null;
  const fakeFetch = (url, opts) => {
    captured = { url, opts };
    return Promise.resolve({ ok: true, json: () => Promise.resolve({ status: "RESOLVED" }) });
  };

  const result = await IG.resolveAsk("ask-42", IG.ACTION.BLOCK, { fetch: fakeFetch, resolvedBy: "admin" });

  assert.deepEqual(result, { status: "RESOLVED" });
  assert.equal(captured.url, "/api/events/ask-42/resolve");
  assert.equal(captured.opts.method, "POST");
  const body = JSON.parse(captured.opts.body);
  assert.equal(body.action, IG.ACTION.BLOCK);
  assert.equal(body.resolvedBy, "admin");
});

test("12.5 resolveAsk rejects when the backend returns a non-ok status", async () => {
  const fakeFetch = () => Promise.resolve({ ok: false, status: 409, json: () => Promise.resolve({}) });
  await assert.rejects(
    () => IG.resolveAsk("ask-42", IG.ACTION.ALLOW, { fetch: fakeFetch }),
    /resolve failed: 409/
  );
});

// ---------------------------------------------------------------------------
// End-to-end: a decoded live envelope flows into a full re-render
// ---------------------------------------------------------------------------

test("handleLiveEvent + renderAll wire SESSION/SCORE/ALERT envelopes into every panel", () => {
  const ids = [
    "sessions-list", "timeline-list", "divergence-header", "divergence-list",
    "explanations-list", "asks-list", "alerts-list"
  ];
  withDocument(ids, (doc) => {
    const state = IG.createState();
    IG.handleLiveEvent(state, { type: "SESSION", payload: sessionEvent({ sessionId: "s", userId: "alice" }) });
    state.selectedSessionId = "s";
    IG.handleLiveEvent(state, { type: "SCORE", payload: scoreEvent({ eventId: "e1", userId: "alice", action: IG.ACTION.ASK, divergenceScore: 0.5, explanation: "needs review" }) });
    IG.handleLiveEvent(state, { type: "ALERT", payload: { alertType: "SESSION_ANOMALY", message: "behavioral drift", highRisk: true, userId: "alice", timestamp: 1 } });

    IG.renderAll(state, doc);

    assert.match(doc.getElementById("sessions-list").textContent, /alice/);
    assert.match(doc.getElementById("timeline-list").textContent, /ASK/);
    assert.match(doc.getElementById("divergence-list").textContent, /e1/);
    assert.match(doc.getElementById("explanations-list").textContent, /needs review/);
    assert.match(doc.getElementById("asks-list").textContent, /e1/);
    assert.match(doc.getElementById("alerts-list").textContent, /behavioral drift/);
    assert.match(doc.getElementById("alerts-list").textContent, /HIGH RISK/);
  });
});
