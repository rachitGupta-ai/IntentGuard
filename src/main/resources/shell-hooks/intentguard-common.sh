# shellcheck shell=bash
# intentguard-common.sh
#
# Shared library for the IntentGuard Shell_Hook blocking gate (Requirements 2.2, 7.4, 7.6).
#
# This file is sourced by the shell-specific integrations (intentguard-bash.sh / intentguard-zsh.sh).
# It knows how to:
#   * build a Shell_Hook request (command + full context) as a single JSON line,
#   * send it to the Enforcement_Engine's Unix domain socket and synchronously block until a
#     verdict or a local deadline,
#   * interpret the verdict (ALLOW / ASK / BLOCK), prompting for confirmation on ASK, and
#   * fail SAFE (block or ask, per policy) on any IPC error or missing/late verdict.
#
# It never executes the user's command itself; it only computes whether the command is permitted
# to run. The shell-specific integration is responsible for actually vetoing execution.
#
# Contract with the engine (see com.intentguard.ingest.ShellSignalCodec):
#   request  -> one JSON line: {"actorType","userId","humanPrincipalId","commandText","cwd",
#                               "envContext":{...},"timestamp","inputOrigin"}
#   response <- one JSON line: {"action":"ALLOW|ASK|BLOCK","reasonCode":"...","explanation":"..."}

# ---------------------------------------------------------------------------
# Configuration (override via environment before sourcing the hook)
# ---------------------------------------------------------------------------

# Path to the service-account-owned Unix domain socket (matches intentguard.socket.path).
: "${INTENTGUARD_SOCKET:=/var/run/intentguard/intentguard.sock}"

# The hook's own hard deadline, in seconds, for obtaining a verdict. It is set slightly above the
# engine's 2-second decision budget so a healthy engine always answers first; if this fires the
# engine is effectively unreachable and we fail safe.
: "${INTENTGUARD_DEADLINE_SECS:=3}"

# Fail-safe policy applied on IPC error / no verdict (Req 7.4, 7.6):
#   block -> refuse the command (fail closed, the secure default for a firewall)
#   ask   -> prompt the operator to confirm before running
: "${INTENTGUARD_FAIL_MODE:=block}"

# How long (seconds) to wait for the operator to confirm an ASK verdict before treating it as a
# block (mirrors the engine's confirmation timeout, Req 7.6).
: "${INTENTGUARD_CONFIRM_TIMEOUT_SECS:=15}"

# Actor identity. Default HUMAN; an AI agent harness should export INTENTGUARD_ACTOR_TYPE=AGENT and
# INTENTGUARD_HUMAN_PRINCIPAL=<principal id> so the engine can apply agent-containment rules.
: "${INTENTGUARD_ACTOR_TYPE:=HUMAN}"
: "${INTENTGUARD_HUMAN_PRINCIPAL:=}"

# Set to 1 to silence the informational messages the hook prints on ask/block.
: "${INTENTGUARD_QUIET:=0}"

# ---------------------------------------------------------------------------
# Small utilities
# ---------------------------------------------------------------------------

__intentguard_log() {
    [ "${INTENTGUARD_QUIET}" = "1" ] && return 0
    # Write to stderr so it never pollutes command output/pipelines.
    printf 'IntentGuard: %s\n' "$*" >&2
}

__intentguard_have() {
    command -v "$1" >/dev/null 2>&1
}

# Current time in epoch milliseconds. Prefer GNU date's %N; fall back to whole seconds * 1000.
__intentguard_now_ms() {
    local ms
    ms="$(date +%s%3N 2>/dev/null)"
    case "${ms}" in
        *N|'' ) printf '%s000' "$(date +%s)" ;;  # %3N unsupported -> pad seconds
        *     ) printf '%s' "${ms}" ;;
    esac
}

# The user/session identity the command runs under.
__intentguard_user() {
    id -un 2>/dev/null || printf '%s' "${USER:-${LOGNAME:-unknown}}"
}

# ---------------------------------------------------------------------------
# Request construction (JSON)
# ---------------------------------------------------------------------------

