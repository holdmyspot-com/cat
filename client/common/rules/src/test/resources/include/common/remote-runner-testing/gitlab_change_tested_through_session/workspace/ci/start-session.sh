#!/bin/sh
# Starts a detached Upterm session that accepts only REMOTE_SESSION_PUBLIC_KEY and prints its SSH command to the job log.
set -eu
if [ -z "${REMOTE_SESSION_PUBLIC_KEY:-}" ]; then
	echo "No REMOTE_SESSION_PUBLIC_KEY; running without a session"
	exit 0
fi
state="$CI_PROJECT_DIR/.remote-session"
mkdir -p "$state"
printf '%s\n' "$REMOTE_SESSION_PUBLIC_KEY" > "$state/authorized_keys"
ssh-keygen -lf "$state/authorized_keys"
curl -fsSL "https://github.com/owenthereal/upterm/releases/download/$UPTERM_VERSION/upterm_linux_amd64.tar.gz" |
	tar -xz -C "$state" upterm
tmux new-session -d -s upterm "$state/upterm host --accept --authorized-keys $state/authorized_keys \
	--known-hosts ci/upterm_known_hosts --server ssh://uptermd.upterm.dev:22 -- bash 2>&1 | tee $state/host.log"
for _ in $(seq 1 30); do
	grep -q 'SSH Command' "$state/host.log" 2>/dev/null && break
	sleep 1
done
echo "=== REMOTE SESSION ==="
grep 'SSH Command' "$state/host.log"
echo "=== END REMOTE SESSION ==="
