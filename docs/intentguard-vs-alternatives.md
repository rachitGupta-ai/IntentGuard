# IntentGuard vs. Existing Command-Control Capabilities

This document compares IntentGuard's approach to command control against existing
capabilities at the OS, cloud, and product levels, and explains where IntentGuard
is genuinely different.

## The core distinction

Most command-control mechanisms answer one of two questions:

- **Rule / signature based** — "Is this command (or API action) on an approved
  list, or matching a known-bad pattern, for this identity?"
- **Identity / permission based** — "Is this identity allowed to perform this
  action at all?"

IntentGuard answers a third question that neither of those addresses:

> **Does this action *diverge* from the human-authorized intent and from the
> user's normal behavior?**

That framing (consistency scoring, not classification) is what lets it catch a
prompt-injected AI agent running ordinary-looking commands, a pasted/obfuscated
payload, or a session takeover — cases a static rule list or a permission check
would wave through because, in isolation, each command looks legitimate.

A second important axis is **prevent vs. detect**:

- **Prevent** = a pre-execution gate that can stop the command before it runs.
- **Detect** = observes/logs after the fact (cannot block the first occurrence).

IntentGuard's Shell_Hook is a true pre-execution gate; its Audit_Feed (auditd)
path is detection/corroboration only, by design.

## Comparison table

| Capability | Layer | Model | Prevent or Detect | Interactive shell commands? | Notes |
|---|---|---|---|---|---|
| **IntentGuard** | Userspace reference monitor | Intent + behavior divergence scoring (+ optional admin rule layer) | **Prevent** (Shell_Hook), detect (auditd) | Yes | Explains *why*; catches off-intent agent actions, pasted payloads, session hijack |
| sudo / sudoers | OS (Linux) | Rule allowlist/denylist of commands per user/group | Prevent | Yes | Closest classic "admin defines allowed commands" |
| Restricted shell (`rbash`) | OS (Linux) | Constrains what the shell can do | Prevent | Yes | Coarse; easily outgrown |
| SELinux / AppArmor | OS (Linux) | Mandatory access control (per binary/process) | Prevent | Indirectly | Confines process capabilities, not command semantics |
| seccomp-bpf | OS (Linux) | Syscall allow/deny | Prevent | No (syscall level) | Very low level |
| fapolicyd (RHEL) | OS (Linux) | Execution allowlisting (approved binaries) | Prevent | Executables only | "Only approved binaries run" |
| auditd (Linux Audit) | OS (Linux) | Rule-based event logging | **Detect only** | Yes (observes) | Cannot block; IntentGuard uses it for corroboration/bypass detection |
| PolicyKit (polkit) | OS (Linux) | Authorization rules for privileged actions | Prevent | Some | Action authorization, not command scanning |
| AppLocker / WDAC | OS (Windows) | Path/hash/publisher allow/deny rules | Prevent | Executables/scripts | Windows application control |
| Software Restriction Policies / GPO | OS (Windows) | Path/hash/publisher rules | Prevent | Executables/scripts | Legacy/GPO-driven |
| AWS SCPs / IAM | Cloud (AWS) | Permission guardrails on API actions | Prevent | No (control-plane API) | Gates cloud API calls, not shell commands |
| AWS SSM Session Manager | Cloud (AWS) | Allowed-command documents + logging | Prevent + detect | Yes (managed sessions) | Command allowlists for managed instances |
| AWS Config / GuardDuty | Cloud (AWS) | Rule/heuristic evaluation | Detect | No | Posture/threat detection |
| Azure Policy / GCP Org Policy | Cloud | Guardrails on control-plane actions | Prevent | No | Analogous to SCPs |
| PAM: Teleport, StrongDM, CyberArk, BeyondTrust | Product | Command allowlist/denylist (regex), approvals, recording | Prevent | Yes | Closest commercial fit to "leadership-defined command rules that block execution" |
| EDR/XDR: CrowdStrike, SentinelOne, MS Defender for Endpoint | Product | Custom detection/prevention on process/command line | Prevent + detect | Yes (observes processes) | Mostly rule/IOC-based; some behavioral |

## Admin/leadership command rules

A rule/pattern policy — "these command patterns are denied / require confirmation"
authored by admins or leadership — is well-established prior art (sudoers, PAM
tools, AppLocker/WDAC, SSM). IntentGuard does **not** ship this as a first-class
feature today; its only pattern matching is the narrowly-scoped `TamperClassifier`
that protects the engine itself.

It slots in cleanly, though: the `DecisionEngine` already applies ordered rules
(tamper override → threshold map → learning clamp → agent containment → ask-timeout).
An admin **CommandPolicy** layer would be a new deterministic stage evaluated
*before* the threshold map:

- A versioned, hot-reloadable `CommandPolicy` (like `ThresholdConfiguration`) with
  ordered rules: `pattern` (glob/regex over the normalized command + args), optional
  scope (user, group, cwd/repo, actor type HUMAN/AGENT), and action
  (`DENY` / `REQUIRE_CONFIRM` / `ALLOW`).
- A pre-decision check: first matching `DENY` forces block (like the tamper
  override); `REQUIRE_CONFIRM` forces at least ask; explicit `ALLOW` can whitelist.
- Every policy hit persisted to `Audit_History` with the matched rule id and named
  in the explanation ("blocked by policy rule R-12").
- Precedence: a hard `DENY` must win over a low divergence score, and the learning
  clamp must **not** soften an explicit policy deny.

This would give both worlds: deterministic leadership-authored guardrails for
known-bad, plus semantic/behavioral scoring for the unknown-but-off-intent.

## Summary

Every OS/cloud/product option above is fundamentally **static rules, signatures,
or identity/permission** — "is this command/action on an approved list for this
identity." IntentGuard adds the missing axis: **behavioral and intent divergence**,
with a plain-English explanation for every flagged action. Adding the admin
command-policy layer would let it enforce hard rules *and* catch the off-intent
actions that rule lists miss.
