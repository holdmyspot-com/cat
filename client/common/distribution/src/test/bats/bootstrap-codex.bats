#!/usr/bin/env bats

# Tests how the release's entry script cat.sh runs the installer's install for Codex, from detecting a Codex shell to
# choosing Codex's home as the harness home, how it refuses Windows, where PowerShell installs Codex, what the
# generated Codex hooks report when Codex runs them on this platform, that each command of the hooks' manifest starts
# its hook, and that the run-part command of the read-file rule reaches the plugin's run-part.

bats_require_minimum_version 1.5.0

load bootstrap-helpers
load windows-helpers

# Runs a generated Codex hook the way that Codex runs it on this platform: on Windows, PowerShell starts the hook's .cmd
# launcher with the call operator, as the hook's commandWindows does, because the Windows plugin has no POSIX launchers;
# elsewhere, the shell starts the hook's .sh launcher, as its command does.
#
# Arguments:
#   $1 - The hook's name, such as session-start.
#   $2 - The event, which the hook reads from its standard input.
#   $3 - The plugin's root directory; the generated Codex plugin when absent.
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
	local runtime="${3:-${GENERATED_RUNTIME_DIRECTORY}/codex}"
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

@test "entry script installs CAT for Codex in a Codex shell" {
	simulate_posix_platform
	export PATH="${FAKE_BIN}:${PATH}" CODEX_THREAD_ID=019a0000-0000-7000-8000-000000000000
	create_fake_release

	run single_command

	[ "${status}" -eq 0 ]
	expect_install_ran_for codex "${CODEX_HOME}"
}

@test "entry script gives install the default Codex home when CODEX_HOME is unset" {
	simulate_posix_platform
	export PATH="${FAKE_BIN}:${PATH}"
	create_fake_release
	unset CODEX_HOME

	run single_command --harness codex

	[ "${status}" -eq 0 ]
	expect_install_ran_for codex "${HOME}/.codex"
}

# The Windows Codex plugin has only PowerShell launchers, so under Git Bash cat.sh could not run its installer
@test "entry script refuses Codex on Windows before downloading and gives the PowerShell command that installs it" {
	create_fake_release
	export PATH="${FAKE_BIN}:${PATH}"
	create_fake_uname MINGW64_NT-10.0-26100 x86_64

	run single_command --harness codex

	[ "${status}" -eq 1 ]
	[[ "${output}" == *"  \$env:CODEX_HOME='${CODEX_HOME}'; & ([scriptblock]::Create((irm ${RELEASE_DOWNLOAD_URL}/cat.ps1))) -Harness codex"* ]]
	[ "$(cat "${URL_LOG}")" = "${SINGLE_COMMAND_URL}" ]
	[ ! -e "${INSTALL_ARGUMENTS}" ]
	[ -z "$(ls -A "${TMPDIR}")" ]
}

@test "entry script's PowerShell command for Codex on Windows omits the default Codex home" {
	create_fake_release
	export PATH="${FAKE_BIN}:${PATH}"
	create_fake_uname MINGW64_NT-10.0-26100 x86_64
	export CODEX_HOME="${HOME}/.codex"

	run single_command --harness codex

	[ "${status}" -eq 1 ]
	[[ "${output}" == *"  & ([scriptblock]::Create((irm ${RELEASE_DOWNLOAD_URL}/cat.ps1))) -Harness codex"* ]]
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

# An event of a shell command that runs run-part, with text that is not ASCII
PART_EVENT='{"tool_input":{"command":"run-part café —"}}'
# The byte-order mark that Windows PowerShell 5.1 can put before the text that it pipes to a program, which run-part and
# the hooks' reader of the event ignore at the start of their input
PIPED_MARK=$'\xEF\xBB\xBF'

# Runs a command of a Codex plugin's hooks manifest the way that Codex runs it on this platform: Codex writes the
# plugin's root directory in place of ${PLUGIN_ROOT}, sets the environment variable PLUGIN_ROOT to it, and runs the
# hook's commandWindows in PowerShell on Windows, and its command in a POSIX shell elsewhere.
#
# Arguments:
#   $1 - The plugin's root directory, which holds hooks/hooks.json.
#   $2 - The hook's event, such as SessionStart.
#   $3 - The position of the hook among the event's hooks, from 0.
#   $4 - The event, which the command reads from its standard input.
#
# Output:
#   Writes the command's standard output and standard error.
#
# Exit status:
#   Returns the command's exit status.
codex_manifest_command()
{
	local field=command root="$1"
	case "$(current_platform)" in
		windows-*)
			field=commandWindows
			root=$(cygpath -w "$1")
			;;
	esac
	local command
	# jq reads the manifest from its standard input: Git Bash converts a path argument of a Windows program such as jq
	# to the path's Windows form, but not a path that holds a quote, which the program then cannot open
	command=$(jq -r --arg event "$2" --argjson index "$3" --arg field "${field}" \
		'[.hooks[$event][].hooks[]][$index][$field]' < "$1/hooks/hooks.json" | tr -d '\r')
	# shellcheck disable=SC2016 # The placeholder that Codex replaces, not an expansion
	local placeholder='${PLUGIN_ROOT}'
	command="${command//"${placeholder}"/"${root}"}"
	case "$(current_platform)" in
		windows-*) printf '%s\n' "$4" | powershell_command "${command}" "PLUGIN_ROOT=${root}" ;;
		*) printf '%s\n' "$4" | env PLUGIN_ROOT="${root}" sh -c "${command}" ;;
	esac
}

