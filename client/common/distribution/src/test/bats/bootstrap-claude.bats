#!/usr/bin/env bats

# Tests Claude plugin installation and removal, directly and through the release's entry script, and generated hook
# behavior.

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
	grep -F "claude plugin marketplace add ${CLAUDE_CONFIG_DIR}/plugins/cat-marketplace" "${COMMAND_LOG}"
}

@test "Claude removal delegates to claude plugin uninstall and deletes the marketplace and the downloaded bundles" {
	create_fake_engine_command claude
	mkdir -p "${CLAUDE_CONFIG_DIR}/plugins/cat-marketplace" "${HOME}/.cat/bundles/cat-codex-v1.0-linux-x64/bin"

	run env PATH="${FAKE_BIN}:${PATH}" COMMAND_LOG="${COMMAND_LOG}" CLAUDE_CONFIG_DIR="${CLAUDE_CONFIG_DIR}" \
		sh "${INSTALL_DIRECTORY}/uninstall-claude.sh"

	[ "${status}" -eq 0 ]
	[ "$(sed -n '1p' "${COMMAND_LOG}")" = "claude plugin uninstall cat@cat" ]
	[ ! -e "${CLAUDE_CONFIG_DIR}/plugins/cat-marketplace" ]
	[ ! -e "${HOME}/.cat/bundles" ]
}

@test "generated Claude SessionStart launcher injects the packaged routing and the project's rules" {
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
	grep -F "project session rule" "${context_file}"
}

@test "generated Claude SubagentStart launcher injects the project rule addressed to the subagent" {
	project="${TEST_ROOT}/generated subagent project"
	mkdir -p "${project}/.cat/rules/claude"
	printf '%s\n' '---' 'agents: ["cat:reviewer"]' '---' 'reviewer rule' > "${project}/.cat/rules/claude/reviewer.md"
	runtime="${GENERATED_RUNTIME_DIRECTORY}/claude"

	run env PATH="${FAKE_BIN}:${PATH}" CLAUDE_PLUGIN_ROOT="${runtime}" sh -c 'printf "%s\\n" "$1" | "$2"' sh \
		"{\"cwd\":\"$(json_path "${project}")\",\"agent_type\":\"cat:reviewer\",\"session_id\":\"123e4567-e89b-12d3-a456-426614174001\",\"agent_id\":\"reviewer-test\"}" "${runtime}/hooks/subagent-start.sh"

	[ "${status}" -eq 0 ]
	[[ "${output}" == *'"hookEventName":"SubagentStart"'* ]]
	context_file=$(printf '%s\n' "${output}" | sed -n 's/.*Read the whole file `\([^`]*\)`.*/\1/p')
	grep -F "reviewer rule" "${context_file}"
}

@test "single command changes nothing and gives the command to run outside the sandbox when Claude's settings are read-only" {
	create_fake_engine_command claude
	create_fake_release claude shipped > /dev/null
	export PATH="${FAKE_BIN}:${PATH}" CLAUDECODE=1
	install_command="curl -fsSL ${SINGLE_COMMAND_URL} | CLAUDE_CONFIG_DIR='${CLAUDE_CONFIG_DIR}' sh -s -- --harness claude"
	uninstall_command="curl -fsSL ${SINGLE_COMMAND_URL} | CLAUDE_CONFIG_DIR='${CLAUDE_CONFIG_DIR}' sh -s -- \
--harness claude --uninstall"
	mkdir -p "${CLAUDE_CONFIG_DIR}"
	printf '{}\n' > "${CLAUDE_CONFIG_DIR}/settings.json"
	make_read_only "${CLAUDE_CONFIG_DIR}/settings.json"

	expect_unchanged_by_single_command claude "${CLAUDE_CONFIG_DIR}/settings.json" "${install_command}"

	chmod u+w "${CLAUDE_CONFIG_DIR}/settings.json"
	run single_command
	[ "${status}" -eq 0 ]
	[ -d "${CLAUDE_CONFIG_DIR}/plugins/cat-marketplace" ]
	make_read_only "${CLAUDE_CONFIG_DIR}/settings.json"

	expect_unchanged_by_single_command claude "${CLAUDE_CONFIG_DIR}/settings.json" "${install_command}"
	expect_unchanged_by_single_command claude "${CLAUDE_CONFIG_DIR}/settings.json" "${uninstall_command}" --uninstall
}

