# Control Tower web frontend

Static dashboard for IntentGuard, served by Spring Boot from `src/main/resources/static/`
at the application root (`http://<host>:<port>/`). No build tooling is required — plain
HTML/CSS/JS.

## Files

- `index.html` — dashboard layout (panels for sessions, timeline, divergence, asks,
  explanations, alerts, thresholds, history).
- `styles.css` — styling.
- `app.js` — controller. Consumes the SSE live channel and renders the views.

## What it shows (Requirement 12)

- **Active Intent Sessions** (12.1) — declared intent + current user, from `SESSION` events.
- **Risk timeline** (12.2) — divergence scores over time (canvas sparkline + list), from `SCORE` events.
- **Intent vs action** (12.3) — scored actions for the selected session's user vs its declared intent.
- **Flagged event explanations** (12.4) — explanations for `ask`/`block` scores.
- **Pending confirmations** (12.5) — confirm/block controls that `POST /api/events/{eventId}/resolve`.
- **Threshold configuration** — `PUT /api/thresholds`.
- **Audit history** — `GET /api/history?userId=&from=&to=`.

## Live channel

`app.js` opens `new EventSource('/api/stream')` and listens for the named SSE events
`SESSION` / `SCORE` / `ALERT`, each carrying the `LiveEvent` envelope `{type, timestamp, payload}`.

## Testability (for task 16.2)

`app.js` is written as an IIFE that exposes its API both on `window.IntentGuard` (browser)
and via `module.exports` (Node). Pure state-transition functions
(`applySessionEvent`, `applyScoreEvent`, `applyAlertEvent`, `handleLiveEvent`) hold no DOM
references; `render*` functions accept an optional `document` argument for jsdom-based tests;
backend calls (`resolveAsk`, `updateThresholds`, `loadHistory`) accept an injectable `fetch`
via an `opts` argument. No Node build is wired into Maven.

> **Security note:** the Control_Tower REST/SSE endpoints are unauthenticated in the
> prototype (see `ControlTowerController`). Do not expose this dashboard on an untrusted
> network as-is.
