# Control Tower front-end tests (task 16.2)

UI interaction / snapshot tests for the IntentGuard Control_Tower front-end
(`src/main/resources/static/app.js`).

These tests are **self-contained and zero-dependency**: they use Node's built-in
test runner (`node:test`) plus a tiny fake DOM (`fake-dom.js`). No `npm install`
and no network access are required. They are intentionally **not** wired into the
Maven build, so they do not affect the Java build or test run.

## Requirements coverage (Requirement 12)

| Test | Requirement |
| --- | --- |
| active sessions rendering, CLOSED filtered out | 12.1 |
| risk timeline of divergence scores | 12.2 |
| intent-vs-action divergence for a selected session | 12.3 |
| explanation display on flagged events | 12.4 |
| ask-state Confirm/Block controls invoking `resolveAsk` | 12.5 |

Plus pure-state-transition checks and an end-to-end `handleLiveEvent` + `renderAll` flow.

## Running

Requires Node.js (tested on v24). From this directory:

```bash
node --test
```

Or from the repository root, point the runner at the test file(s):

```bash
node --test src/test/js/control-tower.test.js
```
