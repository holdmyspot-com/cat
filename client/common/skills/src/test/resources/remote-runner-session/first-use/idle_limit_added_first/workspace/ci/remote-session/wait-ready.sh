#!/usr/bin/env bash
# Waits until a connected client creates the ready file, and fails if the session ends first.
set -euo pipefail
until [ -e "$REMOTE_SESSION_DIR/ready" ]; do
	status=$("$REMOTE_SESSION_DIR/upterm" session info ci-session -o json 2> /dev/null | jq -r .status) || status=ended
	if [ "$status" = ended ]; then
		echo 'The remote session ended before the readiness signal.' >&2
		exit 1
	fi
	sleep 2
done