# Escape a string for embedding in a JSON double-quoted value using pure shell (fallback path).
__intentguard_json_escape() {
    local s="$1" out="" c i
    for (( i = 0; i < ${#s}; i++ )); do
        c="${s:i:1}"
        case "${c}" in
            '\') out+='\\' ;;
            '"') out+='\"' ;;
            $'\n') out+='\n' ;;
            $'\r') out+='\r' ;;
            $'\t') out+='\t' ;;
            *) out+="${c}" ;;
        esac
    done
    printf '%s' "${out}"
}

# Build the Shell_Hook request as a single-line JSON object.
#   $1 = command text
#   $2 = input origin (TYPED | PASTED | UNKNOWN)
# A small, curated slice of environment context is included (never secrets).
__intentguard_build_request() {
    local cmd="$1" origin="$2"
    local user ts principal
    user="$(__intentguard_user)"
    ts="$(__intentguard_now_ms)"
    principal="${INTENTGUARD_HUMAN_PRINCIPAL}"

    # Prefer jq for guaranteed-correct escaping; otherwise fall back to a shell escaper.
    if __intentguard_have jq; then
        jq -cn \
            --arg actorType "${INTENTGUARD_ACTOR_TYPE}" \
            --arg userId "${user}" \
            --arg principal "${principal}" \
            --arg commandText "${cmd}" \
            --arg cwd "${PWD}" \
            --arg shell "${SHELL:-}" \
            --arg term "${TERM:-}" \
            --arg lang "${LANG:-}" \
            --argjson timestamp "${ts}" \
            --arg inputOrigin "${origin}" \
            '{
                actorType: $actorType,
                userId: $userId,
                humanPrincipalId: (if $principal == "" then null else $principal end),
                commandText: $commandText,
                cwd: $cwd,
                envContext: {SHELL: $shell, TERM: $term, LANG: $lang},
                timestamp: $timestamp,
                inputOrigin: $inputOrigin
            }'
        return 0
    fi

    local e_cmd e_cwd e_user e_shell e_term e_lang e_principal
    e_cmd="$(__intentguard_json_escape "${cmd}")"
    e_cwd="$(__intentguard_json_escape "${PWD}")"
    e_user="$(__intentguard_json_escape "${user}")"
    e_shell="$(__intentguard_json_escape "${SHELL:-}")"
    e_term="$(__intentguard_json_escape "${TERM:-}")"
    e_lang="$(__intentguard_json_escape "${LANG:-}")"
    if [ -z "${principal}" ]; then
        principal='null'
    else
        principal="\"$(__intentguard_json_escape "${principal}")\""
    fi
    printf '{"actorType":"%s","userId":"%s","humanPrincipalId":%s,"commandText":"%s","cwd":"%s","envContext":{"SHELL":"%s","TERM":"%s","LANG":"%s"},"timestamp":%s,"inputOrigin":"%s"}' \
        "${INTENTGUARD_ACTOR_TYPE}" "${e_user}" "${principal}" "${e_cmd}" "${e_cwd}" \
        "${e_shell}" "${e_term}" "${e_lang}" "${ts}" "${origin}"
}

# ---------------------------------------------------------------------------
# Socket transport
# ---------------------------------------------------------------------------

# Send a request line to the socket and echo back the single verdict line the engine writes.
# Blocks until the engine responds or the local deadline elapses. Returns non-zero on any
# transport failure (socket missing, no client tool, timeout).
__intentguard_send() {
    local request="$1"

    [ -S "${INTENTGUARD_SOCKET}" ] || return 1

    local runner=""
    if __intentguard_have timeout; then
        runner="timeout ${INTENTGUARD_DEADLINE_SECS}"
    fi

    # socat is the most reliable Unix-socket client; nc -U is the common fallback.
    if __intentguard_have socat; then
        printf '%s\n' "${request}" \
            | ${runner} socat -t"${INTENTGUARD_DEADLINE_SECS}" - UNIX-CONNECT:"${INTENTGUARD_SOCKET}" 2>/dev/null \
            | head -n 1
        return "${PIPESTATUS[1]:-1}"
    fi

    if __intentguard_have nc; then
        printf '%s\n' "${request}" \
            | ${runner} nc -U "${INTENTGUARD_SOCKET}" 2>/dev/null \
            | head -n 1
        return "${PIPESTATUS[1]:-1}"
    fi

    return 1
}

