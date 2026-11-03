#!/usr/bin/env bash
# Starts a detached Upterm session named ci-session that accepts only REMOTE_SESSION_PUBLIC_KEY, ends if nobody joins
# within 10 minutes, and hosts a bash shell that logs out after 10 minutes at its prompt. Prints the SSH command to the
# job's log, which GitHub streams while the job runs.
set -euo pipefail
mkdir -p "$REMOTE_SESSION_DIR"
printf '%s\n' "$REMOTE_SESSION_PUBLIC_KEY" > "$REMOTE_SESSION_DIR/authorized_keys"
ssh-keygen -l -f "$REMOTE_SESSION_DIR/authorized_keys" > /dev/null
printf 'readonly TMOUT=600\n' > "$REMOTE_SESSION_DIR/bashrc"
curl -fsSL "https://github.com/owenthereal/upterm/releases/download/${UPTERM_VERSION}/upterm_linux_amd64.tar.gz" |
	tar -xz -C "$REMOTE_SESSION_DIR" upterm
"$REMOTE_SESSION_DIR/upterm" host --detach --accept --name ci-session --join-timeout 10m \
	--authorized-keys "$REMOTE_SESSION_DIR/authorized_keys" --known-hosts ci/remote-session/upterm_known_hosts \
	--server ssh://uptermd.upterm.dev:22 -o json -- bash --rcfile "$REMOTE_SESSION_DIR/bashrc" -i \
	> "$REMOTE_SESSION_DIR/session.json"
echo '=== REMOTE SESSION ==='
jq -r .sshCommand "$REMOTE_SESSION_DIR/session.json"
echo '=== END REMOTE SESSION ==='
