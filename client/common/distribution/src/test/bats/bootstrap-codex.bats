#!/usr/bin/env bats

# Tests Codex plugin installation, removal, release bootstrapping, and generated hook behavior.

load bootstrap-helpers

@test "Codex installer reports a missing shared library" {
	standalone_directory="${TEST_ROOT}/standalone Codex installer"
	mkdir -p "${standalone_directory}"
	cp "${INSTALL_DIRECTORY}/install-codex.sh" "${standalone_directory}/install-codex.sh"

	run sh "${standalone_directory}/install-codex.sh"

	[ "${status}" -eq 1 ]
	[[ "${output}" == *"CAT installer library not found"* ]]
}

@test "Codex installer on Windows names the PowerShell installer before changing anything" {
	create_fake_engine_command codex
	create_fake_uname MINGW64_NT-10.0-26100
	artifact="$(create_test_artifact codex)"

	run env PATH="${FAKE_BIN}:${PATH}" COMMAND_LOG="${COMMAND_LOG}" CODEX_HOME="${CODEX_HOME}" \
		sh "${INSTALL_DIRECTORY}/install-codex.sh" "${artifact}"

	[ "${status}" -eq 1 ]
	[[ "${output}" == *"bootstrap-codex.ps1"* ]]
	[ ! -e "${CODEX_HOME}/plugins" ]
	[ ! -s "${COMMAND_LOG}" ]
}

