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

@test "generated Claude plugin-info launcher reports its native identity" {
	expected_platform="$(expected_native_platform)"

	run "${GENERATED_RUNTIME_DIRECTORY}/claude/client/bin/plugin-info"

	[ "${status}" -eq 0 ]
	[ "${output}" = "harness=claude
version=1.1
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
	[ "$(cat "${CLAUDE_CONFIG_DIR}/plugins/data/cat-cat/migration-version")" = "1.1" ]
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

@test "Claude removal delegates to the native plugin command" {
	create_fake_engine_command claude
	mkdir -p "${CLAUDE_CONFIG_DIR}/plugins/cat-marketplace"

	run env PATH="${FAKE_BIN}:${PATH}" COMMAND_LOG="${COMMAND_LOG}" CLAUDE_CONFIG_DIR="${CLAUDE_CONFIG_DIR}" \
		sh "${INSTALL_DIRECTORY}/uninstall-claude.sh"

	[ "${status}" -eq 0 ]
	[ "$(sed -n '1p' "${COMMAND_LOG}")" = "claude plugin uninstall cat@cat" ]
	[ ! -e "${CLAUDE_CONFIG_DIR}/plugins/cat-marketplace" ]
}

@test "generated Claude SessionStart launcher injects bundled routing and project rules" {
	project="${TEST_ROOT}/generated session project"
	mkdir -p "${project}/.cat/rules/common"
	printf 'project session rule\n' > "${project}/.cat/rules/common/session.md"
	runtime="${GENERATED_RUNTIME_DIRECTORY}/claude"
	create_fake_bwrap

	run env PATH="${FAKE_BIN}:${PATH}" CLAUDE_PLUGIN_ROOT="${runtime}" sh -c 'printf "%s\\n" "$1" | "$2"' sh \
		"{\"cwd\":\"${project}\",\"session_id\":\"123e4567-e89b-12d3-a456-426614174000\"}" "${runtime}/hooks/session-start.sh"

	[ "${status}" -eq 0 ]
	[[ "${output}" == *'"hookEventName":"SessionStart"'* ]]
	context_file=$(printf '%s\n' "${output}" | sed -n 's/.*instructions in `\([^`]*\)`.*/\1/p')
	grep -F "Default Rule Routing" "${context_file}"
	grep -F "When handling Java exceptions, lazy load" "${context_file}"
	grep -F "project session rule" "${context_file}"
}

@test "generated Claude SubagentStart launcher injects only addressed project rules" {
	project="${TEST_ROOT}/generated subagent project"
	mkdir -p "${project}/.cat/rules/claude"
	printf '%s\n' '---' 'agents: ["main"]' '---' 'main rule' > "${project}/.cat/rules/claude/main.md"
	printf '%s\n' '---' 'agents: ["cat:reviewer"]' '---' 'reviewer rule' > "${project}/.cat/rules/claude/reviewer.md"
	runtime="${GENERATED_RUNTIME_DIRECTORY}/claude"
	create_fake_bwrap

	run env PATH="${FAKE_BIN}:${PATH}" CLAUDE_PLUGIN_ROOT="${runtime}" sh -c 'printf "%s\\n" "$1" | "$2"' sh \
		"{\"cwd\":\"${project}\",\"agent_type\":\"cat:reviewer\",\"session_id\":\"123e4567-e89b-12d3-a456-426614174001\",\"agent_id\":\"reviewer-test\"}" "${runtime}/hooks/subagent-start.sh"

	[ "${status}" -eq 0 ]
	[[ "${output}" == *'"hookEventName":"SubagentStart"'* ]]
	context_file=$(printf '%s\n' "${output}" | sed -n 's/.*instructions in `\([^`]*\)`.*/\1/p')
	grep -F "reviewer rule" "${context_file}"
	! grep -F "main rule" "${context_file}"
}
