#!/usr/bin/env bash
# Keeps the runner until the remote session ends, then makes sure that it has stopped.
set -uo pipefail
name=$(cat "${REMOTE_SESSION_DIR}/name") || exit 1
"${REMOTE_SESSION_DIR}/upterm.exe" session wait "${name}"
"${REMOTE_SESSION_DIR}/upterm.exe" session stop "${name}" 2> /dev/null || true
