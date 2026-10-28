#!/bin/sh
# Waits until the connected client creates .remote-session/client-ready, or 15 minutes pass.
set -eu
[ -n "${REMOTE_SESSION_PUBLIC_KEY:-}" ] || exit 0
for _ in $(seq 1 900); do
	[ -f "$CI_PROJECT_DIR/.remote-session/client-ready" ] && exit 0
	sleep 1
done
echo "No client connected within 15 minutes" >&2
exit 1
