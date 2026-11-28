#!/usr/bin/env bash
# Keyring probe: Codex 0.172.0 stores a fabricated ChatGPT sign-in in the OS keyring, CAT reads it, and the item is
# compared before and after. No real credential is involved. Run from the repository root after the build step.
set -uo pipefail

here=.github/keyring-probe
failures=0
pass() { echo "PASS: $*"; echo "PASS: $*" >> "${results}"; }
fail() { echo "FAIL: $*"; echo "FAIL: $*" >> "${results}"; failures=$((failures + 1)); }
note() { echo "NOTE: $*"; echo "NOTE: $*" >> "${results}"; }
# Runs a command with a time limit, as macOS has no timeout(1)
limit() { perl -e 'alarm shift; exec @ARGV or die "exec: $!"' "$@"; }

if [ "${RUNNER_OS}" = "Windows" ]; then
	work="$(cygpath --unix "${RUNNER_TEMP}")/keyring-probe"
	native() { cygpath --windows "$1"; }
	python=python
	separator=';'
else
	work="${RUNNER_TEMP}/keyring-probe"
	native() { printf '%s\n' "$1"; }
	python=python3
	separator=':'
fi
mkdir -p "${work}"
results="${work}/results.txt"
: > "${results}"
source_home="${work}/source-home"
codex_home="${source_home}/.codex"
mkdir -p "${codex_home}"
classpath="client/codex/cli/target/classes${separator}$(cat "${work}/../cp.txt")"

echo "::group::Codex"
codex --version
npm_root="$(npm root -g)"
[ "${RUNNER_OS}" = "Windows" ] && npm_root="$(cygpath --unix "${npm_root}")"
codex_native="$(find "${npm_root}/@openai" -type f \( -name codex.exe -o -name codex \) -path '*vendor*' | head -1)"
echo "native codex: ${codex_native}"
if [ "${RUNNER_OS}" = "macOS" ]; then
	codesign -dv --verbose=2 "${codex_native}" 2>&1 | grep -E 'Identifier|TeamIdentifier|Authority|Timestamp|Runtime' || true
fi
echo "::endgroup::"

echo "::group::Fake OAuth refresh endpoint"
"${python}" "${here}/login.py" serve "$(native "${work}/port")" "$(native "${work}/requests.log")" \
	"$(native "${work}/expected-token")" &
server=$!
trap 'kill "${server}" 2>/dev/null' EXIT
for _ in $(seq 100); do [ -s "${work}/port" ] && break; sleep 0.1; done
port="$(cat "${work}/port")"
echo "listening on 127.0.0.1:${port}"
touch "${work}/requests.log"
echo "::endgroup::"

export CODEX_HOME
CODEX_HOME="$(native "${codex_home}")"
export CODEX_REFRESH_TOKEN_URL_OVERRIDE="http://127.0.0.1:${port}/oauth/token"
export RUST_LOG=warn
export NO_PROXY=127.0.0.1,localhost no_proxy=127.0.0.1,localhost

echo "::group::Codex stores the fabricated sign-in in the keyring"
"${python}" "${here}/login.py" make-login "${CODEX_HOME}"
"${python}" "${here}/login.py" auth-status true - -- "$(native "${codex_native}")" app-server 2> "${work}/create.err"
status=$?
grep -E 'WARN|ERROR' "${work}/create.err" | grep -v 'trusted project\|Project-local' | cut -c1-400
echo "refresh requests so far:"; cat "${work}/requests.log"
if [ "${status}" -eq 0 ] && [ ! -e "${codex_home}/auth.json" ]; then
	pass "Codex 0.172.0 (store auto) moved the refreshed sign-in into the keyring and deleted auth.json"
else
	fail "Codex did not store the sign-in in the keyring (status ${status}; auth.json present:" \
		"$([ -e "${codex_home}/auth.json" ] && echo yes || echo no))"
