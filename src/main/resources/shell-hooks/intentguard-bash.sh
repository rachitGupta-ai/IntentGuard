# shellcheck shell=bash
# intentguard-bash.sh
#
# Bash integration for the IntentGuard Shell_Hook blocking gate (Req 2.2, 7.4, 7.6).
#
# Bash cannot veto a command from a plain preexec/PROMPT_COMMAND hook, so this uses the DEBUG trap
# together with `shopt -s extdebug`: when a DEBUG trap command returns a non-zero status and
# extdebug is enabled, bash SKIPS the command that was about to run. That is the mechanism by which
# a `block` (or unconfirmed `ask`) verdict prevents execution.
#
# Source this file (or, preferably, source intentguard.sh which auto-selects it) from ~/.bashrc:
#     source /path/to/shell-hooks/intentguard.sh
#
# Requires intentguard-common.sh to have been sourced first.

# Only meaningful in an interactive bash shell.
case "$-" in
    *i*) ;;
    *) return 0 2>/dev/null || exit 0 ;;
esac
[ -n "${BASH_VERSION:-}" ] || return 0

if [ "$(type -t __intentguard_check 2>/dev/null)" != "function" ]; then
    printf 'IntentGuard: intentguard-common.sh must be sourced before intentguard-bash.sh\n' >&2
    return 1 2>/dev/null || exit 1
fi

# Guard state -------------------------------------------------------------------
__INTENTGUARD_IN_HOOK=0        # re-entrancy guard (extdebug makes functions inherit the trap)
__intentguard_at_prompt=0      # 1 exactly once per entered line, set just after the prompt
__intentguard_block_line=0     # 1 while vetoing the remainder of a blocked compound line
__intentguard_paste_flag=0     # reserved for opt-in paste detection

# Default input origin for bash. Reliable typed-vs-pasted detection is not available in bash
# without breaking readline paste handling, so we honestly report UNKNOWN (Req 2.4). Override with
# INTENTGUARD_DEFAULT_ORIGIN=TYPED if your terminal never pastes.
: "${INTENTGUARD_DEFAULT_ORIGIN:=UNKNOWN}"

# Recover the full command line the user just entered (not merely the first simple command that
# fired the DEBUG trap), so the whole line is evaluated as one Command_Event.
__intentguard_bash_current_command() {
    local h
    h="$(HISTTIMEFORMAT='' builtin history 1 2>/dev/null)" || return 1
    if [[ "${h}" =~ ^[[:space:]]*[0-9]+[[:space:]]+(.*)$ ]]; then
        printf '%s' "${BASH_REMATCH[1]}"
    fi
}

# The DEBUG trap: evaluated before each simple command runs.
__intentguard_bash_debug() {
    # Re-entrancy: while we are inside our own hook logic, allow everything through.
    [ "${__INTENTGUARD_IN_HOOK}" = "1" ] && return 0

    # A previously-blocked compound line: keep vetoing every remaining command until the next
    # prompt resets the state, so `bad; other` blocks fully rather than only its first command.
    [ "${__intentguard_block_line}" = "1" ] && return 1

    # Evaluate only once per entered line, immediately after the prompt is drawn.
    [ "${__intentguard_at_prompt}" != "1" ] && return 0

    local cmd="${BASH_COMMAND}"
    # Never gate the prompt bookkeeping command itself.
    [ "${cmd}" = "${PROMPT_COMMAND}" ] && return 0
    [ "${cmd}" = "__intentguard_bash_prompt" ] && return 0
    __intentguard_at_prompt=0

    local full
    full="$(__intentguard_bash_current_command)"
    [ -z "${full}" ] && full="${cmd}"

    local origin="${INTENTGUARD_DEFAULT_ORIGIN}"
    [ "${__intentguard_paste_flag}" = "1" ] && origin="PASTED"
    __intentguard_paste_flag=0

    __INTENTGUARD_IN_HOOK=1
    if __intentguard_check "${full}" "${origin}"; then
        __INTENTGUARD_IN_HOOK=0
        return 0
    fi
    # Veto: mark the rest of the line for blocking and return non-zero so extdebug skips it.
    __intentguard_block_line=1
    __INTENTGUARD_IN_HOOK=0
    return 1
}

# Runs as part of PROMPT_COMMAND: re-arm the gate for the next entered line.
__intentguard_bash_prompt() {
    __intentguard_at_prompt=1
    __intentguard_block_line=0
}

__intentguard_bash_install() {
    # extdebug is what lets a non-zero DEBUG trap skip the command.
    shopt -s extdebug

    # Append our prompt bookkeeping without clobbering an existing PROMPT_COMMAND.
    case ";${PROMPT_COMMAND};" in
        *";__intentguard_bash_prompt;"*) ;;
        *) PROMPT_COMMAND="__intentguard_bash_prompt${PROMPT_COMMAND:+;${PROMPT_COMMAND}}" ;;
    esac

    trap '__intentguard_bash_debug' DEBUG
}

__intentguard_bash_install
