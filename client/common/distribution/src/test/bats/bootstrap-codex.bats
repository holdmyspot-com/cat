#!/usr/bin/env bats

# Tests Codex plugin installation, removal, release bootstrapping, and generated hook behavior.

load bootstrap-helpers

# Gives the packaged hooks a recent account check while the fixture server is unavailable.
# Side effects: Writes the test's private Codex login under CODEX_HOME.
save_recent_account_check() {
	local plugin_data checked_at
	plugin_data="${CODEX_HOME}/plugins/data/cat-cat"
	mkdir -p "${plugin_data}/auth"
	chmod 700 "${plugin_data}/auth"
	checked_at="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
	cat > "${plugin_data}/auth/session.json" <<JSON
{"schema_version":3,"server_base":"http://localhost:9/","client_id":"client_test",
"user_id":"user_test","access_token":"access","refresh_token":"refresh",
"selected_account_id":17,"last_authorized_at":"${checked_at}"}
JSON
	chmod 600 "${plugin_data}/auth/session.json"
}

@test "Codex installer reports a missing shared library" {
	standalone_directory="${TEST_ROOT}/standalone Codex installer"
	mkdir -p "${standalone_directory}"
	cp "${INSTALL_DIRECTORY}/install-codex.sh" "${standalone_directory}/install-codex.sh"

	run sh "${standalone_directory}/install-codex.sh"

	[ "${status}" -eq 1 ]
	[[ "${output}" == *"CAT installer library not found"* ]]
}

