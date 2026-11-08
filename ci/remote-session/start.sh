#!/usr/bin/env bash
# Starts a detached Upterm session that accepts only the key in ci/remote-session/authorized_keys, ends if nobody joins
# within 10 minutes, and hosts a bash shell that exits after 10 minutes at its prompt. The relay's certificate
# authority is pinned from Upterm's README (fingerprint SHA256:9ajV8JqMe6jJE/s3TYjb/9xw7T0pfJ2+gADiBIJWDPE).
set -euo pipefail
mkdir -p "${REMOTE_SESSION_DIR}"
ssh-keygen -l -f ci/remote-session/authorized_keys
printf 'readonly TMOUT=600\nPS1="SESSION$ "\nunset PROMPT_COMMAND\ncd "%s"\n' "${GITHUB_WORKSPACE}" > \
	"${REMOTE_SESSION_DIR}/bashrc"
archive="${REMOTE_SESSION_DIR}/upterm.tar.gz"
curl -fsSL --retry 3 --retry-all-errors -o "${archive}" \
	"https://github.com/owenthereal/upterm/releases/download/${UPTERM_VERSION}/upterm_windows_amd64.tar.gz"
echo "${UPTERM_SHA256}  ${archive}" | sha256sum --check --strict
tar -xzf "${archive}" -C "${REMOTE_SESSION_DIR}"
"${REMOTE_SESSION_DIR}/upterm.exe" version
"${REMOTE_SESSION_DIR}/upterm.exe" host --detach --accept --output json --join-timeout 10m \
	--authorized-keys ci/remote-session/authorized_keys --known-hosts ci/remote-session/upterm_known_hosts \
	-- bash --rcfile "$(cygpath --unix "${REMOTE_SESSION_DIR}")/bashrc" -i > "${REMOTE_SESSION_DIR}/session.json"
jq -er .name "${REMOTE_SESSION_DIR}/session.json" > "${REMOTE_SESSION_DIR}/name"
echo '=== REMOTE SESSION ==='
jq -r .sshCommand "${REMOTE_SESSION_DIR}/session.json"
echo '=== END REMOTE SESSION ==='
