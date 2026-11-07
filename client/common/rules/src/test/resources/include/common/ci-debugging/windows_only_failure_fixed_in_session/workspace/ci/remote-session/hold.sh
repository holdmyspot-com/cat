#!/usr/bin/env bash
# Keeps the runner until the remote session ends, then makes sure that it has stopped.
set -uo pipefail
"$REMOTE_SESSION_DIR/upterm" session wait ci-session
"$REMOTE_SESSION_DIR/upterm" session stop ci-session 2> /dev/null || true
