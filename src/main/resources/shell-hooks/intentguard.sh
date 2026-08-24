# shellcheck shell=bash
# intentguard.sh
#
# Entry point for the IntentGuard Shell_Hook blocking gate. Source this one file from your shell
# rc and it loads the shared library plus the correct integration for bash or zsh:
#
#     # ~/.bashrc or ~/.zshrc
#     source /path/to/shell-hooks/intentguard.sh
#
# It resolves its own directory so the companion scripts are found regardless of where the
# shell-hooks directory is installed.

# Resolve this script's directory in both bash and zsh.
if [ -n "${BASH_VERSION:-}" ]; then
    __intentguard_self="${BASH_SOURCE[0]}"
elif [ -n "${ZSH_VERSION:-}" ]; then
    __intentguard_self="${(%):-%x}"
else
    printf 'IntentGuard: unsupported shell (need bash or zsh)\n' >&2
    return 0 2>/dev/null || exit 0
fi

__intentguard_dir="$(cd "$(dirname "${__intentguard_self}")" >/dev/null 2>&1 && pwd)"
unset __intentguard_self

# Shared library (transport, JSON, verdict handling, fail-safe).
# shellcheck source=intentguard-common.sh
. "${__intentguard_dir}/intentguard-common.sh"

# Shell-specific enforcement mechanism.
if [ -n "${ZSH_VERSION:-}" ]; then
    # shellcheck source=intentguard-zsh.sh
    . "${__intentguard_dir}/intentguard-zsh.sh"
elif [ -n "${BASH_VERSION:-}" ]; then
    # shellcheck source=intentguard-bash.sh
    . "${__intentguard_dir}/intentguard-bash.sh"
fi

unset __intentguard_dir
