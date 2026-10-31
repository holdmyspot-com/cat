#!/usr/bin/env bats

# Tests Claude plugin installation, removal, release bootstrapping, and generated hook behavior.

load bootstrap-helpers

@test "Claude installer reports a missing shared library" {
	standalone_directory="${TEST_ROOT}/standalone Claude installer"
	mkdir -p "${standalone_directory}"
	cp "${INSTALL_DIRECTORY}/install-claude.sh" "${standalone_directory}/install-claude.sh"

	run sh "${standalone_directory}/install-claude.sh"

	[ "${status}" -eq 1 ]
	[[ "${output}" == *"CAT installer library not found"* ]]
}

@test "generated Claude plugin-info launcher reports its harness, version, and platform" {
	expected_platform="$(current_platform)"

	run "${GENERATED_RUNTIME_DIRECTORY}/claude/bin/plugin-info"

	[ "${status}" -eq 0 ]
	[ "${output}" = "harness=claude
version=1.0
platform=${expected_platform}" ]
}

@test "Claude installs an extracted artifact through an artifact-local marketplace" {
	create_fake_engine_command claude
	artifact="$(create_test_artifact claude)"

	run env PATH="${FAKE_BIN}:${PATH}" COMMAND_LOG="${COMMAND_LOG}" CLAUDE_CONFIG_DIR="${CLAUDE_CONFIG_DIR}" \
		sh "${INSTALL_DIRECTORY}/install-claude.sh" "${artifact}"

	[ "${status}" -eq 0 ]
	[ -f "${CLAUDE_CONFIG_DIR}/plugins/cat-marketplace/.claude-plugin/marketplace.json" ]
	cmp "${INSTALL_DIRECTORY}/claude-marketplace.json" \
		"${CLAUDE_CONFIG_DIR}/plugins/cat-marketplace/.claude-plugin/marketplace.json"
	[ -f "${CLAUDE_CONFIG_DIR}/plugins/cat-marketplace/plugins/cat/.claude-plugin/plugin.json" ]
	grep -F "claude plugin marketplace add ${CLAUDE_CONFIG_DIR}/plugins/cat-marketplace" "${COMMAND_LOG}"
	grep -F "claude plugin install cat@cat" "${COMMAND_LOG}"
	[ "$(cat "${CLAUDE_CONFIG_DIR}/plugins/data/cat-cat/migration-version")" = "1.0" ]
}

@test "Claude installation preserves the recorded version when a data migration fails" {
	create_fake_engine_command claude
	artifact="$(create_test_artifact claude)"
	data_directory="${TEST_ROOT}/claude data"
	mkdir -p "${data_directory}"
	printf '0.9\n' > "${data_directory}/migration-version"
	add_test_migration "${artifact}" "1.0" 'exit 7'

	run env PATH="${FAKE_BIN}:${PATH}" COMMAND_LOG="${COMMAND_LOG}" CLAUDE_CONFIG_DIR="${CLAUDE_CONFIG_DIR}" \
		CAT_CLAUDE_DATA_DIR="${data_directory}" sh "${INSTALL_DIRECTORY}/install-claude.sh" "${artifact}"

	[ "${status}" -eq 7 ]
	[ "$(cat "${data_directory}/migration-version")" = "0.9" ]
	[ ! -e "${CLAUDE_CONFIG_DIR}/plugins/cat-marketplace" ]
	[ ! -s "${COMMAND_LOG}" ]
}

@test "Claude installation runs each pending data migration before replacing the marketplace" {
	create_fake_engine_command claude
	artifact="$(create_test_artifact claude)"
	data_directory="${TEST_ROOT}/claude data"
	mkdir -p "${data_directory}"
	printf '0.9\n' > "${data_directory}/migration-version"
	migration_record='printf "%s\\n" "${CAT_MIGRATION_FROM_VERSION}->${CAT_MIGRATION_TO_VERSION}" >> "${COMMAND_LOG}"'
	add_test_migration "${artifact}" "0.10" "${migration_record}"
	add_test_migration "${artifact}" "1.0" "${migration_record}"

	run env PATH="${FAKE_BIN}:${PATH}" COMMAND_LOG="${COMMAND_LOG}" CLAUDE_CONFIG_DIR="${CLAUDE_CONFIG_DIR}" \
		CAT_CLAUDE_DATA_DIR="${data_directory}" sh "${INSTALL_DIRECTORY}/install-claude.sh" "${artifact}"

	[ "${status}" -eq 0 ]
	[ "$(sed -n '1p' "${COMMAND_LOG}")" = "0.9->0.10" ]
	[ "$(sed -n '2p' "${COMMAND_LOG}")" = "0.9->1.0" ]
	[ "$(cat "${data_directory}/migration-version")" = "1.0" ]
	grep -F "claude plugin marketplace add ${CLAUDE_CONFIG_DIR}/plugins/cat-marketplace" "${COMMAND_LOG}"
}

@test "Claude removal delegates to claude plugin uninstall" {
	create_fake_engine_command claude
	mkdir -p "${CLAUDE_CONFIG_DIR}/plugins/cat-marketplace"

	run env PATH="${FAKE_BIN}:${PATH}" COMMAND_LOG="${COMMAND_LOG}" CLAUDE_CONFIG_DIR="${CLAUDE_CONFIG_DIR}" \
		sh "${INSTALL_DIRECTORY}/uninstall-claude.sh"

	[ "${status}" -eq 0 ]
	[ "$(sed -n '1p' "${COMMAND_LOG}")" = "claude plugin uninstall cat@cat" ]
	[ ! -e "${CLAUDE_CONFIG_DIR}/plugins/cat-marketplace" ]
}

@test "Claude removal deletes the downloaded bundles and keeps the rest of CAT's user directory" {
	create_fake_engine_command claude
	mkdir -p "${CLAUDE_CONFIG_DIR}/plugins/cat-marketplace" "${HOME}/.cat/bundles/cat-codex-v1.0-linux-x64/bin" \
		"${HOME}/.cat/rules"
	printf 'rule\n' > "${HOME}/.cat/rules/user.md"

	run env PATH="${FAKE_BIN}:${PATH}" COMMAND_LOG="${COMMAND_LOG}" CLAUDE_CONFIG_DIR="${CLAUDE_CONFIG_DIR}" \
		sh "${INSTALL_DIRECTORY}/uninstall-claude.sh"

	[ "${status}" -eq 0 ]
	[ ! -e "${HOME}/.cat/bundles" ]
	[ -f "${HOME}/.cat/rules/user.md" ]
}

@test "release bootstrap installs the Claude archive for the current platform after verifying its checksum" {
	create_fake_engine_command claude
	archive="$(create_fake_release claude)"

	run env PATH="${FAKE_BIN}:${PATH}" COMMAND_LOG="${COMMAND_LOG}" CLAUDE_CONFIG_DIR="${CLAUDE_CONFIG_DIR}" \
		sh "${INSTALL_DIRECTORY}/install-claude.sh" v1.0

	[ "${status}" -eq 0 ]
	grep -F "https://github.com/holdmyspot-com/cat/releases/download/v1.0/${archive}" "${COMMAND_LOG}"
	grep -F "https://github.com/holdmyspot-com/cat/releases/download/v1.0/SHA256SUMS.txt" "${COMMAND_LOG}"
	[ -f "${CLAUDE_CONFIG_DIR}/plugins/cat-marketplace/plugins/cat/.claude-plugin/plugin.json" ]
}

@test "release bootstrap rejects a Claude archive whose checksum does not match before installing it" {
	create_fake_engine_command claude
	archive="$(create_fake_release claude)"
	printf '%064d  %s\n' 0 "${archive}" > "${FAKE_RELEASE_DIRECTORY}/SHA256SUMS.txt"

	run env PATH="${FAKE_BIN}:${PATH}" COMMAND_LOG="${COMMAND_LOG}" CLAUDE_CONFIG_DIR="${CLAUDE_CONFIG_DIR}" \
		sh "${INSTALL_DIRECTORY}/install-claude.sh" v1.0

	[ "${status}" -ne 0 ]
	[[ "${output}" == *"FAILED"* ]]
	[ ! -e "${CLAUDE_CONFIG_DIR}/plugins/cat-marketplace" ]
	! grep -q '^claude ' "${COMMAND_LOG}"
}

@test "generated Claude SessionStart launcher injects bundled routing and project rules" {
	project="${TEST_ROOT}/generated session project"
	mkdir -p "${project}/.cat/rules/common"
	printf 'project session rule\n' > "${project}/.cat/rules/common/session.md"
	runtime="${GENERATED_RUNTIME_DIRECTORY}/claude"

	run env PATH="${FAKE_BIN}:${PATH}" CLAUDE_PLUGIN_ROOT="${runtime}" sh -c 'printf "%s\\n" "$1" | "$2"' sh \
		"{\"cwd\":\"$(json_path "${project}")\",\"session_id\":\"123e4567-e89b-12d3-a456-426614174000\"}" "${runtime}/hooks/session-start.sh"

	[ "${status}" -eq 0 ]
	[[ "${output}" == *'"hookEventName":"SessionStart"'* ]]
	context_file=$(printf '%s\n' "${output}" | sed -n 's/.*Read the whole file `\([^`]*\)`.*/\1/p')
	grep -F "CAT Rule Routing" "${context_file}"
	grep -F "when selecting a platform or available project API" "${context_file}"
	grep -F "/rules/include/common/rule-routing/interfaces.md" "${context_file}"
	grep -F "project session rule" "${context_file}"
}

@test "generated Claude SubagentStart launcher injects only addressed project rules" {
	project="${TEST_ROOT}/generated subagent project"
	mkdir -p "${project}/.cat/rules/claude"
	printf '%s\n' '---' 'agents: ["main"]' '---' 'main rule' > "${project}/.cat/rules/claude/main.md"
	printf '%s\n' '---' 'agents: ["cat:reviewer"]' '---' 'reviewer rule' > "${project}/.cat/rules/claude/reviewer.md"
	runtime="${GENERATED_RUNTIME_DIRECTORY}/claude"

	run env PATH="${FAKE_BIN}:${PATH}" CLAUDE_PLUGIN_ROOT="${runtime}" sh -c 'printf "%s\\n" "$1" | "$2"' sh \
		"{\"cwd\":\"$(json_path "${project}")\",\"agent_type\":\"cat:reviewer\",\"session_id\":\"123e4567-e89b-12d3-a456-426614174001\",\"agent_id\":\"reviewer-test\"}" "${runtime}/hooks/subagent-start.sh"

	[ "${status}" -eq 0 ]
	[[ "${output}" == *'"hookEventName":"SubagentStart"'* ]]
	context_file=$(printf '%s\n' "${output}" | sed -n 's/.*Read the whole file `\([^`]*\)`.*/\1/p')
	grep -F "reviewer rule" "${context_file}"
	! grep -F "main rule" "${context_file}"
}

@test "single command changes nothing and gives the command to run outside the sandbox when Claude's settings are read-only" {
	create_fake_engine_command claude
	create_fake_release claude > /dev/null
	publish_install_scripts
	export PATH="${FAKE_BIN}:${PATH}" CLAUDECODE=1
	install_command="curl -fsSL ${SINGLE_COMMAND_URL} | CLAUDE_CONFIG_DIR='${CLAUDE_CONFIG_DIR}' sh -s -- --harness claude \
v1.0"
	uninstall_command="curl -fsSL ${SINGLE_COMMAND_URL/v1.0/HEAD} | CLAUDE_CONFIG_DIR='${CLAUDE_CONFIG_DIR}' sh -s -- \
--harness claude --uninstall"
	mkdir -p "${CLAUDE_CONFIG_DIR}"
	printf '{}\n' > "${CLAUDE_CONFIG_DIR}/settings.json"
	make_read_only "${CLAUDE_CONFIG_DIR}/settings.json"

	expect_unchanged_by_single_command "${CLAUDE_CONFIG_DIR}/settings.json" "${install_command}" v1.0

	chmod u+w "${CLAUDE_CONFIG_DIR}/settings.json"
	run single_command v1.0
	[ "${status}" -eq 0 ]
	[ -d "${CLAUDE_CONFIG_DIR}/plugins/cat-marketplace" ]
	make_read_only "${CLAUDE_CONFIG_DIR}/settings.json"

	expect_unchanged_by_single_command "${CLAUDE_CONFIG_DIR}/settings.json" "${install_command}" v1.0
	expect_unchanged_by_single_command "${CLAUDE_CONFIG_DIR}/settings.json" "${uninstall_command}" --uninstall
}

@test "single command changes nothing when the directory that holds Claude's marketplace is read-only" {
	create_fake_engine_command claude
	create_fake_release claude > /dev/null
	publish_install_scripts
	export PATH="${FAKE_BIN}:${PATH}" CLAUDECODE=1
	install_command="curl -fsSL ${SINGLE_COMMAND_URL} | CLAUDE_CONFIG_DIR='${CLAUDE_CONFIG_DIR}' sh -s -- --harness claude \
v1.0"
	uninstall_command="curl -fsSL ${SINGLE_COMMAND_URL/v1.0/HEAD} | CLAUDE_CONFIG_DIR='${CLAUDE_CONFIG_DIR}' sh -s -- \
--harness claude --uninstall"
	mkdir -p "${CLAUDE_CONFIG_DIR}/plugins"
	make_read_only "${CLAUDE_CONFIG_DIR}/plugins"

	expect_unchanged_by_single_command "${CLAUDE_CONFIG_DIR}/plugins" "${install_command}" v1.0

	chmod u+w "${CLAUDE_CONFIG_DIR}/plugins"
	run single_command v1.0
	[ "${status}" -eq 0 ]
	make_read_only "${CLAUDE_CONFIG_DIR}/plugins"

	expect_unchanged_by_single_command "${CLAUDE_CONFIG_DIR}/plugins" "${install_command}" v1.0
	expect_unchanged_by_single_command "${CLAUDE_CONFIG_DIR}/plugins" "${uninstall_command}" --uninstall
}

@test "single command reaches the same Claude Code state as the Claude bootstrap scripts" {
	create_fake_engine_command claude
	create_fake_release claude > /dev/null
	publish_install_scripts
	export PATH="${FAKE_BIN}:${PATH}"
	single_record="${TEST_ROOT}/single command record"
	bootstrap_record="${TEST_ROOT}/bootstrap record"

	record_operation "${single_record}" single_command --harness claude v1.0
	record_operation "${single_record}" single_command --harness claude v1.0
	record_operation "${single_record}" single_command --harness claude --uninstall
	rm -rf "${HOME}"
	mkdir -p "${HOME}"
	record_operation "${bootstrap_record}" sh "${INSTALL_DIRECTORY}/bootstrap-claude.sh" v1.0
	record_operation "${bootstrap_record}" sh "${INSTALL_DIRECTORY}/bootstrap-claude.sh" v1.0
	record_operation "${bootstrap_record}" sh "${INSTALL_DIRECTORY}/bootstrap-uninstall-claude.sh"

	diff "${bootstrap_record}" "${single_record}"
	grep -Fx "claude plugin install cat@cat" "${single_record}"
	grep -Fx "claude plugin uninstall cat@cat" "${single_record}"
}

@test "the installed plugin's single command uninstalls CAT from Claude Code without downloading anything" {
	create_fake_engine_command claude
	artifact="$(create_test_artifact claude)"
	cp "${INSTALL_DIRECTORY}"/*.sh "${artifact}/install/"
	run env PATH="${FAKE_BIN}:${PATH}" sh "${INSTALL_DIRECTORY}/install-claude.sh" "${artifact}"
	[ "${status}" -eq 0 ]
	installed_plugin="${CLAUDE_CONFIG_DIR}/plugins/cat-marketplace/plugins/cat"
	printf '#!/bin/sh\nprintf "curl %%s\\n" "$*" >> "${COMMAND_LOG}"\nexit 22\n' > "${FAKE_BIN}/curl"
	chmod +x "${FAKE_BIN}/curl"
	: > "${COMMAND_LOG}"

	run env PATH="${FAKE_BIN}:${PATH}" CLAUDECODE=1 sh "${installed_plugin}/install/cat.sh" --uninstall

	[ "${status}" -eq 0 ]
	grep -Fx "claude plugin uninstall cat@cat" "${COMMAND_LOG}"
	! grep -q '^curl ' "${COMMAND_LOG}" || false
	[ ! -e "${CLAUDE_CONFIG_DIR}/plugins/cat-marketplace" ]
}

@test "the installed plugin's single command updates CAT to the release that it names and leaves no temporary files" {
	create_fake_engine_command claude
	create_fake_release claude > /dev/null
	publish_install_scripts
	artifact="$(create_test_artifact claude)"
	cp "${INSTALL_DIRECTORY}"/*.sh "${artifact}/install/"
	run env PATH="${FAKE_BIN}:${PATH}" sh "${INSTALL_DIRECTORY}/install-claude.sh" "${artifact}"
	[ "${status}" -eq 0 ]
	installed_plugin="${CLAUDE_CONFIG_DIR}/plugins/cat-marketplace/plugins/cat"
	temporary="${TEST_ROOT}/temporary"
	mkdir -p "${temporary}"
	: > "${COMMAND_LOG}"

	run env PATH="${FAKE_BIN}:${PATH}" CLAUDECODE=1 TMPDIR="${temporary}" sh "${installed_plugin}/install/cat.sh" v1.0

	[ "${status}" -eq 0 ]
	grep -F "/v1.0/client/claude/lifecycle/src/main/resources/install/install-claude.sh" "${COMMAND_LOG}"
	grep -F "/releases/download/v1.0/cat-claude-v1.0-" "${COMMAND_LOG}"
	grep -Fx "claude plugin install cat@cat" "${COMMAND_LOG}"
	[ -f "${installed_plugin}/.claude-plugin/plugin.json" ]
	[ -z "$(ls -A "${temporary}")" ]
}
