#!/usr/bin/env bats

# Tests how the release's entry script cat.sh runs updater setup for Claude Code, from detecting a Claude Code shell to
# choosing Claude Code's configuration directory as the harness home, and what the generated Claude plugin launchers and
# hooks report.

load bootstrap-helpers

@test "entry script sets up the updater for Claude Code in a Claude Code shell" {
	create_fake_release
	export PATH="${FAKE_BIN}:${PATH}" CLAUDECODE=1

	run single_command

	[ "${status}" -eq 0 ]
	expect_setup_ran_for claude "${CLAUDE_CONFIG_DIR}"
}

@test "entry script gives setup the default Claude Code home when CLAUDE_CONFIG_DIR is unset" {
	create_fake_release
	export PATH="${FAKE_BIN}:${PATH}"
	unset CLAUDE_CONFIG_DIR

	run single_command --harness claude

	[ "${status}" -eq 0 ]
	expect_setup_ran_for claude "${HOME}/.claude"
}

@test "generated Claude plugin-info launcher reports its harness, version, and platform" {
	expected_platform="$(current_platform)"

	run "${GENERATED_RUNTIME_DIRECTORY}/claude/bin/plugin-info"

	[ "${status}" -eq 0 ]
	[ "${output}" = "harness=claude
version=1.0
platform=${expected_platform}" ]
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