@test "generated Codex plugin-info launcher reports its native identity" {
	expected_platform="$(expected_native_platform)"

	run "${GENERATED_RUNTIME_DIRECTORY}/codex/client/bin/plugin-info"

	[ "${status}" -eq 0 ]
	[ "${output}" = "harness=codex
version=1.4
platform=${expected_platform}" ]
}

@test "Codex account selection launcher tells a user without a saved login to sign in" {
	mkdir -p "${CODEX_HOME}/plugins/data/cat-cat"
	create_fake_bwrap

	run env PATH="${FAKE_BIN}:${PATH}" "${GENERATED_RUNTIME_DIRECTORY}/codex/client/bin/account-select" 17

	[ "${status}" -ne 0 ]
	[[ "${output}" == *"No WorkOS login is saved; sign in before selecting an account"* ]]
}

@test "Codex lazy rule launcher withholds rule content until the user signs in" {
	create_fake_bwrap

	run env PATH="${FAKE_BIN}:${PATH}" CODEX_HOME="${CODEX_HOME}" \
		"${GENERATED_RUNTIME_DIRECTORY}/codex/client/bin/rule-load" include/common/write-clearly.md

	[ "${status}" -ne 0 ]
	[[ "${output}" == *"CAT features are disabled. Sign in with"* ]]
	[[ "${output}" != *"# Clear Writing"* ]]
}

@test "Codex lazy rule launcher supplies a rule and queues its use during allowed offline access" {
	create_fake_bwrap
	save_recent_account_check

	run env PATH="${FAKE_BIN}:${PATH}" CODEX_HOME="${CODEX_HOME}" \
		"${GENERATED_RUNTIME_DIRECTORY}/codex/client/bin/rule-load" include/common/write-clearly.md

	[ "${status}" -eq 0 ]
	[[ "${output}" == *"# Clear Writing"* ]]
	records=("${CODEX_HOME}"/plugins/data/cat-cat/feature-outbox/*.json)
	[ "${#records[@]}" -eq 1 ]
	grep -F '"account_id":17' "${records[0]}"
	grep -F 'include/common/write-clearly.md' "${records[0]}"
}

@test "Codex login launcher requires a home for its private session" {
	run env -u CODEX_HOME -u HOME "${GENERATED_RUNTIME_DIRECTORY}/codex/client/bin/login"

	[ "${status}" -ne 0 ]
	[[ "${output}" == *"HOME or CODEX_HOME is required"* ]]
}

@test "Codex Stop hook retains measured usage when the host is unavailable" {
	create_fake_engine_command codex
	create_fake_bwrap
	run env PATH="${FAKE_BIN}:${PATH}" CODEX_HOME="${CODEX_HOME}" \
		sh "${INSTALL_DIRECTORY}/install-codex.sh" "${GENERATED_RUNTIME_DIRECTORY}/codex"
	[ "${status}" -eq 0 ]
	runtime="${CODEX_HOME}/plugins/cat-marketplace/plugins/cat"
	plugin_data="${CODEX_HOME}/plugins/data/cat-cat"
	session_id="01a0d58c-2542-7983-9386-1f5a2fadd3d7"
	turn_id="00000000-0000-0000-0000-000000000456"
	rollout="${CODEX_HOME}/sessions/rollout-2026-09-25T00-00-00-${session_id}.jsonl"
	mkdir -p "${plugin_data}/auth" "${CODEX_HOME}/sessions"
	chmod 700 "${plugin_data}/auth"
	cat > "${plugin_data}/auth/session.json" <<'JSON'
{"schema_version":2,"server_base":"http://localhost:9/cat/","client_id":"client_test",
"user_id":"user_test","access_token":"access","refresh_token":"refresh","selected_account_id":17}
JSON
	chmod 600 "${plugin_data}/auth/session.json"
	cat > "${rollout}" <<JSON
{"type":"session_meta","payload":{"session_id":"${session_id}","cli_version":"0.157.0"}}
{"type":"token_usage_record","payload":{"session_id":"${session_id}","root_turn_id":"${turn_id}",
"turn_id":"${turn_id}","response_id":"response-first","usage":{"output_tokens":42},
"turn_token_usage":{"output_tokens":42}}}
JSON
	cat > "${TEST_ROOT}/stop-input.json" <<JSON
{"hook_event_name":"Stop","session_id":"${session_id}","turn_id":"${turn_id}",
"transcript_path":"${rollout}","stop_hook_active":false}
JSON
	run env PATH="${FAKE_BIN}:${PATH}" PLUGIN_ROOT="${runtime}" PLUGIN_DATA="${plugin_data}" \
		sh -c '"$1" < "$2"' sh "${runtime}/hooks/stop.sh" "${TEST_ROOT}/stop-input.json"

	[ "${status}" -eq 0 ]
	[[ "${output}" == *"Usage report queued"* ]]
	records=("${plugin_data}"/outbox/*.json)
	[ "${#records[@]}" -eq 1 ]
	grep -F '"account_id":17' "${records[0]}"
	jq -e '.body | fromjson | .turn_output_tokens_so_far == 42' "${records[0]}"
}

@test "installer tells users how to correct an invalid marketplace directory" {
	artifact="$(create_test_artifact codex)"

	run env CAT_CODEX_MARKETPLACE_ROOT="${TEST_ROOT}/not-cat" sh "${INSTALL_DIRECTORY}/install-codex.sh" "${artifact}"

	[ "${status}" -eq 1 ]
	[[ "${output}" == *"must end in /cat-marketplace"* ]]
	[[ "${output}" == *"CAT_CODEX_MARKETPLACE_ROOT"* ]]
}

@test "Codex installs an extracted artifact through an artifact-local marketplace" {
	create_fake_engine_command codex
	artifact="$(create_test_artifact codex)"

	run env PATH="${FAKE_BIN}:${PATH}" COMMAND_LOG="${COMMAND_LOG}" CODEX_HOME="${CODEX_HOME}" \
		sh "${INSTALL_DIRECTORY}/install-codex.sh" "${artifact}"

	[ "${status}" -eq 0 ]
	[ -f "${CODEX_HOME}/plugins/cat-marketplace/.agents/plugins/marketplace.json" ]
	cmp "${INSTALL_DIRECTORY}/codex-marketplace.json" \
		"${CODEX_HOME}/plugins/cat-marketplace/.agents/plugins/marketplace.json"
	[ -f "${CODEX_HOME}/plugins/cat-marketplace/plugins/cat/.codex-plugin/plugin.json" ]
	[ -f "${CODEX_HOME}/agents/cat-reviewer.toml" ]
	grep -F "Review evidence without editing." "${CODEX_HOME}/agents/cat-reviewer.toml"
	[ "$(cat "${CODEX_HOME}/plugins/data/cat-cat/codex-agent-files")" = "cat-reviewer.toml" ]
	grep -F "codex plugin marketplace add ${CODEX_HOME}/plugins/cat-marketplace" "${COMMAND_LOG}"
	grep -F "codex plugin add cat@cat" "${COMMAND_LOG}"
	grep -F "plugin-info" "${COMMAND_LOG}"
	[ "$(cat "${CODEX_HOME}/plugins/data/cat-cat/migration-version")" = "1.4" ]
}

@test "Codex installs self-contained custom agents from an assembled artifact" {
	create_fake_engine_command codex
	artifact="$(create_test_artifact codex)"
	cat > "${artifact}/agents/codex/reviewer.toml" <<'SCRIPT'
name = "cat-reviewer"
developer_instructions = '''
Review evidence without editing.
'''
SCRIPT
	rm -rf "${artifact}/agents/common"

	run env PATH="${FAKE_BIN}:${PATH}" COMMAND_LOG="${COMMAND_LOG}" CODEX_HOME="${CODEX_HOME}" \
		sh "${INSTALL_DIRECTORY}/install-codex.sh" "${artifact}"

	[ "${status}" -eq 0 ]
	grep -F "Review evidence without editing." "${CODEX_HOME}/agents/cat-reviewer.toml"
}

@test "Codex installation runs each pending data migration before replacing the marketplace" {
	create_fake_engine_command codex
	artifact="$(create_test_artifact codex)"
	data_directory="${TEST_ROOT}/codex data"
	mkdir -p "${data_directory}"
	printf '0.9\n' > "${data_directory}/migration-version"
	migration_record='printf "%s\\n" "${CAT_MIGRATION_FROM_VERSION}->${CAT_MIGRATION_TO_VERSION}" >> "${COMMAND_LOG}"'
	add_test_migration "${artifact}" "0.10" "${migration_record}"
	add_test_migration "${artifact}" "1.0" "${migration_record}"

	run env PATH="${FAKE_BIN}:${PATH}" COMMAND_LOG="${COMMAND_LOG}" CODEX_HOME="${CODEX_HOME}" \
		CAT_CODEX_DATA_DIR="${data_directory}" sh "${INSTALL_DIRECTORY}/install-codex.sh" "${artifact}"

	[ "${status}" -eq 0 ]
	[ "$(sed -n '1p' "${COMMAND_LOG}")" = "0.9->0.10" ]
	[ "$(sed -n '2p' "${COMMAND_LOG}")" = "0.9->1.0" ]
	[ "$(cat "${data_directory}/migration-version")" = "1.4" ]
	grep -F "codex plugin marketplace add ${CODEX_HOME}/plugins/cat-marketplace" "${COMMAND_LOG}"
}

@test "Codex uninstall removes only CAT-owned user agents before native plugin removal" {
	create_fake_engine_command codex
	mkdir -p "${CODEX_HOME}/plugins/cat-marketplace" "${CODEX_HOME}/plugins/data/cat-cat" "${CODEX_HOME}/agents"
	printf 'cat agent\n' > "${CODEX_HOME}/agents/cat-reviewer.toml"
	printf 'user agent\n' > "${CODEX_HOME}/agents/reviewer.toml"
	printf 'cat-reviewer.toml\n' > "${CODEX_HOME}/plugins/data/cat-cat/codex-agent-files"

	run env PATH="${FAKE_BIN}:${PATH}" COMMAND_LOG="${COMMAND_LOG}" CODEX_HOME="${CODEX_HOME}" \
		sh "${INSTALL_DIRECTORY}/uninstall-codex.sh"

	[ "${status}" -eq 0 ]
	[ "$(sed -n '1p' "${COMMAND_LOG}")" = "codex plugin remove cat@cat" ]
	[ ! -e "${CODEX_HOME}/agents/cat-reviewer.toml" ]
	[ -f "${CODEX_HOME}/agents/reviewer.toml" ]
	[ ! -e "${CODEX_HOME}/plugins/data/cat-cat" ]
	[ ! -e "${CODEX_HOME}/plugins/cat-marketplace" ]
}

@test "Codex upgrade replaces recorded agents without overwriting unrelated agents" {
	create_fake_engine_command codex
	artifact="$(create_test_artifact codex)"
	mkdir -p "${CODEX_HOME}/agents"
	printf 'user agent\n' > "${CODEX_HOME}/agents/reviewer.toml"

	run env PATH="${FAKE_BIN}:${PATH}" COMMAND_LOG="${COMMAND_LOG}" CODEX_HOME="${CODEX_HOME}" \
		sh "${INSTALL_DIRECTORY}/install-codex.sh" "${artifact}"
	[ "${status}" -eq 0 ]
	printf 'old CAT agent\n' > "${CODEX_HOME}/agents/cat-reviewer.toml"

	run env PATH="${FAKE_BIN}:${PATH}" COMMAND_LOG="${COMMAND_LOG}" CODEX_HOME="${CODEX_HOME}" \
		sh "${INSTALL_DIRECTORY}/install-codex.sh" "${artifact}"

	[ "${status}" -eq 0 ]
	grep -F "Review evidence without editing." "${CODEX_HOME}/agents/cat-reviewer.toml"
	[ -f "${CODEX_HOME}/agents/reviewer.toml" ]
}

@test "release bootstrap selects the v1.4 Linux x64 Codex archive" {
	create_fake_engine_command codex
	cat > "${FAKE_BIN}/uname" <<'SCRIPT'
#!/usr/bin/env sh
if [ "${1:-}" = "-s" ]; then printf 'Linux\n'; else printf 'x86_64\n'; fi
SCRIPT
	cat > "${FAKE_BIN}/curl" <<'SCRIPT'
#!/usr/bin/env sh
printf 'curl %s\n' "$*" >> "${COMMAND_LOG}"
output=''
while [ "$#" -gt 0 ]; do
	if [ "$1" = "-o" ]; then shift; output="$1"; fi
	shift
done
case "${output}" in
	*/SHA256SUMS.txt) printf 'ignored  cat-codex-v1.4-linux-x64.tar.gz\n' > "${output}" ;;
	*) : > "${output}" ;;
esac
SCRIPT
	cat > "${FAKE_BIN}/sha256sum" <<'SCRIPT'
#!/usr/bin/env sh
exit 0
SCRIPT
	cat > "${FAKE_BIN}/tar" <<'SCRIPT'
#!/usr/bin/env sh
while [ "$#" -gt 0 ]; do
	if [ "$1" = "-C" ]; then shift; destination="$1"; fi
	shift
done
	root="${destination}/cat-codex-v1.4-linux-x64"
	mkdir -p "${root}/.codex-plugin" "${root}/client/bin" "${root}/install/migrations" \
		"${root}/agents/codex" "${root}/agents/common"
	printf '{}\n' > "${root}/.codex-plugin/plugin.json"
	printf 'name = "cat-reviewer"\n<!-- cat:include ../common/reviewer.md -->\n' > "${root}/agents/codex/reviewer.toml"
	printf 'review\n' > "${root}/agents/common/reviewer.md"
printf '1.4\n' > "${root}/client/VERSION"
printf '# target-version<TAB>script-relative-to-this-directory\n' > "${root}/install/migrations/registry.tsv"
cat > "${root}/client/bin/plugin-info" <<'INNER'
#!/usr/bin/env sh
exit 0
INNER
chmod +x "${root}/client/bin/plugin-info"
SCRIPT
	chmod +x "${FAKE_BIN}/uname" "${FAKE_BIN}/curl" "${FAKE_BIN}/sha256sum" "${FAKE_BIN}/tar"

	run env PATH="${FAKE_BIN}:${PATH}" COMMAND_LOG="${COMMAND_LOG}" CODEX_HOME="${CODEX_HOME}" \
		sh "${INSTALL_DIRECTORY}/install-codex.sh" v1.4

	[ "${status}" -eq 0 ]
	grep -F "https://github.com/holdmyspot-com/cat-client/releases/download/v1.4/cat-codex-v1.4-linux-x64.tar.gz" \
		"${COMMAND_LOG}"
	grep -F "https://github.com/holdmyspot-com/cat-client/releases/download/v1.4/SHA256SUMS.txt" "${COMMAND_LOG}"
}

