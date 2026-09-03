/*
 * IntentGuard Control Tower - front-end controller.
 *
 * Design: "Control_Tower API and web frontend". Renders active Intent_Sessions
 * (Req 12.1), a risk timeline of Divergence_Scores (Req 12.2), an intent-vs-action
 * divergence view for a selected session (Req 12.3), explanations on flagged events
 * (Req 12.4), ask-state confirm/block controls (Req 12.5), and a threshold config
 * form. Consumes the SSE live channel at GET /api/stream (Req 12.6).
 *
 * Structure: pure state-transition functions (applySessionEvent / applyScoreEvent /
 * applyAlertEvent) hold no DOM references so they can be unit-tested directly; the
 * render* functions read state and update the DOM; the wiring at the bottom connects
 * the EventSource and forms. Everything is exported both on `window` (browser tests)
 * and via `module.exports` (Node-based tests in task 16.2).
 */
(function (root) {
  "use strict";

  var ACTION = { ALLOW: "ALLOW", ASK: "ASK", BLOCK: "BLOCK" };
  var SESSION_STATUS = { OPENED: "OPENED", MODIFIED: "MODIFIED", CLOSED: "CLOSED" };
  var MAX_TIMELINE = 100;

  // ---------------------------------------------------------------------------
  // State
  // ---------------------------------------------------------------------------

  function createState() {
    return {
      sessions: {},      // sessionId -> SessionUpdateEvent (last known)
      scores: [],        // ScoreEvent[] in arrival order (newest last)
      alerts: [],        // AlertEvent[] newest first
      pendingAsks: {},   // eventId -> ScoreEvent (action === ASK, unresolved)
      selectedSessionId: null
    };
  }

  // ---------------------------------------------------------------------------
  // Pure state transitions (no DOM) - unit-testable in isolation
  // ---------------------------------------------------------------------------

  /** Applies a SESSION live event, updating the tracked session by id. */
  function applySessionEvent(state, event) {
    if (!event || !event.sessionId) return state;
    state.sessions[event.sessionId] = {
      sessionId: event.sessionId,
      userId: event.userId,
      declaredIntent: event.declaredIntent,
      status: event.status,
      timestamp: event.timestamp
    };
    // If the selected session closed, keep selection (view will show closed state).
    return state;
  }

  /** Returns only the sessions that are not closed, newest first. */
  function activeSessions(state) {
    return Object.keys(state.sessions)
      .map(function (id) { return state.sessions[id]; })
      .filter(function (s) { return s.status !== SESSION_STATUS.CLOSED; })
      .sort(function (a, b) { return b.timestamp - a.timestamp; });
  }

  /** Applies a SCORE live event: records it, tracking/removing pending asks. */
  function applyScoreEvent(state, event) {
    if (!event || !event.eventId) return state;
    state.scores.push(event);
    if (state.scores.length > MAX_TIMELINE) {
      state.scores.splice(0, state.scores.length - MAX_TIMELINE);
    }
    if (event.action === ACTION.ASK) {
      state.pendingAsks[event.eventId] = event;
    } else {
      // A subsequent non-ask score for the same event resolves any pending ask.
      delete state.pendingAsks[event.eventId];
    }
    return state;
  }

  /** Applies an ALERT live event, newest first. */
  function applyAlertEvent(state, event) {
    if (!event) return state;
    state.alerts.unshift(event);
    return state;
  }

  /** True when a score/action represents a flagged (non-allow) decision. */
  function isFlagged(scoreEvent) {
    return !!scoreEvent && scoreEvent.action && scoreEvent.action !== ACTION.ALLOW;
  }

  /** Scores belonging to the user of the given session (intent-vs-action view). */
  function scoresForSession(state, sessionId) {
    var session = state.sessions[sessionId];
    if (!session) return [];
    return state.scores.filter(function (s) { return s.userId === session.userId; });
  }

  /** All flagged scores, newest first. */
  function flaggedScores(state) {
    return state.scores.filter(isFlagged).slice().reverse();
  }

  /** Removes a resolved ask from the pending set. */
  function removePendingAsk(state, eventId) {
    delete state.pendingAsks[eventId];
    return state;
  }

  // ---------------------------------------------------------------------------
  // User Profiling — pure state and derivation functions (Req 1.3, 4.3, 6.2, 6.3, 7.6, 11.2–11.4)
  // ---------------------------------------------------------------------------

  /**
   * Creates the initial profile-screen state.
   * view: 'live' | 'profiling'
   * usersStatus / profileStatus: 'idle' | 'loading' | 'ok' | 'error'
   */
  function createProfileState() {
    return {
      view: "live",
      users: [],
      usersStatus: "idle",
      selectedUserId: null,
      windowDays: 3,
      full: false,
      profile: null,
      profileStatus: "idle"
    };
  }

  /**
   * Returns a new state with the view toggled.  Never closes the SSE EventSource
   * and never touches any live-view field — only the `view` key changes. (Req 11.2–11.4)
   * @param {object} state  current profile state
   * @param {string} view   'live' or 'profiling'
   */
  function setView(state, view) {
    return Object.assign({}, state, { view: view });
  }

  /**
   * Returns a new array of user ids sorted case-insensitively ascending. (Req 1.3)
   * @param {string[]} users
   * @returns {string[]}
   */
  function sortUsersCaseInsensitive(users) {
    if (!users || users.length === 0) return [];
    return users.slice().sort(function (a, b) {
      var la = a.toLowerCase();
      var lb = b.toLowerCase();
      if (la < lb) return -1;
      if (la > lb) return 1;
      return 0;
    });
  }

  /**
   * Truncates text to at most maxLen characters. (Req 4.3)
   * @param {string} text
   * @param {number} maxLen
   * @returns {{ text: string, wasTruncated: boolean }}
   */
  function truncateQueryText(text, maxLen) {
    if (typeof text !== "string") {
      return { text: "", wasTruncated: false };
    }
    if (text.length <= maxLen) {
      return { text: text, wasTruncated: false };
    }
    return { text: text.slice(0, maxLen), wasTruncated: true };
  }

  /**
   * Converts a vocabulary/sequence map into a top-k list ordered by descending count
   * then ascending key. (Req 6.2, 6.3)
   * @param {Object|Map} map  keys are string labels, values are numeric counts
   * @param {number}     k    maximum number of entries to return
   * @returns {{ key: string, count: number }[]}
   */
  function topEntries(map, k) {
    if (!map) return [];
    var entries;
    if (typeof Map !== "undefined" && map instanceof Map) {
      entries = [];
      map.forEach(function (v, key) { entries.push({ key: String(key), count: Number(v) || 0 }); });
    } else {
      entries = Object.keys(map).map(function (key) {
        return { key: key, count: Number(map[key]) || 0 };
      });
    }
    entries.sort(function (a, b) {
      if (b.count !== a.count) return b.count - a.count;   // descending count
      if (a.key < b.key) return -1;                        // ascending key (tie-break)
      if (a.key > b.key) return 1;
      return 0;
    });
    return entries.slice(0, k);
  }

  /**
   * Formats an epoch-ms window as a human-readable date/time range string. (Req 7.6)
   * Produces "YYYY-MM-DD HH:MM:SS — YYYY-MM-DD HH:MM:SS" in local time.
   * @param {number} start  epoch milliseconds
   * @param {number} end    epoch milliseconds
   * @returns {string}
   */
  function formatWindow(start, end) {
    function pad(n) { return String(n).padStart(2, "0"); }
    function fmt(ms) {
      var d = new Date(ms);
      return d.getFullYear() + "-" +
        pad(d.getMonth() + 1) + "-" +
        pad(d.getDate()) + " " +
        pad(d.getHours()) + ":" +
        pad(d.getMinutes()) + ":" +
        pad(d.getSeconds());
    }
    if (!start && !end) return "";
    return fmt(start) + " \u2014 " + fmt(end);
  }

  // ---------------------------------------------------------------------------
  // Formatting helpers
  // ---------------------------------------------------------------------------

  function fmtScore(v) {
    return (typeof v === "number" && isFinite(v)) ? v.toFixed(2) : "-";
  }

  function fmtTime(ms) {
    if (!ms) return "";
    var d = new Date(ms);
    return d.toLocaleTimeString();
  }

  function actionBadgeClass(action) {
    switch (action) {
      case ACTION.ALLOW: return "badge badge-allow";
      case ACTION.ASK: return "badge badge-ask";
      case ACTION.BLOCK: return "badge badge-block";
      default: return "badge";
    }
  }

  function scoreColor(action) {
    switch (action) {
      case ACTION.ALLOW: return "#35c47a";
      case ACTION.ASK: return "#f2b24a";
      case ACTION.BLOCK: return "#ef5b6b";
      default: return "#8ea0bd";
    }
  }

  // Small DOM helper.
  function el(tag, className, text) {
    var node = document.createElement(tag);
    if (className) node.className = className;
    if (text != null) node.textContent = text;
    return node;
  }

  // ---------------------------------------------------------------------------
  // Renderers (DOM). Each is defensive about missing containers so tests can
  // render individual panels without the full page.
  // ---------------------------------------------------------------------------

  function renderSessions(state, doc) {
    doc = doc || document;
    var list = doc.getElementById("sessions-list");
    if (!list) return;
    list.innerHTML = "";
    var sessions = activeSessions(state);
    if (sessions.length === 0) {
      list.appendChild(el("li", "empty", "No active sessions yet."));
      return;
    }
    sessions.forEach(function (s) {
      var li = el("li", s.sessionId === state.selectedSessionId ? "selected" : "");
      li.setAttribute("data-session-id", s.sessionId);
      li.appendChild(el("div", "session-intent", s.declaredIntent || "(no declared intent)"));
      var meta = el("div", "session-meta");
      meta.textContent = "user: " + (s.userId || "?") + " · " + (s.status || "") + " · " + fmtTime(s.timestamp);
      li.appendChild(meta);
      li.addEventListener("click", function () {
        state.selectedSessionId = s.sessionId;
        renderSessions(state, doc);
        renderDivergence(state, doc);
      });
      list.appendChild(li);
    });
  }

  function renderTimeline(state, doc) {
    doc = doc || document;
    var list = doc.getElementById("timeline-list");
    if (list) {
      list.innerHTML = "";
      state.scores.slice().reverse().slice(0, 30).forEach(function (s) {
        var li = el("li");
        var line = el("div", "score-line");
        line.appendChild(el("span", "ts", fmtTime(s.timestamp) + " · " + (s.userId || "?")));
        var right = el("span");
        var badge = el("span", actionBadgeClass(s.action), s.action || "?");
        var val = el("span", "score-value");
        val.textContent = " " + fmtScore(s.divergenceScore);
        right.appendChild(badge);
        right.appendChild(val);
        line.appendChild(right);
        li.appendChild(line);
        list.appendChild(li);
      });
    }
    drawTimelineCanvas(state, doc);
  }

  /** Draws a simple divergence sparkline; skipped when canvas is unavailable (tests). */
  function drawTimelineCanvas(state, doc) {
    doc = doc || document;
    var canvas = doc.getElementById("timeline-canvas");
    if (!canvas || typeof canvas.getContext !== "function") return;
    var ctx = canvas.getContext("2d");
    if (!ctx) return;
    var w = canvas.width, h = canvas.height;
    ctx.clearRect(0, 0, w, h);

    // Threshold guide lines.
    ctx.strokeStyle = "rgba(255,255,255,0.08)";
    ctx.lineWidth = 1;
    [0.25, 0.5, 0.75].forEach(function (t) {
      var y = h - t * h;
      ctx.beginPath();
      ctx.moveTo(0, y);
      ctx.lineTo(w, y);
      ctx.stroke();
    });

    var scores = state.scores;
    if (scores.length === 0) return;
    var n = scores.length;
    var step = n > 1 ? w / (n - 1) : w;

    // Line.
    ctx.strokeStyle = "#4f8cff";
    ctx.lineWidth = 2;
    ctx.beginPath();
    scores.forEach(function (s, i) {
      var x = n > 1 ? i * step : w / 2;
      var y = h - clamp01(s.divergenceScore) * h;
      if (i === 0) ctx.moveTo(x, y); else ctx.lineTo(x, y);
    });
    ctx.stroke();

    // Points colored by action.
    scores.forEach(function (s, i) {
      var x = n > 1 ? i * step : w / 2;
      var y = h - clamp01(s.divergenceScore) * h;
      ctx.fillStyle = scoreColor(s.action);
      ctx.beginPath();
      ctx.arc(x, y, 3, 0, Math.PI * 2);
      ctx.fill();
    });
  }

  function clamp01(v) {
    if (typeof v !== "number" || isNaN(v)) return 0;
    if (v < 0) return 0;
    if (v > 1) return 1;
    return v;
  }

  function renderDivergence(state, doc) {
    doc = doc || document;
    var header = doc.getElementById("divergence-header");
    var list = doc.getElementById("divergence-list");
    if (!header || !list) return;
    header.innerHTML = "";
    list.innerHTML = "";

    var session = state.selectedSessionId ? state.sessions[state.selectedSessionId] : null;
    if (!session) {
      header.appendChild(el("span", "muted", "No session selected."));
      return;
    }
    header.appendChild(el("div", "session-intent", "Intent: " + (session.declaredIntent || "(none)")));
    header.appendChild(el("div", "session-meta", "user: " + (session.userId || "?") + " · " + (session.status || "")));

    var rows = scoresForSession(state, state.selectedSessionId).slice().reverse();
    if (rows.length === 0) {
      list.appendChild(el("li", "empty", "No scored actions for this session yet."));
      return;
    }
    rows.forEach(function (s) {
      var li = el("li");
      var line = el("div", "score-line");
      line.appendChild(el("span", "mono", s.eventId));
      var right = el("span");
      right.appendChild(el("span", actionBadgeClass(s.action), s.action || "?"));
      var val = el("span", "score-value");
      val.textContent = " " + fmtScore(s.divergenceScore);
      right.appendChild(val);
      line.appendChild(right);
      li.appendChild(line);
      if (isFlagged(s) && s.explanation) {
        li.appendChild(el("div", "explanation-text", s.explanation));
      }
      list.appendChild(li);
    });
  }

  function renderExplanations(state, doc) {
    doc = doc || document;
    var list = doc.getElementById("explanations-list");
    if (!list) return;
    list.innerHTML = "";
    var flagged = flaggedScores(state);
    if (flagged.length === 0) {
      list.appendChild(el("li", "empty", "No flagged events yet."));
      return;
    }
    flagged.slice(0, 30).forEach(function (s) {
      var li = el("li");
      var line = el("div", "score-line");
      line.appendChild(el("span", "mono", (s.userId || "?") + " · " + s.eventId));
      line.appendChild(el("span", actionBadgeClass(s.action), s.action || "?"));
      li.appendChild(line);
      li.appendChild(el("div", "explanation-text", s.explanation || "(no explanation provided)"));
      li.appendChild(el("div", "ts", fmtTime(s.timestamp)));
      list.appendChild(li);
    });
  }

  function renderPendingAsks(state, doc, onResolve) {
    doc = doc || document;
    var list = doc.getElementById("asks-list");
    if (!list) return;
    list.innerHTML = "";
    var ids = Object.keys(state.pendingAsks);
    if (ids.length === 0) {
      list.appendChild(el("li", "empty", "Nothing awaiting confirmation."));
      return;
    }
    ids.forEach(function (id) {
      var s = state.pendingAsks[id];
      var li = el("li");
      var line = el("div", "score-line");
      line.appendChild(el("span", "mono", (s.userId || "?") + " · " + id));
      var val = el("span", "score-value", fmtScore(s.divergenceScore));
      line.appendChild(val);
      li.appendChild(line);
      if (s.explanation) li.appendChild(el("div", "explanation-text", s.explanation));

      var controls = el("div", "ask-controls");
      var confirmBtn = el("button", "btn btn-confirm", "Confirm");
      confirmBtn.type = "button";
      confirmBtn.addEventListener("click", function () {
        (onResolve || resolveAsk)(id, ACTION.ALLOW);
      });
      var blockBtn = el("button", "btn btn-block", "Block");
      blockBtn.type = "button";
      blockBtn.addEventListener("click", function () {
        (onResolve || resolveAsk)(id, ACTION.BLOCK);
      });
      controls.appendChild(confirmBtn);
      controls.appendChild(blockBtn);
      li.appendChild(controls);
      list.appendChild(li);
    });
  }

  function renderAlerts(state, doc) {
    doc = doc || document;
    var list = doc.getElementById("alerts-list");
    if (!list) return;
    list.innerHTML = "";
    if (state.alerts.length === 0) {
      list.appendChild(el("li", "empty", "No alerts."));
      return;
    }
    state.alerts.slice(0, 30).forEach(function (a) {
      var li = el("li", a.highRisk ? "high-risk" : "");
      var line = el("div", "score-line");
      line.appendChild(el("span", "", a.alertType || "ALERT"));
      if (a.highRisk) line.appendChild(el("span", "badge badge-block", "HIGH RISK"));
      li.appendChild(line);
      li.appendChild(el("div", "explanation-text", a.message || ""));
      if (a.userId) li.appendChild(el("div", "ts", "user: " + a.userId + " · " + fmtTime(a.timestamp)));
      else li.appendChild(el("div", "ts", fmtTime(a.timestamp)));
      list.appendChild(li);
    });
  }

  function renderHistory(records, doc) {
    doc = doc || document;
    var list = doc.getElementById("history-list");
    if (!list) return;
    list.innerHTML = "";
    if (!records || records.length === 0) {
      list.appendChild(el("li", "empty", "No records for this query."));
      return;
    }
    records.forEach(function (r) {
      var li = el("li");
      var line = el("div", "score-line");
      line.appendChild(el("span", "mono", r.commandText || r.recordType || r.eventId || "(record)"));
      if (r.correctiveAction) {
        line.appendChild(el("span", actionBadgeClass(r.correctiveAction), r.correctiveAction));
      }
      li.appendChild(line);
      if (typeof r.divergenceScore === "number") {
        li.appendChild(el("div", "ts", "score " + fmtScore(r.divergenceScore) + " · " + fmtTime(r.timestamp)));
      } else {
        li.appendChild(el("div", "ts", fmtTime(r.timestamp)));
      }
      if (r.explanation) li.appendChild(el("div", "explanation-text", r.explanation));
      list.appendChild(li);
    });
  }

  /** Re-renders every panel that depends on live state. */
  function renderAll(state, doc) {
    renderSessions(state, doc);
    renderTimeline(state, doc);
    renderDivergence(state, doc);
    renderExplanations(state, doc);
    renderPendingAsks(state, doc);
    renderAlerts(state, doc);
  }

  // ---------------------------------------------------------------------------
  // Live channel dispatch
  // ---------------------------------------------------------------------------

  /**
   * Applies a decoded LiveEvent envelope to state. Accepts either the raw envelope
   * ({type,timestamp,payload}) or a bare payload with an explicit `type` argument.
   * Returns the state for chaining/testing.
   */
  function handleLiveEvent(state, envelopeOrPayload, explicitType) {
    if (!envelopeOrPayload) return state;
    var type = explicitType || envelopeOrPayload.type;
    var payload = explicitType ? envelopeOrPayload : envelopeOrPayload.payload;
    switch (type) {
      case "SESSION": return applySessionEvent(state, payload);
      case "SCORE": return applyScoreEvent(state, payload);
      case "ALERT": return applyAlertEvent(state, payload);
      default: return state;
    }
  }

  // ---------------------------------------------------------------------------
  // Backend calls
  // ---------------------------------------------------------------------------

  /** POST /api/events/{eventId}/resolve with the administrator's choice (Req 12.5). */
  function resolveAsk(eventId, action, opts) {
    opts = opts || {};
    var fetchFn = opts.fetch || (typeof fetch !== "undefined" ? fetch : null);
    if (!fetchFn) return Promise.reject(new Error("fetch unavailable"));
    return fetchFn("/api/events/" + encodeURIComponent(eventId) + "/resolve", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ action: action, resolvedBy: opts.resolvedBy || "admin" })
    }).then(function (res) {
      if (!res.ok) throw new Error("resolve failed: " + res.status);
      return res.json();
    });
  }

  /** Reads threshold form values into a ThresholdConfigUpdate payload. */
  function readThresholdForm(doc) {
    doc = doc || document;
    var num = function (id) { return parseFloat(doc.getElementById(id).value); };
    var intv = function (id) { return parseInt(doc.getElementById(id).value, 10); };
    var weights = {};
    var inputs = doc.querySelectorAll("[data-component]");
    Array.prototype.forEach.call(inputs, function (inp) {
      weights[inp.getAttribute("data-component")] = parseFloat(inp.value);
    });
    return {
      askThreshold: num("askThreshold"),
      blockThreshold: num("blockThreshold"),
      componentWeights: weights,
      inferredIntentSemanticWeight: num("inferredIntentSemanticWeight"),
      learningMinEvents: intv("learningMinEvents"),
      monitoringGapTimeoutMs: intv("monitoringGapTimeoutMs"),
      confirmationTimeoutMs: intv("confirmationTimeoutMs"),
      llmTimeoutMs: intv("llmTimeoutMs"),
      correlationWindowMs: intv("correlationWindowMs")
    };
  }

  /** PUT /api/thresholds with the given update; resolves with the new config or rejects with the error body. */
  function updateThresholds(update, opts) {
    opts = opts || {};
    var fetchFn = opts.fetch || (typeof fetch !== "undefined" ? fetch : null);
    if (!fetchFn) return Promise.reject(new Error("fetch unavailable"));
    return fetchFn("/api/thresholds", {
      method: "PUT",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(update)
    }).then(function (res) {
      return res.json().then(function (body) {
        if (!res.ok) {
          var err = new Error((body && body.message) || ("threshold update failed: " + res.status));
          err.body = body;
          throw err;
        }
        return body;
      });
    });
  }

  /**
   * GET /api/bootstrap?days=N — hydrates state from persisted MongoDB data (last N days) so the
   * dashboard reflects historical sessions/scores/alerts on a fresh page load instead of starting
   * empty. Replays the returned events through the same state-transition functions the live
   * channel uses, preserving ordering (oldest-first) so timelines and pending-asks rebuild
   * correctly.
   */
  function loadBootstrap(state, opts) {
    opts = opts || {};
    var fetchFn = opts.fetch || (typeof fetch !== "undefined" ? fetch : null);
    if (!fetchFn) return Promise.reject(new Error("fetch unavailable"));
    var days = opts.days || 3;
    return fetchFn("/api/bootstrap?days=" + encodeURIComponent(days)).then(function (res) {
      if (!res.ok) throw new Error("bootstrap failed: " + res.status);
      return res.json();
    }).then(function (data) {
      if (!data) return state;
      (data.sessions || []).forEach(function (s) { applySessionEvent(state, s); });
      (data.scores || []).forEach(function (s) { applyScoreEvent(state, s); });
      (data.alerts || []).forEach(function (a) { applyAlertEvent(state, a); });
      return state;
    });
  }

  /** GET /api/history?userId=&from=&to= (Req 11.3). */
  function loadHistory(userId, from, to, opts) {
    opts = opts || {};
    var fetchFn = opts.fetch || (typeof fetch !== "undefined" ? fetch : null);
    if (!fetchFn) return Promise.reject(new Error("fetch unavailable"));
    var qs = "?userId=" + encodeURIComponent(userId) +
      "&from=" + encodeURIComponent(from) + "&to=" + encodeURIComponent(to);
    return fetchFn("/api/history" + qs).then(function (res) {
      if (!res.ok) throw new Error("history query failed: " + res.status);
      return res.json();
    });
  }

  /**
   * GET /api/users — returns the KnownUsersView (list of distinct known user ids).
   * Uses a 3-second AbortController timeout. On non-OK status or network/timeout error,
   * rejects with a descriptive Error so callers can surface a distinct error state (Req 1.7).
   *
   * @param {object} [opts]
   * @param {Function} [opts.fetch]           injectable fetch for tests
   * @param {Function} [opts.AbortController] injectable AbortController for tests
   * @returns {Promise<{users: string[]}>}
   */
  function loadUsers(opts) {
    opts = opts || {};
    var fetchFn = opts.fetch || (typeof fetch !== "undefined" ? fetch : null);
    if (!fetchFn) return Promise.reject(new Error("fetch unavailable"));
    var AC = opts.AbortController || (typeof AbortController !== "undefined" ? AbortController : null);
    var signal = null;
    var timer = null;
    if (AC) {
      var controller = new AC();
      signal = controller.signal;
      timer = setTimeout(function () { controller.abort(); }, 3000);
    }
    return fetchFn("/api/users", signal ? { signal: signal } : {}).then(function (res) {
      if (timer !== null) clearTimeout(timer);
      if (!res.ok) throw new Error("users load failed: " + res.status);
      return res.json();
    }).catch(function (err) {
      if (timer !== null) clearTimeout(timer);
      if (err && err.name === "AbortError") {
        throw new Error("users load timed out");
      }
      throw err;
    });
  }

  /**
   * GET /api/users/{userId}/profile?days={days}&full={full} — returns the UserProfileView for the
   * selected user and time window. Uses a 3-second AbortController timeout. Rejects distinctly on
   * failure (network error, timeout, non-OK status) so the caller can retain the current view and
   * show an error indication without clearing a previously loaded profile (Req 2.7, 11.5).
   * An OK response with an empty-but-successful profile still resolves (not rejects).
   *
   * @param {string} userId
   * @param {{ days?: number, full?: boolean }} [window]
   * @param {object} [opts]
   * @param {Function} [opts.fetch]           injectable fetch for tests
   * @param {Function} [opts.AbortController] injectable AbortController for tests
   * @returns {Promise<object>}  UserProfileView JSON
   */
  function loadProfile(userId, window, opts) {
    opts = opts || {};
    window = window || {};
    var fetchFn = opts.fetch || (typeof fetch !== "undefined" ? fetch : null);
    if (!fetchFn) return Promise.reject(new Error("fetch unavailable"));
    var days = window.days != null ? window.days : 3;
    var full = window.full ? "true" : "false";
    var qs = "?days=" + encodeURIComponent(days) + "&full=" + encodeURIComponent(full);
    var url = "/api/users/" + encodeURIComponent(userId) + "/profile" + qs;
    var AC = opts.AbortController || (typeof AbortController !== "undefined" ? AbortController : null);
    var signal = null;
    var timer = null;
    if (AC) {
      var controller = new AC();
      signal = controller.signal;
      timer = setTimeout(function () { controller.abort(); }, 3000);
    }
    return fetchFn(url, signal ? { signal: signal } : {}).then(function (res) {
      if (timer !== null) clearTimeout(timer);
      if (!res.ok) throw new Error("profile load failed: " + res.status);
      return res.json();
    }).catch(function (err) {
      if (timer !== null) clearTimeout(timer);
      if (err && err.name === "AbortError") {
        throw new Error("profile load timed out");
      }
      throw err;
    });
  }

  // ---------------------------------------------------------------------------
  // User Profiling renderers (Req 1.4–1.7, 2.3–2.7, 3.2, 3.5, 3.6, 4.2, 4.4,
  //   5.2, 5.4, 5.5, 6.2, 6.4, 8.4, 10.5, 10.6)
  // ---------------------------------------------------------------------------

  /**
   * Populates the searchable user control (#profiling-user-select text input + its
   * #profiling-user-options <datalist>) from state.users; handles loading/error/empty/ok.
   * Also updates #profiling-users-status hint and #profiling-load-btn disabled state.
   * (Req 1.4, 1.5, 1.6, 1.7)
   *
   * The control is a type-to-filter text input backed by a native <datalist>: the operator can
   * type to narrow the known-user list or open the picker to choose. Load is enabled only when the
   * input value matches a known user (case-insensitively).
   */
  function renderUserSelector(state, doc) {
    doc = doc || document;
    var input = doc.getElementById("profiling-user-select");
    var list = doc.getElementById("profiling-user-options");
    var hint = doc.getElementById("profiling-users-status");
    var btn = doc.getElementById("profiling-load-btn");

    if (!input) return;

    // Helper: clear the datalist options (works on both real DOM and the fake test DOM).
    function clearOptions() {
      if (list) list.innerHTML = "";
    }

    if (state.usersStatus === "loading") {
      clearOptions();
      input.disabled = true;
      input.setAttribute("placeholder", "Loading users…");
      if (hint) { hint.className = "profiling-status-hint"; hint.textContent = "Loading…"; }
      if (btn) btn.disabled = true;
      return;
    }

    if (state.usersStatus === "error") {
      clearOptions();
      input.disabled = true;
      input.setAttribute("placeholder", "— failed to load users —");
      if (hint) {
        hint.className = "profiling-status-hint cat-error";
        hint.textContent = "Could not load user list. Refresh the page to retry.";
      }
      if (btn) btn.disabled = true;
      return;
    }

    // ok
    input.disabled = false;
    input.setAttribute("placeholder", "Type or pick a user…");
    clearOptions();

    if (!state.users || state.users.length === 0) {
      input.disabled = true;
      input.setAttribute("placeholder", "No known users yet.");
      if (hint) { hint.className = "profiling-status-hint cat-empty"; hint.textContent = "No known users found."; }
      if (btn) btn.disabled = true;
      return;
    }

    state.users.forEach(function (userId) {
      var opt = el("option", null, userId);
      opt.value = userId;
      if (list) list.appendChild(opt);
    });
    // Keep the input reflecting the current selection (e.g. after a re-render).
    if (state.selectedUserId != null && input.value !== state.selectedUserId) {
      input.value = state.selectedUserId;
    }
    if (hint) { hint.className = "profiling-status-hint"; hint.textContent = state.users.length + " user(s) found."; }
    if (btn) btn.disabled = !state.selectedUserId;
  }

  /**
   * Renders the command decision timeline category into #prof-timeline-body.
   * UNAVAILABLE: shows .cat-error, retains prior content (Req 2.7).
   * Empty: shows .cat-empty. Populated: one row per entry.
   * (Req 2.3, 2.4, 2.5, 2.6, 2.7, 8.4)
   */
  function renderCommandTimeline(category, doc) {
    doc = doc || document;
    var body = doc.getElementById("prof-timeline-body");
    if (!body) return;

    if (!category || category.status === "UNAVAILABLE") {
      // Retain existing content; overlay an error indicator (Req 2.7).
      var errDiv = el("div", "cat-error", "Command timeline unavailable. Previously loaded data shown above.");
      body.appendChild(errDiv);
      return;
    }

    body.innerHTML = "";

    if (!category.records || category.records.length === 0) {
      body.appendChild(el("p", "cat-empty", "No command records in this window."));
      return;
    }

    var list = el("ul", "prof-list");
    category.records.forEach(function (entry) {
      var li = el("li", "prof-entry");

      // Top line: command text + action badge
      var topLine = el("div", "score-line");
      topLine.appendChild(el("span", "mono prof-cmd", entry.commandText || "(no command)"));
      if (entry.correctiveAction) {
        topLine.appendChild(el("span", actionBadgeClass(entry.correctiveAction), entry.correctiveAction));
      }
      li.appendChild(topLine);

      // Second line: score + reasonCode + profileState + inputOrigin
      var meta = el("div", "prof-entry-meta");
      var scoreSpan = el("span", "score-value", "score " + fmtScore(entry.divergenceScore));
      meta.appendChild(scoreSpan);
      if (entry.reasonCode) {
        meta.appendChild(el("span", "prof-meta-sep", " · "));
        meta.appendChild(el("span", "prof-reason", entry.reasonCode));
      }
      if (entry.profileState) {
        meta.appendChild(el("span", "prof-meta-sep", " · "));
        meta.appendChild(el("span", "prof-state", entry.profileState));
      }
      var origin = entry.inputOrigin || null;
      meta.appendChild(el("span", "prof-meta-sep", " · "));
      meta.appendChild(el("span", "prof-origin", "origin: " + (origin !== null ? origin : "unknown")));
      li.appendChild(meta);

      // Timestamp
      li.appendChild(el("div", "ts", fmtTime(entry.timestamp)));

      list.appendChild(li);
    });
    body.appendChild(list);

    if (category.truncated) {
      body.appendChild(el("p", "cat-truncated",
        "Showing " + category.records.length + " of " + category.totalAvailable + " records. Narrow the window to see all."));
    }
  }

  /**
   * Renders the multilingual commands & intents category into #prof-multilingual-body.
   * Handles UNAVAILABLE, empty, and populated entries showing source text, language tag,
   * English text, and translation-unavailable indicator. (Req 3.2, 3.5, 3.6, 8.4)
   */
  function renderMultilingual(category, doc) {
    doc = doc || document;
    var body = doc.getElementById("prof-multilingual-body");
    if (!body) return;

    body.innerHTML = "";

    if (!category || category.status === "UNAVAILABLE") {
      body.appendChild(el("div", "cat-error", "Multilingual data unavailable for this window."));
      return;
    }

    if (!category.records || category.records.length === 0) {
      body.appendChild(el("p", "cat-empty", "No multilingual intent entries in this window."));
      return;
    }

    var list = el("ul", "prof-list");
    category.records.forEach(function (entry) {
      var li = el("li", "prof-entry");

      // Source text + language tag
      var topLine = el("div", "score-line");
      topLine.appendChild(el("span", "prof-lang-tag", "[" + (entry.sourceLanguageTag || "?") + "]"));
      topLine.appendChild(el("span", "prof-src-text", " " + (entry.sourceText || "(empty)")));
      li.appendChild(topLine);

      // English text adjacent (or translation-unavailable indicator)
      if (entry.translationAvailable === false) {
        li.appendChild(el("div", "prof-translation-unavailable", "⚠ Translation unavailable"));
      } else {
        var engLine = el("div", "prof-eng-text");
        engLine.appendChild(el("span", "prof-lang-tag", "[en]"));
        engLine.appendChild(el("span", null, " " + (entry.englishText || "(no English text)")));
        li.appendChild(engLine);
      }

      li.appendChild(el("div", "ts", fmtTime(entry.timestamp)));
      list.appendChild(li);
    });
    body.appendChild(list);

    if (category.truncated) {
      body.appendChild(el("p", "cat-truncated",
        "Showing " + category.records.length + " of " + category.totalAvailable + " records. Narrow the window to see all."));
    }
  }

  /**
   * Renders the NL assistant queries category into #prof-assist-body.
   * Query text is display-truncated to 2000 chars with an indicator. Shows generated
   * commands in order and full timestamp (date+time). (Req 4.2, 4.3, 4.4, 8.4)
   */
  function renderAssistQueries(category, doc) {
    doc = doc || document;
    var body = doc.getElementById("prof-assist-body");
    if (!body) return;

    body.innerHTML = "";

    if (!category || category.status === "UNAVAILABLE") {
      body.appendChild(el("div", "cat-error", "Assistant query data unavailable for this window."));
      return;
    }

    if (!category.records || category.records.length === 0) {
      body.appendChild(el("p", "cat-empty", "No assistant queries in this window."));
      return;
    }

    var list = el("ul", "prof-list");
    category.records.forEach(function (entry) {
      var li = el("li", "prof-entry");

      // Query text — truncate to 2000 chars, show indicator if truncated
      var truncated = truncateQueryText(entry.queryEnglish || "", 2000);
      var queryDiv = el("div", "prof-query-text", truncated.text);
      if (truncated.wasTruncated) {
        var truncIndicator = el("span", "cat-truncated", " [truncated — original query longer than 2000 chars]");
        queryDiv.appendChild(truncIndicator);
      }
      li.appendChild(queryDiv);

      // Generated commands list (in order)
      var cmds = entry.generatedCommands;
      if (cmds && cmds.length > 0) {
        var cmdLabel = el("div", "prof-entry-meta", "Generated commands:");
        li.appendChild(cmdLabel);
        var cmdList = el("ol", "prof-cmd-list");
        cmds.forEach(function (cmd) {
          cmdList.appendChild(el("li", "mono", cmd));
        });
        li.appendChild(cmdList);
      }

      // Timestamp: date + time (Req 4.2)
      var d = new Date(entry.timestamp);
      var dtStr = d.toLocaleDateString() + " " + d.toLocaleTimeString();
      li.appendChild(el("div", "ts", dtStr));

      list.appendChild(li);
    });
    body.appendChild(list);

    if (category.truncated) {
      body.appendChild(el("p", "cat-truncated",
        "Showing " + category.records.length + " of " + category.totalAvailable + " records. Narrow the window to see all."));
    }
  }

  /**
   * Renders the translation records category into #prof-translations-body.
   * Shows source/translated text, language tags, kind, and a degraded indicator
   * when the translation fell back to English (Req 5.2, 5.4, 5.5, 8.4).
   */
  function renderTranslations(category, doc) {
    doc = doc || document;
    var body = doc.getElementById("prof-translations-body");
    if (!body) return;

    body.innerHTML = "";

    if (!category || category.status === "UNAVAILABLE") {
      body.appendChild(el("div", "cat-error", "Translation record data unavailable for this window."));
      return;
    }

    if (!category.records || category.records.length === 0) {
      body.appendChild(el("p", "cat-empty", "No attributed translation records in this window."));
      return;
    }

    var list = el("ul", "prof-list");
    category.records.forEach(function (entry) {
      var li = el("li", "prof-entry" + (entry.degraded ? " prof-entry-degraded" : ""));

      // Top line: src lang → tgt lang + kind badge
      var topLine = el("div", "score-line");
      topLine.appendChild(el("span", "prof-lang-tag", (entry.sourceLanguageTag || "?") + " → " + (entry.targetLanguageTag || "?")));
      if (entry.kind) {
        topLine.appendChild(el("span", "badge", " " + entry.kind));
      }
      if (entry.degraded) {
        topLine.appendChild(el("span", "badge badge-ask", " degraded"));
      }
      li.appendChild(topLine);

      // Source text
      li.appendChild(el("div", "prof-src-text", entry.sourceText || "(empty)"));

      // Translated text (only if different from source and available)
      if (entry.translatedText && entry.translatedText !== entry.sourceText) {
        var translLine = el("div", "prof-eng-text", entry.translatedText);
        li.appendChild(translLine);
      }

      li.appendChild(el("div", "ts", fmtTime(entry.timestamp)));
      list.appendChild(li);
    });
    body.appendChild(list);

    if (category.truncated) {
      body.appendChild(el("p", "cat-truncated",
        "Showing " + category.records.length + " of " + category.totalAvailable + " records. Narrow the window to see all."));
    }
  }

  /**
   * Renders the behavioral profile summary into #prof-behavioral-body.
   * When present=false: empty-state. When present: shows state, eventCount,
   * top-10 vocabulary entries, top-10 sequence stats. (Req 6.2, 6.4, 6.5, 8.4)
   */
  function renderBehavioralProfile(profile, doc) {
    doc = doc || document;
    var body = doc.getElementById("prof-behavioral-body");
    if (!body) return;

    body.innerHTML = "";

    if (!profile || !profile.present) {
      body.appendChild(el("p", "cat-empty", "No behavioral profile recorded for this user yet."));
      return;
    }

    // State + event count
    var summaryDiv = el("div", "prof-entry-meta");
    summaryDiv.appendChild(el("span", "prof-state", "State: " + (profile.state || "?")));
    summaryDiv.appendChild(el("span", "prof-meta-sep", " · "));
    summaryDiv.appendChild(el("span", null, "Events: " + (profile.eventCount != null ? profile.eventCount : "?")));
    body.appendChild(summaryDiv);

    // Top-10 vocabulary
    var vocabEntries = topEntries(profile.vocabulary, 10);
    if (vocabEntries.length > 0) {
      body.appendChild(el("h3", "prof-section-label", "Top Commands (vocabulary)"));
      var vocabList = el("ul", "prof-list prof-topk-list");
      vocabEntries.forEach(function (entry) {
        var li = el("li", "prof-topk-entry");
        li.appendChild(el("span", "mono", entry.key));
        li.appendChild(el("span", "prof-count", " × " + entry.count));
        vocabList.appendChild(li);
      });
      body.appendChild(vocabList);
    } else {
      body.appendChild(el("p", "cat-empty", "No vocabulary data."));
    }

    // Top-10 sequence stats
    var seqEntries = topEntries(profile.sequenceStats, 10);
    if (seqEntries.length > 0) {
      body.appendChild(el("h3", "prof-section-label", "Top Command Sequences"));
      var seqList = el("ul", "prof-list prof-topk-list");
      seqEntries.forEach(function (entry) {
        var li = el("li", "prof-topk-entry");
        li.appendChild(el("span", "mono", entry.key));
        li.appendChild(el("span", "prof-count", " × " + entry.count));
        seqList.appendChild(li);
      });
      body.appendChild(seqList);
    } else {
      body.appendChild(el("p", "cat-empty", "No sequence data."));
    }
  }

  /**
   * Returns the CSS badge class for a RiskStats risk band (LOW/ELEVATED/HIGH/NONE).
   */
  function riskBandClass(band) {
    switch (band) {
      case "LOW": return "risk-badge risk-low";
      case "ELEVATED": return "risk-badge risk-elevated";
      case "HIGH": return "risk-badge risk-high";
      default: return "risk-badge risk-none";
    }
  }

  /**
   * Maps a divergence score in [0,1] to a colour, matching the live risk-timeline palette:
   * green (allow) < 0.4, amber (ask) < 0.8, red (block) otherwise.
   */
  function riskScoreColor(score) {
    if (typeof score !== "number" || isNaN(score)) return "#8ea0bd";
    if (score < 0.4) return "#35c47a";
    if (score < 0.8) return "#f2b24a";
    return "#ef5b6b";
  }

  /**
   * Renders the Risk Overview panel (#prof-risk-body): an "average command score" badge with
   * ALLOW/ASK/BLOCK counts, and a 30-day daily-average risk trend graph drawn on a canvas.
   *
   * @param {object} riskStats the RiskStats payload from the profile response
   * @param {object} doc        injectable document (defaults to global document)
   */
  function renderRiskStats(riskStats, doc) {
    doc = doc || document;
    var body = doc.getElementById("prof-risk-body");
    if (!body) return;

    body.innerHTML = "";

    if (!riskStats) {
      body.appendChild(el("div", "cat-error", "Risk statistics unavailable for this user."));
      return;
    }

    if (!riskStats.present) {
      body.appendChild(el("p", "cat-empty", "No scored commands in the last 30 days."));
      // Still draw the empty axis so the panel looks intentional, if a canvas is present.
      drawRiskTrend(riskStats.daily || [], doc);
      return;
    }

    // --- Summary row: average-score badge + action counts -------------------
    var summary = el("div", "risk-summary");

    var avg = typeof riskStats.averageScore === "number" ? riskStats.averageScore : 0;
    var badge = el("div", riskBandClass(riskStats.riskBand));
    badge.appendChild(el("span", "risk-badge-value", fmtScore(avg)));
    badge.appendChild(el("span", "risk-badge-label", "avg score"));
    summary.appendChild(badge);

    var meta = el("div", "risk-meta");
    meta.appendChild(el("div", "risk-band-line", "Risk band: " + (riskStats.riskBand || "?")));
    meta.appendChild(el("div", "risk-count-line",
      (riskStats.commandCount || 0) + " commands over last " + (riskStats.windowDays || 30) + " days"));
    var counts = el("div", "risk-action-counts");
    counts.appendChild(el("span", "badge badge-allow", "ALLOW " + (riskStats.allowCount || 0)));
    counts.appendChild(el("span", "badge badge-ask", "ASK " + (riskStats.askCount || 0)));
    counts.appendChild(el("span", "badge badge-block", "BLOCK " + (riskStats.blockCount || 0)));
    meta.appendChild(counts);
    summary.appendChild(meta);

    body.appendChild(summary);

    // --- 30-day trend graph -------------------------------------------------
    var chartWrap = el("div", "risk-chart-wrap");
    var canvas = doc.createElement("canvas");
    canvas.setAttribute("id", "prof-risk-canvas");
    canvas.setAttribute("width", "900");
    canvas.setAttribute("height", "160");
    canvas.className = "risk-chart";
    chartWrap.appendChild(canvas);
    body.appendChild(chartWrap);
    body.appendChild(el("p", "risk-chart-caption", "Daily average command score — last 30 days (green ≤ 0.4, amber ≤ 0.8, red > 0.8)"));

    drawRiskTrend(riskStats.daily || [], doc);
  }

  /**
   * Draws the 30-day daily-average trend as vertical bars on #prof-risk-canvas. Bars are coloured
   * by score band; empty days render as a faint baseline tick. Skipped when canvas is unavailable
   * (e.g. in the Node test DOM), so the renderer remains headless-safe.
   */
  function drawRiskTrend(daily, doc) {
    doc = doc || document;
    var canvas = doc.getElementById("prof-risk-canvas");
    if (!canvas || typeof canvas.getContext !== "function") return;
    var ctx = canvas.getContext("2d");
    if (!ctx) return;

    var w = canvas.width, h = canvas.height;
    ctx.clearRect(0, 0, w, h);

    var pad = 6;
    var plotH = h - pad * 2;

    // Threshold guide lines at 0.4 and 0.8 (ask/block).
    ctx.strokeStyle = "rgba(255,255,255,0.08)";
    ctx.lineWidth = 1;
    [0.4, 0.8].forEach(function (t) {
      var y = h - pad - t * plotH;
      ctx.beginPath();
      ctx.moveTo(pad, y);
      ctx.lineTo(w - pad, y);
      ctx.stroke();
    });

    var n = daily.length;
    if (n === 0) return;
    var slot = (w - pad * 2) / n;
    var barW = Math.max(2, slot * 0.6);

    daily.forEach(function (pt, i) {
      var x = pad + i * slot + (slot - barW) / 2;
      if (!pt || pt.count === 0) {
        // faint baseline tick for empty days
        ctx.fillStyle = "rgba(142,160,189,0.20)";
        ctx.fillRect(x, h - pad - 1, barW, 1);
        return;
      }
      var score = clamp01(pt.averageScore);
      var barH = Math.max(1, score * plotH);
      ctx.fillStyle = riskScoreColor(pt.averageScore);
      ctx.fillRect(x, h - pad - barH, barW, barH);
    });
  }

  /**
   * Orchestrates all profiling renderers for the current state.
   * Renders window bounds, handles profileStatus loading/error states,
   * whole-profile failure (profileLoadFailed), and per-category rendering.
   * (Req 1.4–1.7, 10.5, 10.6, 7.6)
   */
  function renderProfileView(state, doc) {
    doc = doc || document;

    renderUserSelector(state, doc);

    var noUserMsg = doc.getElementById("profiling-no-user-msg");
    var windowBounds = doc.getElementById("profiling-window-bounds");

    // No user selected yet — show prompt and clear all category panels
    if (!state.selectedUserId) {
      if (noUserMsg) noUserMsg.className = (noUserMsg.className || "").replace("view-hidden", "").trim();
      if (windowBounds) windowBounds.className = "profiling-window-bounds view-hidden";
      renderRiskStats(null, doc);
      renderCommandTimeline(null, doc);
      renderMultilingual(null, doc);
      renderAssistQueries(null, doc);
      renderTranslations(null, doc);
      renderBehavioralProfile(null, doc);
      return;
    }

    if (noUserMsg) {
      var cls = noUserMsg.className || "";
      if (cls.indexOf("view-hidden") === -1) noUserMsg.className = cls + " view-hidden";
    }

    // profileStatus: loading
    if (state.profileStatus === "loading") {
      if (windowBounds) windowBounds.className = "profiling-window-bounds view-hidden";
      var bodies = [
        doc.getElementById("prof-risk-body"),
        doc.getElementById("prof-timeline-body"),
        doc.getElementById("prof-multilingual-body"),
        doc.getElementById("prof-assist-body"),
        doc.getElementById("prof-translations-body"),
        doc.getElementById("prof-behavioral-body")
      ];
      bodies.forEach(function (b) {
        if (b) { b.innerHTML = ""; b.appendChild(el("p", "empty", "Loading…")); }
      });
      return;
    }

    // profileStatus: error (load failed before we even got a response)
    if (state.profileStatus === "error") {
      if (windowBounds) windowBounds.className = "profiling-window-bounds view-hidden";
      var errBodies = [
        doc.getElementById("prof-risk-body"),
        doc.getElementById("prof-timeline-body"),
        doc.getElementById("prof-multilingual-body"),
        doc.getElementById("prof-assist-body"),
        doc.getElementById("prof-translations-body"),
        doc.getElementById("prof-behavioral-body")
      ];
      errBodies.forEach(function (b) {
        if (b) {
          b.innerHTML = "";
          b.appendChild(el("div", "cat-error", "Failed to load profile. Please try again."));
        }
      });
      return;
    }

    // profileStatus: ok or idle with a profile loaded
    var profile = state.profile;
    if (!profile) return;

    // Window bounds (Req 7.6)
    if (windowBounds && profile.windowStart && profile.windowEnd) {
      windowBounds.className = "profiling-window-bounds";
      var startEl = doc.getElementById("profiling-window-start");
      var endEl = doc.getElementById("profiling-window-end");
      var fmtStr = formatWindow(profile.windowStart, profile.windowEnd);
      var parts = fmtStr.split(" \u2014 ");
      if (startEl) startEl.textContent = parts[0] || "";
      if (endEl) endEl.textContent = parts[1] || "";
    }

    // Whole-profile failure — visually distinct from an empty-but-successful profile (Req 10.6)
    if (profile.profileLoadFailed) {
      var allBodies = [
        doc.getElementById("prof-risk-body"),
        doc.getElementById("prof-timeline-body"),
        doc.getElementById("prof-multilingual-body"),
        doc.getElementById("prof-assist-body"),
        doc.getElementById("prof-translations-body"),
        doc.getElementById("prof-behavioral-body")
      ];
      allBodies.forEach(function (b) {
        if (b) {
          b.innerHTML = "";
          b.appendChild(el("div", "cat-error prof-whole-failure",
            "Profile data could not be loaded at this time. All data sources are unavailable. Please try again later."));
        }
      });
      return;
    }

    // Per-category rendering — each independent so a failure in one doesn't stop others (Req 10.5)
    renderRiskStats(profile.riskStats, doc);
    renderCommandTimeline(profile.commandTimeline, doc);
    renderMultilingual(profile.multilingual, doc);
    renderAssistQueries(profile.assistQueries, doc);
    renderTranslations(profile.translations, doc);
    renderBehavioralProfile(profile.behavioralProfile, doc);
  }

  // ---------------------------------------------------------------------------
  // Wiring (browser only)
  // ---------------------------------------------------------------------------

  function setConnected(connected, doc) {
    doc = doc || document;
    var dot = doc.getElementById("conn-dot");
    var label = doc.getElementById("conn-label");
    if (dot) dot.className = "dot " + (connected ? "dot-on" : "dot-off");
    if (label) label.textContent = connected ? "live" : "reconnecting…";
  }

  /** Opens the SSE channel and wires named events to state updates (Req 12.6). */
  function connectStream(state, opts) {
    opts = opts || {};
    var Source = opts.EventSource || (typeof EventSource !== "undefined" ? EventSource : null);
    if (!Source) return null;
    var source = new Source(opts.url || "/api/stream");
    var doc = opts.document || document;

    var onEnvelope = function (evt) {
      try {
        var envelope = JSON.parse(evt.data);
        handleLiveEvent(state, envelope);
        renderAll(state, doc);
      } catch (e) {
        // Ignore malformed frames; keep the stream alive.
      }
    };

    ["SESSION", "SCORE", "ALERT"].forEach(function (name) {
      source.addEventListener(name, onEnvelope);
    });
    var everOpened = false;
    source.onopen = function () { everOpened = true; setConnected(true, doc); };
    // Only flip to disconnected after we have been connected at least once — this suppresses
    // the transient error the browser fires while the initial SSE handshake is negotiating,
    // which would otherwise overwrite the optimistic "live" label in the HTML.
    source.onerror = function () { if (everOpened) setConnected(false, doc); };
    return source;
  }

  /**
   * Wires the User Profiling view controls:
   *  - #nav-live / #nav-profiling   → setView + nav .active class (Req 11.2, 11.3, 11.4)
   *  - First entry to profiling      → loadUsers + renderUserSelector (Req 1.1, 1.7)
   *  - #profiling-user-select change → enable/disable load btn (Req 1.5)
   *  - #profiling-days change        → update state.windowDays (Req 7.2)
   *  - #profiling-full change        → update state.full (Req 7.2)
   *  - #profiling-load-btn click     → loadProfile + renderProfileView; on rejection
   *                                    shows error without clearing the panel (Req 11.5, 2.7)
   *
   * The live EventSource is NEVER touched here — setView only toggles visibility. (Req 11.4)
   *
   * @param {object} profileState  the profile-screen state object (from createProfileState)
   * @param {object} doc           injectable document (defaults to global document)
   * @param {object} [opts]        injectable fetch / AbortController for tests
   */
  function wireProfilingControls(profileState, doc, opts) {
    doc = doc || document;
    opts = opts || {};

    // -----------------------------------------------------------------------
    // Internal helpers
    // -----------------------------------------------------------------------

    /** Toggle visibility of the two main views and update aria/active classes. */
    function applyView(view) {
      profileState = setView(profileState, view);

      var liveView = doc.getElementById("live-view");
      var profView = doc.getElementById("profiling-view");
      var navLive = doc.getElementById("nav-live");
      var navProf = doc.getElementById("nav-profiling");

      if (liveView) liveView.className = liveView.className.replace(/\bview-hidden\b/, "").trim();
      if (profView) profView.className = profView.className.replace(/\bview-hidden\b/, "").trim();

      if (view === "profiling") {
        if (liveView) liveView.className = (liveView.className + " view-hidden").trim();
      } else {
        if (profView) profView.className = (profView.className + " view-hidden").trim();
      }

      if (navLive) {
        navLive.className = navLive.className.replace(/\bactive\b/, "").trim();
        if (view === "live") navLive.className = (navLive.className + " active").trim();
        navLive.setAttribute("aria-pressed", String(view === "live"));
      }
      if (navProf) {
        navProf.className = navProf.className.replace(/\bactive\b/, "").trim();
        if (view === "profiling") navProf.className = (navProf.className + " active").trim();
        navProf.setAttribute("aria-pressed", String(view === "profiling"));
      }
    }

    /** Fetch the known-user list and re-render the selector. */
    function fetchUsers() {
      profileState.usersStatus = "loading";
      renderUserSelector(profileState, doc);
      loadUsers(opts).then(function (data) {
        profileState.users = sortUsersCaseInsensitive((data && data.users) || []);
        profileState.usersStatus = "ok";
        renderUserSelector(profileState, doc);
      }).catch(function () {
        profileState.usersStatus = "error";
        renderUserSelector(profileState, doc);
      });
    }

    /** Fire a profile load for the currently selected user and window. */
    function fetchProfile() {
      var userId = profileState.selectedUserId;
      if (!userId) return;

      // Snapshot the previous profile so we can restore it on failure (Req 2.7, 11.5).
      var previousProfile = profileState.profile;
      profileState.profileStatus = "loading";
      renderProfileView(profileState, doc);

      loadProfile(userId, { days: profileState.windowDays, full: profileState.full }, opts)
        .then(function (data) {
          profileState.profile = data;
          profileState.profileStatus = "ok";
          renderProfileView(profileState, doc);
        })
        .catch(function () {
          // Retain the previously loaded profile; only overlay an error indication (Req 11.5).
          profileState.profile = previousProfile;
          profileState.profileStatus = "error";
          renderProfileView(profileState, doc);
        });
    }

    // -----------------------------------------------------------------------
    // Nav tab wiring (Req 11.2, 11.3, 11.4)
    // -----------------------------------------------------------------------
    var usersLoaded = false;

    var navLiveBtn = doc.getElementById("nav-live");
    if (navLiveBtn) {
      navLiveBtn.addEventListener("click", function () {
        applyView("live");
      });
    }

    var navProfBtn = doc.getElementById("nav-profiling");
    if (navProfBtn) {
      navProfBtn.addEventListener("click", function () {
        applyView("profiling");
        // Load the user list the first time we enter the profiling view (Req 1.1).
        if (!usersLoaded) {
          usersLoaded = true;
          fetchUsers();
        }
      });
    }

    // -----------------------------------------------------------------------
    // User search input → resolve to a known user + enable/disable load btn (Req 1.5)
    // The input is a type-to-filter search box. Its typed value is matched
    // case-insensitively against the known-user list; Load is enabled only on an exact match,
    // and a live "no match" hint guides the operator otherwise.
    // -----------------------------------------------------------------------
    var userSelect = doc.getElementById("profiling-user-select");
    if (userSelect) {
      var resolveUser = function () {
        var typed = (userSelect.value || "").trim();
        var loadBtn = doc.getElementById("profiling-load-btn");
        var statusEl = doc.getElementById("profiling-users-status");

        if (typed === "") {
          profileState.selectedUserId = null;
          if (loadBtn) loadBtn.disabled = true;
          if (statusEl) {
            statusEl.className = "profiling-status-hint";
            statusEl.textContent = (profileState.users || []).length + " user(s) found.";
          }
          return;
        }

        // Case-insensitive exact match against the known-user list.
        var lower = typed.toLowerCase();
        var match = null;
        (profileState.users || []).forEach(function (u) {
          if (u.toLowerCase() === lower) match = u;
        });

        profileState.selectedUserId = match;
        if (loadBtn) loadBtn.disabled = !match;
        if (statusEl) {
          if (match) {
            statusEl.className = "profiling-status-hint status-ok";
            statusEl.textContent = "Ready to load " + match + ".";
          } else {
            statusEl.className = "profiling-status-hint cat-error";
            statusEl.textContent = "No user matches \"" + typed + "\".";
          }
        }
      };
      // Fire on every keystroke and on change/pick from the datalist.
      userSelect.addEventListener("input", resolveUser);
      userSelect.addEventListener("change", resolveUser);
    }

    // -----------------------------------------------------------------------
    // Window control changes → update state (Req 7.2)
    // -----------------------------------------------------------------------
    var daysInput = doc.getElementById("profiling-days");
    if (daysInput) {
      daysInput.addEventListener("change", function () {
        var parsed = parseInt(daysInput.value, 10);
        if (!isNaN(parsed) && parsed >= 1 && parsed <= 365) {
          profileState.windowDays = parsed;
        }
      });
    }

    var fullCheckbox = doc.getElementById("profiling-full");
    if (fullCheckbox) {
      fullCheckbox.addEventListener("change", function () {
        profileState.full = fullCheckbox.checked;
      });
    }

    // -----------------------------------------------------------------------
    // Load profile button (Req 11.3, 11.5)
    // -----------------------------------------------------------------------
    var loadBtn = doc.getElementById("profiling-load-btn");
    if (loadBtn) {
      loadBtn.addEventListener("click", function () {
        fetchProfile();
      });
    }
  }

  function wireForms(state, doc) {
    doc = doc || document;

    var thresholdForm = doc.getElementById("threshold-form");
    if (thresholdForm) {
      thresholdForm.addEventListener("submit", function (e) {
        e.preventDefault();
        var status = doc.getElementById("threshold-status");
        var update = readThresholdForm(doc);
        updateThresholds(update).then(function (cfg) {
          if (status) {
            status.className = "threshold-status status-ok";
            status.textContent = "Applied. Active version " + (cfg && cfg.version != null ? cfg.version : "?") + ".";
          }
        }).catch(function (err) {
          if (status) {
            status.className = "threshold-status status-err";
            status.textContent = err.message || "Update rejected.";
          }
        });
      });
    }

    var historyForm = doc.getElementById("history-form");
    if (historyForm) {
      historyForm.addEventListener("submit", function (e) {
        e.preventDefault();
        var userId = doc.getElementById("history-user").value;
        var from = doc.getElementById("history-from").value;
        var to = doc.getElementById("history-to").value;
        loadHistory(userId, from, to).then(function (records) {
          renderHistory(records, doc);
        }).catch(function () {
          renderHistory([], doc);
        });
      });
    }
  }

  /** Boots the dashboard once the DOM is ready. */
  function init(opts) {
    opts = opts || {};
    var state = opts.state || createState();
    var profileState = opts.profileState || createProfileState();
    var doc = opts.document || document;
    // Initial paint (empty), then hydrate from persisted MongoDB state (last 3 days) so the
    // dashboard is populated on a fresh page load, then re-render and open the live channel.
    renderAll(state, doc);
    wireForms(state, doc);
    wireProfilingControls(profileState, doc, opts);
    if (opts.hydrate !== false) {
      loadBootstrap(state, opts).then(function () {
        renderAll(state, doc);
      }).catch(function () {
        // Bootstrap is best-effort: an empty/failed hydration still leaves a working live view.
      });
    }
    connectStream(state, opts);
    return state;
  }

  var api = {
    ACTION: ACTION,
    SESSION_STATUS: SESSION_STATUS,
    createState: createState,
    // User Profiling — pure state/derivation functions (task 6.3)
    createProfileState: createProfileState,
    setView: setView,
    sortUsersCaseInsensitive: sortUsersCaseInsensitive,
    truncateQueryText: truncateQueryText,
    topEntries: topEntries,
    formatWindow: formatWindow,
    applySessionEvent: applySessionEvent,
    applyScoreEvent: applyScoreEvent,
    applyAlertEvent: applyAlertEvent,
    handleLiveEvent: handleLiveEvent,
    activeSessions: activeSessions,
    scoresForSession: scoresForSession,
    flaggedScores: flaggedScores,
    isFlagged: isFlagged,
    removePendingAsk: removePendingAsk,
    renderSessions: renderSessions,
    renderTimeline: renderTimeline,
    renderDivergence: renderDivergence,
    renderExplanations: renderExplanations,
    renderPendingAsks: renderPendingAsks,
    renderAlerts: renderAlerts,
    renderHistory: renderHistory,
    renderAll: renderAll,
    // User Profiling renderers (task 6.5)
    renderUserSelector: renderUserSelector,
    renderCommandTimeline: renderCommandTimeline,
    renderMultilingual: renderMultilingual,
    renderAssistQueries: renderAssistQueries,
    renderTranslations: renderTranslations,
    renderBehavioralProfile: renderBehavioralProfile,
    renderRiskStats: renderRiskStats,
    renderProfileView: renderProfileView,
    resolveAsk: resolveAsk,
    updateThresholds: updateThresholds,
    readThresholdForm: readThresholdForm,
    loadHistory: loadHistory,
    loadUsers: loadUsers,
    loadProfile: loadProfile,
    loadBootstrap: loadBootstrap,
    connectStream: connectStream,
    wireForms: wireForms,
    wireProfilingControls: wireProfilingControls,
    setConnected: setConnected,
    init: init
  };

  // Expose for browser (and browser-based tests).
  root.IntentGuard = api;

  // Auto-boot in a real browser document (skip when loaded as a Node module for tests).
  if (typeof document !== "undefined" && document.addEventListener && !(typeof module !== "undefined" && module.exports)) {
    if (document.readyState === "loading") {
      document.addEventListener("DOMContentLoaded", function () { init(); });
    } else {
      init();
    }
  }

  // Expose for Node-based tests (task 16.2).
  if (typeof module !== "undefined" && module.exports) {
    module.exports = api;
  }
})(typeof window !== "undefined" ? window : this);
