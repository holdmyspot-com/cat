#!/usr/bin/env bash
# Keeps the worker until the remote shell exits, then removes the session's files.
set -euo pipefail
[[ -d .remote-session ]] || exit 0
while pgrep -x upterm > /dev/null; do sleep 5; done
rm -rf .remote-session
