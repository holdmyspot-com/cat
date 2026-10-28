#!/bin/sh
# Keeps the runner alive until the remote shell exits, then lets cleanup continue.
set -eu
[ -n "${REMOTE_SESSION_PUBLIC_KEY:-}" ] || exit 0
while tmux has-session -t upterm 2>/dev/null; do
	sleep 5
done
