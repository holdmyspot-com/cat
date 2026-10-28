#!/usr/bin/env bash
# Waits until the connected client creates .remote-session/client-ready, or {{minutes}} minutes pass.
set -euo pipefail
[[ -n "${REMOTE_SESSION_PUBLIC_KEY:-}" ]] || exit 0
deadline=$((SECONDS + {{minutes}} * 60))
until [[ -e .remote-session/client-ready ]]; do
	if (( SECONDS >= deadline )); then
		echo "No client connected within {{minutes}} minutes; continuing without a session"
		exit 0
	fi
	sleep 5
done