fi
printf 'cli_auth_credentials_store = "keyring"\n' > "${codex_home}/config.toml"
requests_after_creation="$(wc -l < "${work}/requests.log")"
ls -la "${codex_home}" "${codex_home}/secrets" 2>/dev/null
echo "::endgroup::"

canonical="$("${python}" -I -c '
import os, sys
path = os.path.realpath(sys.argv[1])
print("\\\\?\\" + path if os.name == "nt" and not path.startswith("\\\\?\\") else path)' "${CODEX_HOME}")"
hash="$(printf '%s' "${canonical}" | "${python}" -I -c 'import hashlib, sys; print(hashlib.sha256(sys.stdin.buffer.read()).hexdigest()[:16])')"
echo "canonical CODEX_HOME: ${canonical}; hash ${hash}"

snapshot() {
	if [ "${RUNNER_OS}" = "Windows" ]; then
		pwsh -NoProfile -File "$(native "${here}/snapshot-windows.ps1")" -Target "secrets|${hash}.codex" \
			-CodexHome "${CODEX_HOME}"
	else
		echo "attributes:"
		limit 30 /usr/bin/security find-generic-password -s "Codex Auth" -a "cli|${hash}" "${KEYCHAIN}" 2>&1
		echo "access lists:"
		limit 60 /usr/bin/security dump-keychain -a "${KEYCHAIN}" 2>&1
	fi
}

echo "::group::The keyring's entries"
if [ "${RUNNER_OS}" = "Windows" ]; then
	cmdkey /list | grep -i -B1 -A3 'codex' || true
else
	limit 30 /usr/bin/security find-generic-password -s "Codex Auth" "${KEYCHAIN}" 2>&1 | head -30
fi
echo "::endgroup::"

echo "::group::Snapshot before CAT reads"
snapshot > "${work}/before.txt" 2>&1
cat "${work}/before.txt"
echo "::endgroup::"

echo "::group::CAT's keyring reader and login service"
java -Djava.io.tmpdir="$(native "${work}")" -cp "${classpath}" "${here}/KeyringProbe.java" "$(native "${source_home}")" \
	"${CODEX_HOME}" "$(native "${work}/expected-token")" 2>&1 | grep -v '^SLF4J' | tee "${work}/probe.txt"
if [ "${PIPESTATUS[0]}" -eq 0 ]; then
	pass "CAT read the token that Codex stored, and the login service opened and refused as the reach check says"
else
	fail "CAT's probe failed; see the lines marked FAIL above"
fi
echo "::endgroup::"

echo "::group::Snapshot after CAT reads"
snapshot > "${work}/after.txt" 2>&1
cat "${work}/after.txt"
if diff "${work}/before.txt" "${work}/after.txt"; then
	pass "the keyring item and its access list are identical before and after CAT's reads"
else
	fail "the keyring item or its access list changed while CAT read it"
fi
requests_after_read="$(wc -l < "${work}/requests.log")"
if [ "${requests_after_read}" -eq "${requests_after_creation}" ]; then
	pass "no token refresh reached the OAuth endpoint while CAT read (${requests_after_read} requests, all at creation)"
else
	fail "a refresh reached the OAuth endpoint while CAT read"
	cat "${work}/requests.log"
fi
echo "::endgroup::"

echo "::group::Control: Codex itself reads the item it stored"
"${python}" "${here}/login.py" auth-status false "$(native "${work}/codex-token")" -- "$(native "${codex_native}")" app-server \
	2> "${work}/codex-read.err"
if cmp -s "${work}/codex-token" "${work}/expected-token"; then
	pass "Codex reads back the token it stored"
else
	fail "Codex did not read back the token it stored"
	grep -E 'WARN|ERROR' "${work}/codex-read.err" | cut -c1-400
fi
echo "::endgroup::"

