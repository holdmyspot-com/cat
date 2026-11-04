#!/usr/bin/env bats

# Tests how the release's entry script cat.sh runs installer setup for Codex, from detecting a Codex shell to choosing
# Codex's home as the harness home, and what the generated Codex hooks report when Codex runs them on this platform.

load bootstrap-helpers
load windows-helpers

# Runs a generated Codex hook the way that Codex runs it on this platform: on Windows, PowerShell starts the hook's .cmd
# launcher with the call operator, as the hook's commandWindows does, because the Windows plugin has no POSIX launchers;
# elsewhere, the shell starts the hook's .sh launcher, as its command does.
#
# Arguments:
#   $1 - The hook's name, such as session-start.
#   $2 - The event, which the hook reads from its standard input.
#
# Environment:
#   GENERATED_RUNTIME_DIRECTORY - The directory of the plugins that the build generated.
#   FAKE_BIN - The directory of the fake programs, which PATH searches first.
#   HOME - The user's home directory, which Codex gives a Windows hook as USERPROFILE instead.
#
# Output:
#   Writes the hook's standard output and standard error.
#
# Exit status:
#   Returns the hook's exit status.
codex_hook()
{
	local runtime="${GENERATED_RUNTIME_DIRECTORY}/codex"
	case "$(current_platform)" in
		windows-*)
			printf '%s\n' "$2" | powershell_command "& \"\$env:PLUGIN_ROOT\\hooks\\$1.cmd\"" \
				"PATH=${FAKE_BIN}:${PATH}" "PLUGIN_ROOT=$(cygpath -w "${runtime}")" "USERPROFILE=$(cygpath -w "${HOME}")"
			;;
		*)
			printf '%s\n' "$2" | env PATH="${FAKE_BIN}:${PATH}" PLUGIN_ROOT="${runtime}" "${runtime}/hooks/$1.sh"
			;;
	esac
}

@test "entry script sets up the installer for Codex in a Codex shell" {
	create_fake_release
	export PATH="${FAKE_BIN}:${PATH}" CODEX_THREAD_ID=019a0000-0000-7000-8000-000000000000

	run single_command

	[ "${status}" -eq 0 ]
	expect_setup_ran_for codex "${CODEX_HOME}"
}

@test "entry script gives setup the default Codex home when CODEX_HOME is unset" {
	create_fake_release
	export PATH="${FAKE_BIN}:${PATH}"
	unset CODEX_HOME

	run single_command --harness codex

	[ "${status}" -eq 0 ]
	expect_setup_ran_for codex "${HOME}/.codex"
}

@test "generated Codex SessionStart launcher injects the packaged routing and the project's rules" {
	project="${TEST_ROOT}/generated session project"
	mkdir -p "${project}/.cat/rules/common"
	printf 'project session rule\n' > "${project}/.cat/rules/common/session.md"

	run codex_hook session-start \
		"{\"cwd\":\"$(json_path "${project}")\",\"session_id\":\"123e4567-e89b-12d3-a456-426614174000\"}"

	[ "${status}" -eq 0 ]
	[[ "${output}" == *'"hookSpecificOutput"'* ]]
	context_file=$(printf '%s\n' "${output}" | sed -n 's/.*rules for this session are in `\([^`]*\)`.*/\1/p')
	grep -F "CAT Rule Routing" "${context_file}"
	grep -F "project session rule" "${context_file}"
}

@test "generated Codex SubagentStart launcher injects the project rule addressed to the subagent" {
	project="${TEST_ROOT}/generated subagent project"
	mkdir -p "${project}/.cat/rules/codex"
	printf '%s\n' '---' 'agents: ["cat:reviewer"]' '---' 'reviewer rule' > "${project}/.cat/rules/codex/reviewer.md"

	session='"session_id":"123e4567-e89b-12d3-a456-426614174000"'
	run codex_hook subagent-start "{\"cwd\":\"$(json_path "${project}")\",\"agent_type\":\"cat:reviewer\",${session}}"

	[ "${status}" -eq 0 ]
	[[ "${output}" == *'"hookEventName":"SubagentStart"'* ]]
	[[ "${output}" == *"reviewer rule"* ]]
}
