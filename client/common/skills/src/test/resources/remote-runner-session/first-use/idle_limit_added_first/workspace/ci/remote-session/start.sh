#!/usr/bin/env bash
# Starts a detached Upterm session named ci-session that accepts only REMOTE_SESSION_PUBLIC_KEY, and prints its SSH
# command to the job's log, which GitHub streams while the job runs.
set -euo pipefail
mkdir -p "$REMOTE_SESSION_DIR"
printf '%s\n' "$REMOTE_SESSION_PUBLIC_KEY" > "$REMOTE_SESSION_DIR/authorized_keys"
ssh-keygen -l -f "$REMOTE_SESSION_DIR/authorized_keys" > /dev/null
case "$(uname -m)" in
	x86_64) arch=amd64 ;;
	aarch64) arch=arm64 ;;
	*) echo "unsupported architecture: $(uname -m)" >&2; exit 1 ;;
esac
curl -fsSL "https://github.com/owenthereal/upterm/releases/download/${UPTERM_VERSION}/upterm_linux_${arch}.tar.gz" |
	tar -xz -C "$REMOTE_SESSION_DIR" upterm
"$REMOTE_SESSION_DIR/upterm" host --detach --accept --name ci-session \
	--authorized-keys "$REMOTE_SESSION_DIR/authorized_keys" --known-hosts ci/remote-session/upterm_known_hosts \
	--server ssh://uptermd.upterm.dev:22 -o json -- bash > "$REMOTE_SESSION_DIR/session.json"
echo '=== REMOTE SESSION ==='
jq -r .sshCommand "$REMOTE_SESSION_DIR/session.json"
echo "Create $REMOTE_SESSION_DIR/ready through the session to start the integration tests."
echo '=== END REMOTE SESSION ==='