if [ "${RUNNER_OS}" = "macOS" ]; then
	echo "::group::Control: another program asks for the item's secret"
	limit 20 /usr/bin/security find-generic-password -s "Codex Auth" -a "cli|${hash}" -w "${KEYCHAIN}" > /dev/null \
		2> "${work}/other.err"
	status=$?
	cat "${work}/other.err"
	note "/usr/bin/security -w (not the creating codex) ended with status ${status}"
	snapshot > "${work}/after-other.txt" 2>&1
	if diff "${work}/after.txt" "${work}/after-other.txt" > /dev/null; then
		note "the item is unchanged after that request"
	else
		note "the item changed after that request"
	fi
	echo "::endgroup::"

	echo "::group::Reach: a command looks the item up by its attributes in each sandbox"
	lookup=(/usr/bin/security find-generic-password -s "Codex Auth" -a "cli|${hash}")
	run_case() {
		local name="$1"
		shift
		limit 60 "$@" > "${work}/case-${name}.out" 2>&1
		local status=$?
		echo "case ${name}: status ${status}"
		head -c 1500 "${work}/case-${name}.out"
		echo
		if [ "${status}" -eq 0 ]; then echo reachable > "${work}/outcome-${name}"; else echo unreachable > "${work}/outcome-${name}"; fi
	}
	sandbox_home="${work}/sandbox-codex-home"
	mkdir -p "${sandbox_home}"
	cd "${work}" || exit 1
	run_case unsandboxed "${lookup[@]}"
	run_case codex-network-full env CODEX_HOME="${sandbox_home}" codex -c 'sandbox_mode="workspace-write"' \
		-c 'sandbox_workspace_write.network_access=true' sandbox -- "${lookup[@]}"
	run_case codex-network-off env CODEX_HOME="${sandbox_home}" codex -c 'sandbox_mode="workspace-write"' \
		-c 'sandbox_workspace_write.network_access=false' sandbox -- "${lookup[@]}"
	run_case codex-read-only env CODEX_HOME="${sandbox_home}" codex -c 'sandbox_mode="read-only"' sandbox -- \
		"${lookup[@]}"
	printf '{"network":{"allowedDomains":[],"deniedDomains":[]},"filesystem":{"denyRead":[],"allowWrite":["%s"],"denyWrite":[]}}\n' \
		"${work}" > "${work}/srt.json"
	run_case claude-code-sandboxed srt --settings "${work}/srt.json" \
		"/usr/bin/security find-generic-password -s 'Codex Auth' -a 'cli|${hash}'"
	printf '{"network":{"allowedDomains":["example.com"],"deniedDomains":[]},"filesystem":{"denyRead":[],"allowWrite":["%s"],"denyWrite":[]}}\n' \
		"${work}" > "${work}/srt-network.json"
	run_case claude-code-sandboxed-with-domains srt --settings "${work}/srt-network.json" \
		"/usr/bin/security find-generic-password -s 'Codex Auth' -a 'cli|${hash}'"
	echo "srt version: $(npm ls -g @anthropic-ai/sandbox-runtime --depth=0 2>/dev/null | grep sandbox-runtime)"
	cd - > /dev/null || exit 1
	for name in unsandboxed codex-network-full codex-network-off claude-code-sandboxed; do
		check="$(grep "^check ${name}: " "${work}/probe.txt" | sed -E 's/^check [^:]+: (reachable|unreachable).*/\1/')"
		outcome="$(cat "${work}/outcome-${name}")"
		if [ "${check}" = "${outcome}" ]; then
			pass "P36(2) ${name}: the command's lookup is ${outcome} and the check says ${check}"
		else
			fail "P36(2) ${name}: the command's lookup is ${outcome} but the check says ${check:-nothing}"
		fi
	done
	note "codex-read-only lookup: $(cat "${work}/outcome-codex-read-only"); claude-code-sandboxed-with-domains" \
		"lookup: $(cat "${work}/outcome-claude-code-sandboxed-with-domains")"
	echo "::endgroup::"
fi

echo "::group::Results"
cat "${results}"
echo "::endgroup::"
[ "${failures}" -eq 0 ]