@test "bootstrap propagates a download failure" {
	cat > "${FAKE_BIN}/uname" <<'SCRIPT'
#!/usr/bin/env sh
if [ "${1:-}" = "-s" ]; then printf 'Linux\n'; else printf 'x86_64\n'; fi
SCRIPT
	cat > "${FAKE_BIN}/curl" <<'SCRIPT'
#!/usr/bin/env sh
exit 23
SCRIPT
	chmod +x "${FAKE_BIN}/uname" "${FAKE_BIN}/curl"

	run env PATH="${FAKE_BIN}:${PATH}" sh "${INSTALL_DIRECTORY}/install-codex.sh" v1.4

	[ "${status}" -eq 23 ]
}

@test "release bootstrap selects the v1.4 macOS ARM64 archive" {
	cat > "${FAKE_BIN}/uname" <<'SCRIPT'
#!/usr/bin/env sh
if [ "${1:-}" = "-s" ]; then printf 'Darwin\n'; else printf 'arm64\n'; fi
SCRIPT
	cat > "${FAKE_BIN}/curl" <<'SCRIPT'
#!/usr/bin/env sh
printf 'curl %s\n' "$*" >> "${COMMAND_LOG}"
exit 23
SCRIPT
	chmod +x "${FAKE_BIN}/uname" "${FAKE_BIN}/curl"

	run env PATH="${FAKE_BIN}:${PATH}" COMMAND_LOG="${COMMAND_LOG}" \
		CAT_RELEASE_BASE_URL="https://example.invalid/releases/v1.4" sh \
		"${INSTALL_DIRECTORY}/install-codex.sh" v1.4

	[ "${status}" -eq 23 ]
	grep -F "https://example.invalid/releases/v1.4/cat-codex-v1.4-macos-arm64.tar.gz" "${COMMAND_LOG}"
}

