# shellcheck shell=bash
# intentguard-zsh.sh
#
# Zsh integration for the IntentGuard Shell_Hook blocking gate (Req 2.2, 7.4, 7.6).
#
# Zsh has no extdebug equivalent, so the veto point is the `accept-line` ZLE widget. We wrap it:
# when the user presses Enter, the entered buffer is sent to the Enforcement_Engine and we block
# for a verdict. On `allow` we call the builtin accept-line; on `block` (or unconfirmed `ask`) we
# refuse to accept the line, so the command never runs.
#
# Typed-vs-pasted origin is detected reliably here by wrapping the `bracketed-paste` widget: any
# text arriving via a terminal paste flips a flag that is reported as PASTED (Req 9), then reset
# after each entered line.
#
# Source this file (or intentguard.sh) from ~/.zshrc:
#     source /path/to/shell-hooks/intentguard.sh
#
# Requires intentguard-common.sh to have been sourced first.

[ -n "${ZSH_VERSION:-}" ] || return 0
[[ -o interactive ]] || return 0

if ! typeset -f __intentguard_check >/dev/null 2>&1; then
    print -u2 'IntentGuard: intentguard-common.sh must be sourced before intentguard-zsh.sh'
    return 1
fi

__intentguard_zsh_pasted=0

# Wrap bracketed-paste so we know when the current buffer contains pasted content.
__intentguard_zsh_bracketed_paste() {
    __intentguard_zsh_pasted=1
    zle .bracketed-paste
}

# The gate: replaces accept-line. Decides whether the entered buffer may run.
__intentguard_zsh_accept_line() {
    local cmd="${BUFFER}"

    # Empty line: accept normally and reset paste state.
    if [[ -z "${cmd//[[:space:]]/}" ]]; then
        __intentguard_zsh_pasted=0
        zle .accept-line
        return
    fi

    local origin="TYPED"
    [[ "${__intentguard_zsh_pasted}" = "1" ]] && origin="PASTED"
    __intentguard_zsh_pasted=0

    # Repaint so any hook messages/prompt appear below the command line, not over it.
    zle -I

    if __intentguard_check "${cmd}" "${origin}"; then
        zle .accept-line
    else
        # Veto: do not accept the line. Clear the buffer so the command is discarded and the
        # user gets a fresh prompt.
        BUFFER=""
        zle .reset-prompt
    fi
}

__intentguard_zsh_install() {
    zle -N accept-line __intentguard_zsh_accept_line
    # Only wrap bracketed-paste if the terminal/zsh supports it.
    if zle -la bracketed-paste 2>/dev/null || [[ -n "${terminfo[kbs]:-}" ]]; then
        zle -N bracketed-paste __intentguard_zsh_bracketed_paste 2>/dev/null || true
    fi
}

__intentguard_zsh_install
