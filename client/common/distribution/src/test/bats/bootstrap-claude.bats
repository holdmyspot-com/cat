#!/usr/bin/env bats

# Tests how the release's entry script cat.sh runs the installer's install for Claude Code, from detecting a Claude Code
# shell to choosing Claude Code's configuration directory as the harness home, what the generated Claude plugin
# launchers and hooks report, that the generated claude-credential launcher saves a token from standard input without
# printing it, and that each command of the hooks' manifest starts its hook.

load bootstrap-helpers

@test "entry script installs CAT for Claude Code in a Claude Code shell" {
	create_fake_release
	export PATH="${FAKE_BIN}:${PATH}" CLAUDECODE=1

	run single_command

	[ "${status}" -eq 0 ]
	expect_install_ran_for claude "${CLAUDE_CONFIG_DIR}"
}

@test "entry script gives install the default Claude Code home when CLAUDE_CONFIG_DIR is unset" {
	create_fake_release
	export PATH="${FAKE_BIN}:${PATH}"
	unset CLAUDE_CONFIG_DIR

	run single_command --harness claude

	[ "${status}" -eq 0 ]
	expect_install_ran_for claude "${HOME}/.claude"
}

@test "generated Claude plugin-info launcher reports its harness, full plugin version, and platform" {
	expected_platform="$(current_platform)"
	expected_version="$(< "${GENERATED_RUNTIME_DIRECTORY}/claude/bin/VERSION")"

	run "${GENERATED_RUNTIME_DIRECTORY}/claude/bin/plugin-info"

	[[ "${expected_version}" =~ ^1\.0\+b[0-9a-f]{12}$ ]]
	[ "${status}" -eq 0 ]
	[ "${output}" = "harness=claude
version=${expected_version}
platform=${expected_platform}" ]
}

@test "generated Claude claude-credential launcher saves a token from standard input into an owner-only file without printing it" {
	home="${TEST_ROOT}/credential home"
	mkdir -p "${home}"
	token="sk-ant-oat01-fixture"

	# The command refuses to run in a nested session, so the session that runs the tests must not make it one
	run env -u CAT_NESTED_SESSION -u CAT_SESSION_REQUESTS sh -c 'printf "%s\n" "$1" | "$2" set --home "$3" --json' \
		sh "${token}" "${GENERATED_RUNTIME_DIRECTORY}/claude/bin/claude-credential" "${home}"

	[ "${status}" -eq 0 ]
	[ "$(printf '%s\n' "${output}" | wc -l | tr -d ' ')" -eq 1 ]
	[[ "${output}" == '{"result":"saved",'* ]]
	[[ "${output}" != *"${token}"* ]]
	[[ "$(ls -l "${home}/.cat/claude/.credentials.json")" == "-rw-------"* ]]
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

@test "each generated Claude hook command starts its hook from a path with a space and a quote" {
	plugin="${TEST_ROOT}/cat plugin's hooks"
	mkdir -p "${plugin}/bin"
	cp -R "${GENERATED_RUNTIME_DIRECTORY}/claude/hooks" "${plugin}/hooks"
	# Claude Code names the plugin's root directory with / separators on every platform
	root="${plugin}"
	if command -v cygpath >/dev/null 2>&1; then
		root=$(cygpath -m "${plugin}")
	fi
	# jq reads the manifest from its standard input: Git Bash converts a path argument of a Windows program such as jq
	# to the path's Windows form, but not a path that holds a quote, which the program then cannot open
	hooks="${plugin}/hooks/hooks.json"
	commands=0
	for event in $(jq -r '.hooks | keys_unsorted[]' < "${hooks}" | tr -d '\r'); do
		case "${event}" in
			SessionStart) program="session-start" ;;
			SubagentStart) program="subagent-start" ;;
			SessionEnd) program="session-end" ;;
			*)
				printf 'The manifest names an unknown event: %s\n' "${event}"
				false
				;;
		esac
		printf '#!/bin/sh\necho started %s\n' "${program}" > "${plugin}/bin/${program}"
		chmod +x "${plugin}/bin/${program}"
		count=$(jq --arg event "${event}" '[.hooks[$event][].hooks[]] | length' < "${hooks}" | tr -d '\r')
		for ((index = 0; index < count; ++index)); do
			command=$(jq -r --arg event "${event}" --argjson index "${index}" \
				'[.hooks[$event][].hooks[]][$index].command' < "${hooks}" | tr -d '\r')

			# Claude Code runs the command in a POSIX shell, Git Bash on Windows, which expands ${CLAUDE_PLUGIN_ROOT}
			run env CLAUDE_PLUGIN_ROOT="${root}" sh -c "${command}" < /dev/null

			[ "${status}" -eq 0 ]
			[ "${output}" = "started ${program}" ]
			commands=$((commands + 1))
		done
	done
	[ "${commands}" -eq 3 ]
}