@test "generated Codex plugin-info launcher reports its harness, version, and platform" {
	expected_platform="$(current_platform)"

	run "${GENERATED_RUNTIME_DIRECTORY}/codex/client/bin/plugin-info"

	[ "${status}" -eq 0 ]
	[ "${output}" = "harness=codex
version=1.0
platform=${expected_platform}" ]
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
	[ "$(cat "${CODEX_HOME}/plugins/data/cat-cat/migration-version")" = "1.0" ]
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
	[ "$(cat "${data_directory}/migration-version")" = "1.0" ]
	grep -F "codex plugin marketplace add ${CODEX_HOME}/plugins/cat-marketplace" "${COMMAND_LOG}"
}

@test "Codex installation preserves the recorded version when a data migration fails" {
	create_fake_engine_command codex
	artifact="$(create_test_artifact codex)"
	data_directory="${TEST_ROOT}/codex data"
	mkdir -p "${data_directory}"
	printf '0.9\n' > "${data_directory}/migration-version"
	add_test_migration "${artifact}" "1.0" 'exit 7'

	run env PATH="${FAKE_BIN}:${PATH}" COMMAND_LOG="${COMMAND_LOG}" CODEX_HOME="${CODEX_HOME}" \
		CAT_CODEX_DATA_DIR="${data_directory}" sh "${INSTALL_DIRECTORY}/install-codex.sh" "${artifact}"

	[ "${status}" -eq 7 ]
	[ "$(cat "${data_directory}/migration-version")" = "0.9" ]
	[ ! -e "${CODEX_HOME}/plugins/cat-marketplace" ]
	[ ! -s "${COMMAND_LOG}" ]
}

@test "Codex uninstall removes only CAT-owned user agents before running codex plugin remove" {
	create_fake_engine_command codex
	mkdir -p "${CODEX_HOME}/plugins/cat-marketplace" "${CODEX_HOME}/plugins/data/cat-cat" "${CODEX_HOME}/agents"
	printf 'cat agent\n' > "${CODEX_HOME}/agents/cat-reviewer.toml"
	printf 'user agent\n' > "${CODEX_HOME}/agents/reviewer.toml"
	printf 'cat-reviewer.toml\n' > "${CODEX_HOME}/plugins/data/cat-cat/codex-agent-files"
	printf '0.9\n' > "${CODEX_HOME}/plugins/data/cat-cat/migration-version"

	run env PATH="${FAKE_BIN}:${PATH}" COMMAND_LOG="${COMMAND_LOG}" CODEX_HOME="${CODEX_HOME}" \
		sh "${INSTALL_DIRECTORY}/uninstall-codex.sh"

	[ "${status}" -eq 0 ]
	[ "$(sed -n '1p' "${COMMAND_LOG}")" = "codex plugin remove cat@cat" ]
	[ ! -e "${CODEX_HOME}/agents/cat-reviewer.toml" ]
	[ -f "${CODEX_HOME}/agents/reviewer.toml" ]
	[ ! -e "${CODEX_HOME}/plugins/data/cat-cat/codex-agent-files" ]
	[ "$(cat "${CODEX_HOME}/plugins/data/cat-cat/migration-version")" = "0.9" ]
	[ ! -e "${CODEX_HOME}/plugins/cat-marketplace" ]
}

@test "Codex uninstall deletes CAT's data only when asked to" {
	create_fake_engine_command codex
	mkdir -p "${CODEX_HOME}/plugins/cat-marketplace" "${CODEX_HOME}/plugins/data/cat-cat"
	printf '0.9\n' > "${CODEX_HOME}/plugins/data/cat-cat/migration-version"

	run env PATH="${FAKE_BIN}:${PATH}" COMMAND_LOG="${COMMAND_LOG}" CODEX_HOME="${CODEX_HOME}" \
		sh "${INSTALL_DIRECTORY}/uninstall-codex.sh" --delete-data

	[ "${status}" -eq 0 ]
	[ ! -e "${CODEX_HOME}/plugins/data/cat-cat" ]
	[ ! -e "${CODEX_HOME}/plugins/cat-marketplace" ]
}

@test "Codex uninstall rejects an unknown argument before removing anything" {
	create_fake_engine_command codex
	mkdir -p "${CODEX_HOME}/plugins/cat-marketplace" "${CODEX_HOME}/plugins/data/cat-cat"

	run env PATH="${FAKE_BIN}:${PATH}" COMMAND_LOG="${COMMAND_LOG}" CODEX_HOME="${CODEX_HOME}" \
		sh "${INSTALL_DIRECTORY}/uninstall-codex.sh" --delete

	[ "${status}" -eq 2 ]
	[[ "${output}" == *"Usage:"*"[--delete-data]"* ]]
	[ -d "${CODEX_HOME}/plugins/data/cat-cat" ]
	[ -d "${CODEX_HOME}/plugins/cat-marketplace" ]
	[ ! -s "${COMMAND_LOG}" ]
}

@test "release uninstall bootstrap passes --delete-data to the Codex uninstaller" {
	create_fake_engine_command codex
	create_fake_release codex > /dev/null
	cp "${INSTALL_DIRECTORY}/common.sh" "${INSTALL_DIRECTORY}/uninstall-codex.sh" "${FAKE_RELEASE_DIRECTORY}/"
	mkdir -p "${CODEX_HOME}/plugins/cat-marketplace" "${CODEX_HOME}/plugins/data/cat-cat"
	printf '0.9\n' > "${CODEX_HOME}/plugins/data/cat-cat/migration-version"

	run env PATH="${FAKE_BIN}:${PATH}" COMMAND_LOG="${COMMAND_LOG}" CODEX_HOME="${CODEX_HOME}" \
		FAKE_RELEASE_DIRECTORY="${FAKE_RELEASE_DIRECTORY}" \
		sh "${INSTALL_DIRECTORY}/bootstrap-uninstall-codex.sh" --delete-data

	[ "${status}" -eq 0 ]
	grep -Fx "codex plugin remove cat@cat" "${COMMAND_LOG}"
	[ ! -e "${CODEX_HOME}/plugins/data/cat-cat" ]
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

@test "release bootstrap installs the Codex archive for the current platform after verifying its checksum" {
	create_fake_engine_command codex
	archive="$(create_fake_release codex)"

	run env PATH="${FAKE_BIN}:${PATH}" COMMAND_LOG="${COMMAND_LOG}" CODEX_HOME="${CODEX_HOME}" \
		sh "${INSTALL_DIRECTORY}/install-codex.sh" v1.0

	[ "${status}" -eq 0 ]
	grep -F "https://github.com/cowwoc/cat.client/releases/download/v1.0/${archive}" "${COMMAND_LOG}"
	grep -F "https://github.com/cowwoc/cat.client/releases/download/v1.0/SHA256SUMS.txt" "${COMMAND_LOG}"
	[ -f "${CODEX_HOME}/plugins/cat-marketplace/plugins/cat/.codex-plugin/plugin.json" ]
}

@test "release bootstrap rejects a Codex archive whose checksum does not match before installing it" {
	create_fake_engine_command codex
	archive="$(create_fake_release codex)"
	printf '%064d  %s\n' 0 "${archive}" > "${FAKE_RELEASE_DIRECTORY}/SHA256SUMS.txt"

	run env PATH="${FAKE_BIN}:${PATH}" COMMAND_LOG="${COMMAND_LOG}" CODEX_HOME="${CODEX_HOME}" \
		sh "${INSTALL_DIRECTORY}/install-codex.sh" v1.0

	[ "${status}" -ne 0 ]
	[[ "${output}" == *"FAILED"* ]]
	[ ! -e "${CODEX_HOME}/plugins/cat-marketplace" ]
	! grep -q '^codex ' "${COMMAND_LOG}"
}
@test "generated Codex SessionStart launcher injects bundled routing and project rules" {
	project="${TEST_ROOT}/generated session project"
	mkdir -p "${project}/.cat/rules/common"
	printf 'project session rule\n' > "${project}/.cat/rules/common/session.md"
	runtime="${GENERATED_RUNTIME_DIRECTORY}/codex"
	create_fake_bwrap

	run env PATH="${FAKE_BIN}:${PATH}" PLUGIN_ROOT="${runtime}" sh -c 'printf "%s\\n" "$1" | "$2"' sh \
		"{\"cwd\":\"${project}\",\"session_id\":\"123e4567-e89b-12d3-a456-426614174000\"}" \
		"${runtime}/hooks/session-start.sh"

	[ "${status}" -eq 0 ]
	[[ "${output}" == *'"hookSpecificOutput"'* ]]
	context_file=$(printf '%s\n' "${output}" | sed -n 's/.*rules for this session are in `\([^`]*\)`.*/\1/p')
	grep -F "Default Rule Routing" "${context_file}"
	grep -F "When handling Java exceptions, lazy load" "${context_file}"
	grep -F "/rules/include/java/exceptions.md" "${context_file}"
	grep -F "project session rule" "${context_file}"
}

@test "generated Codex SubagentStart launcher injects only addressed project rules" {
	project="${TEST_ROOT}/generated subagent project"
	mkdir -p "${project}/.cat/rules/codex"
	printf '%s\n' '---' 'agents: ["main"]' '---' 'main rule' > "${project}/.cat/rules/codex/main.md"
	printf '%s\n' '---' 'agents: ["cat:reviewer"]' '---' 'reviewer rule' > "${project}/.cat/rules/codex/reviewer.md"
	runtime="${GENERATED_RUNTIME_DIRECTORY}/codex"
	create_fake_bwrap

	run env PATH="${FAKE_BIN}:${PATH}" PLUGIN_ROOT="${runtime}" sh -c 'printf "%s\\n" "$1" | "$2"' sh \
		"{\"cwd\":\"${project}\",\"agent_type\":\"cat:reviewer\"}" "${runtime}/hooks/subagent-start.sh"

	[ "${status}" -eq 0 ]
	[[ "${output}" == *'"hookEventName":"SubagentStart"'* ]]
	[[ "${output}" == *"reviewer rule"* ]]
	[[ "${output}" != *"main rule"* ]]
}
