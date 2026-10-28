#!/usr/bin/env bash
# Starts an Upterm session in the background that accepts only REMOTE_SESSION_PUBLIC_KEY, and prints its SSH command
# to the console log, which Jenkins streams while the build runs.
set -euo pipefail
mkdir -p .remote-session
printf '%s\n' "$REMOTE_SESSION_PUBLIC_KEY" > .remote-session/authorized_keys
ssh-keygen -l -f .remote-session/authorized_keys > /dev/null
curl -fsSL "https://github.com/owenthereal/upterm/releases/download/${UPTERM_VERSION}/upterm_linux_$(dpkg --print-architecture).tar.gz" |
	tar -xz -C .remote-session upterm
.remote-session/upterm host --accept --authorized-keys .remote-session/authorized_keys \
	--known-hosts ci/upterm_known_hosts --server ssh://uptermd.upterm.dev:22 -- bash > .remote-session/host.log 2>&1 &
until grep -q 'SSH Command' .remote-session/host.log; do sleep 1; done
echo '=== REMOTE SESSION ==='
grep 'SSH Command' .remote-session/host.log
echo '=== END REMOTE SESSION ==='