@test "generated launchers work when the runtime path contains spaces" {
	runtime="${TEST_ROOT}/generated runtime"
	cp -R "${GENERATED_RUNTIME_DIRECTORY}/codex" "${runtime}"
	create_fake_bwrap

	run env PATH="${FAKE_BIN}:${PATH}" "${runtime}/client/bin/plugin-info"

	[ "${status}" -eq 0 ]
	[[ "${output}" == "harness=codex"$'\n'* ]]
}

@test "generated Codex SessionStart hook disables additional-context spilling" {
	runtime="${GENERATED_RUNTIME_DIRECTORY}/codex"

	grep -F '"additionalContextLimit": 0' "${runtime}/hooks/hooks.json"
}

@test "generated Codex SessionStart launcher injects bundled routing and project rules" {
	project="${TEST_ROOT}/generated session project"
	mkdir -p "${project}/.cat/rules/common"
	printf 'project session rule\n' > "${project}/.cat/rules/common/session.md"
	runtime="${GENERATED_RUNTIME_DIRECTORY}/codex"
	create_fake_bwrap
	save_recent_account_check

	run env PATH="${FAKE_BIN}:${PATH}" PLUGIN_ROOT="${runtime}" sh -c 'printf "%s\\n" "$1" | "$2"' sh \
		"{\"cwd\":\"${project}\"}" "${runtime}/hooks/session-start.sh"

	[ "${status}" -eq 0 ]
	context="$(printf '%s' "${output}" | jq -r '.hookSpecificOutput.additionalContext')"
	[[ "${context}" == *"Default Rule Routing"* ]]
	[[ "${context}" == *'"$CAT_PLUGIN_ROOT/client/bin/rule-load" include/java/exceptions.md'* ]]
	[[ "${context}" == *"project session rule"* ]]
}

@test "generated Codex SubagentStart launcher injects only addressed project rules" {
	project="${TEST_ROOT}/generated subagent project"
	mkdir -p "${project}/.cat/rules/codex"
	printf '%s\n' '---' 'agents: ["main"]' '---' 'main rule' > "${project}/.cat/rules/codex/main.md"
	printf '%s\n' '---' 'agents: ["cat:reviewer"]' '---' 'reviewer rule' > "${project}/.cat/rules/codex/reviewer.md"
	runtime="${GENERATED_RUNTIME_DIRECTORY}/codex"
	create_fake_bwrap
	save_recent_account_check

	run env PATH="${FAKE_BIN}:${PATH}" PLUGIN_ROOT="${runtime}" sh -c 'printf "%s\\n" "$1" | "$2"' sh \
		"{\"cwd\":\"${project}\",\"agent_type\":\"cat:reviewer\"}" "${runtime}/hooks/subagent-start.sh"

	[ "${status}" -eq 0 ]
	[[ "${output}" == *'"hookEventName":"SubagentStart"'* ]]
	[[ "${output}" == *"reviewer rule"* ]]
	[[ "${output}" != *"main rule"* ]]
}