@test "single command changes nothing when the directory that holds Claude's marketplace is read-only" {
	create_fake_engine_command claude
	create_fake_release claude shipped > /dev/null
	export PATH="${FAKE_BIN}:${PATH}" CLAUDECODE=1
	install_command="curl -fsSL ${SINGLE_COMMAND_URL} | CLAUDE_CONFIG_DIR='${CLAUDE_CONFIG_DIR}' sh -s -- --harness claude"
	uninstall_command="curl -fsSL ${SINGLE_COMMAND_URL} | CLAUDE_CONFIG_DIR='${CLAUDE_CONFIG_DIR}' sh -s -- \
--harness claude --uninstall"
	mkdir -p "${CLAUDE_CONFIG_DIR}/plugins"
	make_read_only "${CLAUDE_CONFIG_DIR}/plugins"

	expect_unchanged_by_single_command claude "${CLAUDE_CONFIG_DIR}/plugins" "${install_command}"

	chmod u+w "${CLAUDE_CONFIG_DIR}/plugins"
	run single_command
	[ "${status}" -eq 0 ]
	make_read_only "${CLAUDE_CONFIG_DIR}/plugins"

	expect_unchanged_by_single_command claude "${CLAUDE_CONFIG_DIR}/plugins" "${install_command}"
	expect_unchanged_by_single_command claude "${CLAUDE_CONFIG_DIR}/plugins" "${uninstall_command}" --uninstall
}

# Without CLAUDE_CONFIG_DIR, Claude Code keeps .claude.json directly in the home directory, outside .claude
@test "single command changes nothing when the home directory that holds Claude's .claude.json is read-only" {
	create_fake_engine_command claude
	create_fake_release claude shipped > /dev/null
	export PATH="${FAKE_BIN}:${PATH}" CLAUDECODE=1
	unset CLAUDE_CONFIG_DIR
	install_command="curl -fsSL ${SINGLE_COMMAND_URL} | sh -s -- --harness claude"
	mkdir -p "${HOME}/.claude"
	make_read_only "${HOME}"

	expect_unchanged_by_single_command claude "${HOME}" "${install_command}"
}

@test "single command changes nothing when the .claude.json in Claude's configuration directory is read-only" {
	create_fake_engine_command claude
	create_fake_release claude shipped > /dev/null
	export PATH="${FAKE_BIN}:${PATH}" CLAUDECODE=1
	uninstall_command="curl -fsSL ${SINGLE_COMMAND_URL} | CLAUDE_CONFIG_DIR='${CLAUDE_CONFIG_DIR}' sh -s -- \
--harness claude --uninstall"
	mkdir -p "${CLAUDE_CONFIG_DIR}"
	printf '{}\n' > "${CLAUDE_CONFIG_DIR}/.claude.json"
	make_read_only "${CLAUDE_CONFIG_DIR}/.claude.json"

	expect_unchanged_by_single_command claude "${CLAUDE_CONFIG_DIR}/.claude.json" "${uninstall_command}" --uninstall
}

@test "single command that stops partway tells an agent to hand the command to the user and offers the prompt prefix" {
	create_fake_engine_command claude
	printf '%s\n' '#!/usr/bin/env sh' 'exit 1' > "${FAKE_BIN}/claude"
	create_fake_release claude shipped > /dev/null
	export PATH="${FAKE_BIN}:${PATH}" CLAUDECODE=1

	run single_command

	[ "${status}" -ne 0 ]
	[[ "${output}" == *"stopped partway"* ]]
	expect_agent_told_to_hand_command_to_user claude
}