# Extract a top-level string field from a verdict JSON line.
#   $1 = field name, $2 = json
__intentguard_json_field() {
    local field="$1" json="$2"
    if __intentguard_have jq; then
        printf '%s' "${json}" | jq -r --arg f "${field}" '.[$f] // empty' 2>/dev/null
        return 0
    fi
    # Fallback: grab the quoted value following "field":
    printf '%s' "${json}" \
        | sed -n "s/.*\"${field}\"[[:space:]]*:[[:space:]]*\"\([^\"]*\)\".*/\1/p" \
        | head -n 1
}

# ---------------------------------------------------------------------------
# Verdict handling / fail-safe
# ---------------------------------------------------------------------------

# Apply the configured fail-safe policy. Returns 0 (allow) only if the operator confirms an
# ASK-mode prompt; otherwise returns 1 (block). Used when the engine cannot be reached (Req 7.4).
__intentguard_failsafe() {
    local reason="$1"
    if [ "${INTENTGUARD_FAIL_MODE}" = "ask" ]; then
        __intentguard_log "engine unreachable (${reason}); confirm to proceed."
        __intentguard_confirm "IntentGuard could not verify this command (${reason})."
        return $?
    fi
    __intentguard_log "engine unreachable (${reason}); command blocked (fail-closed)."
    return 1
}

# Prompt the operator to confirm an ASK. Returns 0 if confirmed within the timeout, else 1.
# An unconfirmed ask (declined or timed out) is treated as a block (Req 7.6).
#
# The read syntax differs between shells (`read -p` means "prompt" in bash but "coprocess" in
# zsh), so this branches on the running shell.
__intentguard_confirm() {
    local explanation="$1" answer=""
    [ -n "${explanation}" ] && __intentguard_log "${explanation}"

    # Only prompt when attached to a terminal; non-interactive contexts fail closed.
    if [ ! -t 0 ] || [ ! -t 2 ]; then
        __intentguard_log "no terminal to confirm on; treating ask as block."
        return 1
    fi

    local prompt="IntentGuard: allow this command? [y/N] "
    if [ -n "${ZSH_VERSION:-}" ]; then
        # zsh: `read "var?prompt"` prints the prompt; -t applies the timeout.
        if ! read -t "${INTENTGUARD_CONFIRM_TIMEOUT_SECS}" "answer?${prompt}"; then
            __intentguard_log "confirmation timed out; command blocked."
            return 1
        fi
    else
        # bash: -p prints the prompt; a timeout returns non-zero.
        if ! read -r -t "${INTENTGUARD_CONFIRM_TIMEOUT_SECS}" -p "${prompt}" answer; then
            __intentguard_log "confirmation timed out; command blocked."
            return 1
        fi
    fi

    case "${answer}" in
        y|Y|yes|YES) return 0 ;;
        *) __intentguard_log "not confirmed; command blocked."; return 1 ;;
    esac
}

# The core gate. Decides whether a command may execute.
#   $1 = command text
#   $2 = input origin (TYPED | PASTED | UNKNOWN); defaults to UNKNOWN (Req 2.4)
# Returns 0 to ALLOW execution, non-zero to VETO (block / unconfirmed ask / fail-safe).
__intentguard_check() {
    local cmd="$1" origin="${2:-UNKNOWN}"

    # Never gate empty input.
    [ -z "${cmd}" ] && return 0

    local request response action explanation
    request="$(__intentguard_build_request "${cmd}" "${origin}")"

    response="$(__intentguard_send "${request}")"
    if [ -z "${response}" ]; then
        __intentguard_failsafe "no verdict"
        return $?
    fi

    action="$(__intentguard_json_field action "${response}")"
    explanation="$(__intentguard_json_field explanation "${response}")"

    case "${action}" in
        ALLOW)
            return 0
            ;;
        ASK)
            __intentguard_confirm "${explanation:-This command diverges from your normal behavior.}"
            return $?
            ;;
        BLOCK)
            __intentguard_log "blocked: ${explanation:-This command was blocked by IntentGuard.}"
            return 1
            ;;
        *)
            # Unrecognized/garbled verdict -> fail safe.
            __intentguard_failsafe "unrecognized verdict"
            return $?
            ;;
    esac
}
