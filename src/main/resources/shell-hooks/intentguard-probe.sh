#!/usr/bin/env bash
# intentguard-probe.sh
#
# Standalone helper to run a single command through the IntentGuard gate WITHOUT installing the
# interactive trap. Useful for demos, scripting, and verifying the socket round-trip.
#
#     ./intentguard-probe.sh "kubectl delete ns prod"
#
# Exit status: 0 if the command would be ALLOWED (or an ASK the operator confirmed);
#              non-zero if it would be BLOCKED / unconfirmed / failed safe.
set -u

__intentguard_probe_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" >/dev/null 2>&1 && pwd)"
# shellcheck source=intentguard-common.sh
. "${__intentguard_probe_dir}/intentguard-common.sh"

if [ "$#" -lt 1 ]; then
    printf 'usage: %s "<command text>" [TYPED|PASTED|UNKNOWN]\n' "$0" >&2
    exit 2
fi

__intentguard_check "$1" "${2:-UNKNOWN}"
status=$?
if [ "${status}" -eq 0 ]; then
    printf 'IntentGuard: ALLOW\n' >&2
else
    printf 'IntentGuard: DENIED (block / unconfirmed / fail-safe)\n' >&2
fi
exit "${status}"
