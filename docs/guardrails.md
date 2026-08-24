# IntentGuard Guardrails

This document inventories the guardrails IntentGuard **already implements** and the
guardrails that **can be added**, with the hook point each maps to in the current
architecture (the `DecisionEngine` ordered-rule chain, a scoring component, the
watchdog, or persistence).

> Core principle: hard, deterministic rules should always beat a soft divergence
> score, and fail-safe behavior is default-deny when the engine cannot decide.

## Guardrail composition (precedence)

Guardrails stack so a hard rule always wins over a soft score:

```
tamper override
  → command policy (DENY)
  → blast-radius / protected-target
  → threshold map (divergence score)
  → learning clamp
  → agent containment
  → dual-control / ask-timeout
```

---

## 1. Already implemented

| Guardrail | What it does | Hook point |
|---|---|---|
| **Tamper override** | Any command touching engine config / process / datastore is forced to the max score → block | `DecisionEngine` (first rule), `TamperClassifier` |
| **Agent containment** | An `AGENT` actor with no open human Intent_Session is unauthorized-by-default (at least ask) | `DecisionEngine` |
| **Agent risk markers** | Outbound connection / secret-file access / unrelated privilege escalation raise the score | Scoring pipeline (`AgentRiskAdjuster`) |
| **Learning clamp** | Immature profiles never hard-block (block → ask while LEARNING) | `DecisionEngine` |
| **Ask-timeout fail-safe** | An unconfirmed ask becomes a block after the confirmation timeout | `DecisionEngine.onAskTimeout` |
| **Decision-budget fail-safe** | Block when a decision can't be reached within the 2s budget or on error | `InteractiveSignalIngestor` |
| **Monitoring-gap watchdog** | High-risk alert when the audit feed / hook liveness goes silent past the timeout | `MonitoringGapWatchdog` |
| **Self-defense over control requests** | Reject stop/pause/reconfigure from unprivileged users; record the attempt | `SelfDefenseGuard` |
| **Session-anomaly (hijack) detection** | Sustained behavioral-fingerprint deviation raises a recorded alert with evidence | `SessionAnomalyDetector` |
| **Config validation + last-known-good** | Reject invalid threshold updates, retain the previous config | `ThresholdConfigurationService` |
| **Snapshot / undo (stretch)** | Capture pre-execution state for ask/block events; restore-with-audit on admin undo | `SnapshotService` |
| **Inferred-intent (stretch)** | Score against an inferred goal when no session is open, at a strictly lower weight | `InferredIntentService` + `SemanticInconsistencyComponent` |

---

## 2. Deterministic policy guardrails (admin / leadership authored)

The highest-value addition — a `CommandPolicy` layer evaluated **before** the
threshold map, mirroring the versioned/hot-reloadable `ThresholdConfiguration`.

| Guardrail | What it does |
|---|---|
| **Denylist patterns** | Force block on known-dangerous patterns: `rm -rf /`, `mkfs`, `dd if=`, fork bomb, `chmod -R 777`, `DROP TABLE`, `kubectl delete ns`, etc. |
| **Allowlist / confirm-list** | Patterns that require confirmation (or are explicitly whitelisted) regardless of score |
| **Scoped rules** | Rules scoped by user, group, cwd/repo, or actor type (e.g., agents may never run `curl … \| sh`) |

Design notes: ordered rules with `pattern` (glob/regex over normalized command +
args), optional scope, and action (`DENY` / `REQUIRE_CONFIRM` / `ALLOW`). First
matching `DENY` forces block (like the tamper override); persist every hit to
`Audit_History` with the matched rule id and name it in the explanation. A hard
`DENY` must win over a low divergence score, and the learning clamp must **not**
soften it.

---

## 3. Target / blast-radius guardrails

| Guardrail | What it does |
|---|---|
| **Protected paths** | Reads/writes to secrets, `~/.ssh`, `/etc`, cloud-credential files, prod config → escalate or block |
| **Protected hosts / resources** | Production DBs, specific clusters, or tagged cloud resources → escalate |
| **Mass-operation limits** | Recursive deletes, wildcard/glob ops, bulk updates above a threshold → require confirmation (blast-radius cap) |
| **Destructive-verb detection** | A scoring component that recognizes irreversible operations and raises the floor |

---

## 4. Velocity / rate guardrails

| Guardrail | What it does |
|---|---|
| **Command-rate limiting** | Per user/agent limits; catch runaway agents or scripted abuse |
| **Burst / timing anomaly** | Sudden spike vs. the profile's `hourHistogram` / `meanInterCommandMs` |
| **Velocity-triggered session anomaly** | Extend `SessionAnomalyDetector` to fire on rate thresholds, not just deviation |

---

## 5. Data-exfiltration guardrails

| Guardrail | What it does |
|---|---|
| **Egress control** | Outbound connections to unknown/unapproved destinations → escalate |
| **Secret-access + egress combo** | Reading a credential *and* opening a network connection in the same session (first-class exfil correlation, beyond per-command markers) |
| **Canary tokens** | Planted secrets that, if accessed, force an immediate block + high-risk alert |

---

## 6. Time / context guardrails

| Guardrail | What it does |
|---|---|
| **Maintenance windows** | Allow risky ops only during approved windows; off-hours raises the floor |
| **Context-mismatch rules** | Extend the `Context_Mismatch` component with explicit "wrong directory/repo/env for this command class" rules |
| **Geo / source restrictions** | Flag sessions originating from unexpected locations or hosts |

---

## 7. Authorization / dual-control guardrails

| Guardrail | What it does |
|---|---|
| **Break-glass / four-eyes** | High-risk or policy-flagged commands require a second approver in the Control_Tower before proceeding |
| **Step-up confirmation** | Require re-authentication (not just a click) for block-range actions |
| **Per-agent capability scoping** | An agent inherits only a subset of its principal's intent envelope |

---

## 8. Semantic / LLM guardrails

| Guardrail | What it does |
|---|---|
| **Prompt-injection heuristics** | Detect instruction-injection patterns in the command context that precede off-intent actions |
| **Intent-drift detection** | Track how far a session's actions wander from the declared intent over time, not just per-command |
| **LLM output validation** | Clamp/schema-check semantic scores (done) and treat malformed responses as errors, not signals (done) |

---

## 9. Operational / safety guardrails (present; worth hardening)

| Guardrail | What it does |
|---|---|
| **Default-deny when engine down** | Fail-closed if the reference monitor is unavailable |
| **Privilege-separation enforcement** | Verify the engine's dedicated service-account isolation at startup (design requirement) |
| **Policy validation + last-known-good** | Extend the threshold-config validation model to the new `CommandPolicy` |

---

## Recommended first additions (highest value)

1. **CommandPolicy layer** — deterministic, auditable, leadership-authored rules.
2. **Protected-path / blast-radius guardrails** — stop irreversible, high-impact ops.
3. **Dual-control approval** — second-person sign-off for block-range/high-risk actions.

Together with the existing semantic + behavioral scoring, these give the
"known-bad hard rules **and** unknown-but-off-intent detection" combination that
static rule lists alone cannot provide.
