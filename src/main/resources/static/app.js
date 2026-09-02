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
    var doc = opts.document || document;
    // Initial paint (empty), then hydrate from persisted MongoDB state (last 3 days) so the
    // dashboard is populated on a fresh page load, then re-render and open the live channel.
    renderAll(state, doc);
    wireForms(state, doc);
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
    resolveAsk: resolveAsk,
    updateThresholds: updateThresholds,
    readThresholdForm: readThresholdForm,
    loadHistory: loadHistory,
    loadBootstrap: loadBootstrap,
    connectStream: connectStream,
    wireForms: wireForms,
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