# Replaces a program of a Codex plugin's bin folder, in the form that the plugin's hooks start on this platform, with
# one that prints a line and then copies its standard input to its standard output.
#
# Arguments:
#   $1 - The plugin's root directory.
#   $2 - The program's name, such as session-start.
#   $3 - The line that the program prints first.
#
# Side effects:
#   Writes $1/bin/$2.cmd on Windows and $1/bin/$2 elsewhere.
replace_codex_program()
{
	case "$(current_platform)" in
		windows-*)
			printf '@echo off\r\necho %s\r\nfindstr "^"\r\nexit /b 0\r\n' "$3" > "$1/bin/$2.cmd"
			;;
		*)
			printf '#!/bin/sh\necho %s\ncat\n' "$3" > "$1/bin/$2"
			chmod +x "$1/bin/$2"
			;;
	esac
}

@test "each generated Codex hook command starts its hook from a path with a space, a quote and a dollar sign" {
	plugin="${TEST_ROOT}/cat plugin's \$HOME hooks"
	mkdir -p "${plugin}/bin"
	cp -R "${GENERATED_RUNTIME_DIRECTORY}/codex/hooks" "${plugin}/hooks"
	commands=0
	for event in $(jq -r '.hooks | keys_unsorted[]' < "${plugin}/hooks/hooks.json" | tr -d '\r'); do
		case "${event}" in
			SessionStart) program="session-start" ;;
			SubagentStart) program="subagent-start" ;;
			# The next test runs this hook, whose command starts Java only for some events
			PostToolUse) continue ;;
			*)
				printf 'The manifest names an unknown event: %s\n' "${event}"
				false
				;;
		esac
		replace_codex_program "${plugin}" "${program}" "started ${program}"
		hooks=$(jq --arg event "${event}" '[.hooks[$event][].hooks[]] | length' < "${plugin}/hooks/hooks.json" |
			tr -d '\r')
		for ((index = 0; index < hooks; ++index)); do
			run --separate-stderr codex_manifest_command "${plugin}" "${event}" "${index}" "${PART_EVENT}"

			[ "${status}" -eq 0 ]
			[ "${output//$'\r'/}" = "started ${program}"$'\n'"${PART_EVENT}" ]
			commands=$((commands + 1))
		done
	done
	[ "${commands}" -eq 2 ]
}

@test "the generated Codex PostToolUse hook passes only a run-part or read-part event to the plugin's program" {
	plugin="${TEST_ROOT}/cat plugin's \$HOME hooks"
	mkdir -p "${plugin}/bin"
	cp -R "${GENERATED_RUNTIME_DIRECTORY}/codex/hooks" "${plugin}/hooks"
	replace_codex_program "${plugin}" post-tool-use started
	read_part_event="${PART_EVENT/run-part/read-part}"

	run --separate-stderr codex_manifest_command "${plugin}" PostToolUse 0 "${PART_EVENT}"

	[ "${status}" -eq 0 ]
	output="${output//$'\r'/}"
	[ "${output}" = "started"$'\n'"${PART_EVENT}" ] || [ "${output}" = "started"$'\n'"${PIPED_MARK}${PART_EVENT}" ]

	run --separate-stderr codex_manifest_command "${plugin}" PostToolUse 0 "${read_part_event}"

	[ "${status}" -eq 0 ]
	output="${output//$'\r'/}"
	[ "${output}" = "started"$'\n'"${read_part_event}" ] ||
		[ "${output}" = "started"$'\n'"${PIPED_MARK}${read_part_event}" ]

	run --separate-stderr codex_manifest_command "${plugin}" PostToolUse 0 '{"tool_input":{"command":"ls"}}'

	[ "${status}" -eq 0 ]
	[ -z "${output}" ]
}

@test "the generated Codex read-file rule's run-part command reaches run-part from a path with a quote" {
	plugin="${TEST_ROOT}/cat-o'brien plugin"
	cp -R "${GENERATED_RUNTIME_DIRECTORY}/codex" "${plugin}"
	project="${TEST_ROOT}/read-file project"
	mkdir -p "${project}"

	run --separate-stderr codex_hook session-start \
		"{\"cwd\":\"$(json_path "${project}")\",\"session_id\":\"123e4567-e89b-12d3-a456-426614174000\"}" "${plugin}"

	[ "${status}" -eq 0 ]
	# The agent writes its command in place of <command> in the rule's first code block, which runs run-part
	block=$(printf '%s\n' "${output}" | jq -r '.hookSpecificOutput.additionalContext' | tr -d '\r' |
		awk '/^```/ { if (inside) exit; inside = 1; next } inside')
	block="${block//<command>/Get-Content notes.md}"
	case "$(current_platform)" in
		windows-*)
			printf '@findstr "^"\r\n' > "${plugin}/bin/run-part.cmd"
			run --separate-stderr powershell_command "${block}" < /dev/null
			;;
		*)
			printf '#!/bin/sh\ncat\n' > "${plugin}/bin/run-part"
			run --separate-stderr sh -c "${block}" < /dev/null
			;;
	esac

	[ "${status}" -eq 0 ]
	output="${output//$'\r'/}"
	[ "${output#"${PIPED_MARK}"}" = "Get-Content notes.md" ]
}
