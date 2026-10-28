#!/usr/bin/env bash
# Keeps the agent until the remote shell exits.
set -euo pipefail
while pgrep -x upterm > /dev/null; do sleep 5; done
