#!/usr/bin/env bats

# Tests the run-part and read-part launchers in the Codex plugin's bin folder and the plugin's rg override: the
# launchers start CAT's programs from an installed plugin path that contains spaces, run-part saves a command's
# output in the temporary folder of the Codex thread's CAT session directory in the project, the line that ends a part
# names the plugin's read-part, run-part runs the plugin's rg override, and the override keeps the output that run-part
# saves short.

load bootstrap-helpers

# The command runs in a folder below the CAT project, so run-part must look upward for the project that receives the
# output. In a Codex thread whose configuration sets no limit, a part holds about 40,000
# bytes, so the command's output takes several parts; the second is printed by running the command the first names.
# The project is not in a Git work tree; Git's search for one stops at the test's folder, so a test folder inside
# another work tree does not make run-part treat the project as part of it.
@test "the plugin's run-part saves output in the project and the next part's command runs the plugin's read-part" {
	export GIT_CEILING_DIRECTORIES="${TEST_ROOT}"
	runtime="${TEST_ROOT}/installed plugin"
	cp -R "${GENERATED_RUNTIME_DIRECTORY}/codex" "${runtime}"
	project="${TEST_ROOT}/project"
	thread=0b6f3c2e-6d1a-4c8e-9f3b-2a7d5e1c4b90
	codex_home="${TEST_ROOT}/codex home"
	mkdir -p "${project}/.cat" "${project}/src" "${codex_home}"
	cd "${project}/src"

	run env CODEX_THREAD_ID="${thread}" CODEX_HOME="${codex_home}" LC_ALL=C LANG=C "${runtime}/bin/run-part" <<'EOF'
seq 1 20000
EOF

	[ "${status}" -eq 0 ]
	[ "${lines[0]}" = "1" ]
	last="${lines[${#lines[@]}-2]}"
	closing="${lines[${#lines[@]}-1]}"
	[[ "${closing}" == "[More output follows: "*" To read the next part, run: '${runtime}/bin/read-part' "* ]]
	[[ "${closing}" == *" '${project}/.cat/sessions/${thread}/temp/run-part-"*"/output.txt' "* ]]
	command="${closing#*run: }"
	command="${command%]}"

	run env CODEX_THREAD_ID="${thread}" CODEX_HOME="${codex_home}" LC_ALL=C LANG=C bash -c "${command}"

	[ "${status}" -eq 0 ]
	[ "${lines[0]}" = "$((last + 1))" ]
}

# Creates a fake rg after the plugin's rg on PATH. It records its arguments in the command log, prints FAKE_RG_LINES
# lines, and exits with FAKE_RG_STATUS.
create_fake_rg()
{
	cat > "${FAKE_BIN}/rg" <<'SCRIPT'
#!/usr/bin/env bash
printf 'rg %s\n' "$*" >> "${COMMAND_LOG}"
for ((line = 1; line <= ${FAKE_RG_LINES:-0}; ++line)); do
	printf 'match %d\n' "${line}"
done
exit "${FAKE_RG_STATUS:-0}"
SCRIPT
	chmod +x "${FAKE_BIN}/rg"
}

# Runs a command through the installed plugin's run-part in a project, with the fake rg on PATH.
#
# Arguments:
#   $1 - the command that run-part reads from its standard input
# Environment:
#   GENERATED_RUNTIME_DIRECTORY - the built runtime, whose Codex plugin is copied to a path that contains a space
#   TEST_ROOT - the folder that receives the copied plugin and the project
#   FAKE_BIN, COMMAND_LOG, FAKE_RG_LINES, FAKE_RG_STATUS - the fake rg's folder and settings (see create_fake_rg)
# Output: none; Bats' run sets status, output and lines from run-part's exit status and standard output
run_part()
{
	runtime="${TEST_ROOT}/installed plugin"
	cp -R "${GENERATED_RUNTIME_DIRECTORY}/codex" "${runtime}"
	project="${TEST_ROOT}/project"
	mkdir -p "${project}/.cat"
	cd "${project}"
	create_fake_rg
	run env PATH="${FAKE_BIN}:${PATH}" LC_ALL=C LANG=C "${runtime}/bin/run-part" <<<"$1"
}

# Runs a command the way that run-part runs it, without starting run-part's JVM: bash runs the command with the plugin's
# run-part overrides first on PATH, the fake rg after them, and its standard output and standard error going to the
# file that CAT_RUN_PART_OUTPUT names, which stands for the file in which run-part saves the output. Then the stand-in
# prints that file and exits with the command's exit status, as run-part does for output that fits in one part.
# CodexRunPartTest proves that run-part runs a command in this environment, and the first rg test runs the override
# through run-part itself.
#
# Arguments:
#   $1 - the command
# Environment:
#   GENERATED_RUNTIME_DIRECTORY - the built runtime, whose Codex plugin holds the run-part overrides
#   TEST_ROOT - the folder that receives the saved output
#   FAKE_BIN, COMMAND_LOG, FAKE_RG_LINES, FAKE_RG_STATUS - the fake rg's folder and settings (see create_fake_rg)
# Output: none; Bats' run sets status, output and lines from the command's exit status and saved output
run_saved()
{
	local overrides="${GENERATED_RUNTIME_DIRECTORY}/codex/bin/run-part-overrides"
	local saved="${TEST_ROOT}/run-part-1/output.txt"
	mkdir -p "${saved%/*}"
	create_fake_rg
	# The inner bash stands for the command's process, whose output goes to the saved file; the outer one, for run-part
	run env PATH="${overrides}:${FAKE_BIN}:${PATH}" CAT_RUN_PART_OUTPUT="${saved}" LC_ALL=C LANG=C bash -c \
		'bash -c "$1" >"${CAT_RUN_PART_OUTPUT}" 2>&1; status=$?; cat "${CAT_RUN_PART_OUTPUT}"; exit "${status}"' \
		run-part "$1"
}

@test "rg in a run-part command shortens long lines and keeps rg's exit status" {
	export FAKE_RG_LINES=3 FAKE_RG_STATUS=1

	run_part 'rg -n needle src'

	[ "${status}" -eq 1 ]
	[ "${lines[0]}" = "match 1" ]
	[ "${lines[2]}" = "match 3" ]
	[ "$(cat "${COMMAND_LOG}")" = "rg --max-columns=500 --max-columns-preview -n needle src" ]
}

@test "rg whose output run-part saves prints nothing and fails when its output passes 250 lines" {
	export FAKE_RG_LINES=251

	run_saved 'rg needle'

	[ "${status}" -eq 2 ]
	[[ "${output}" != *"match 1"* ]]
	[[ "${lines[0]}" == "rg: "*"more than 250 lines"* ]]
}

@test "rg whose output run-part saves prints every line when CAT_RG_LINE_LIMIT is 0" {
	export FAKE_RG_LINES=251

	run_saved 'CAT_RG_LINE_LIMIT=0 rg needle'

	[ "${status}" -eq 0 ]
	[ "${lines[250]}" = "match 251" ]
}

@test "rg in a run-part command that writes into a pipe runs unchanged" {
	export FAKE_RG_LINES=300

	run_saved 'rg needle | wc -l'

	[ "${status}" -eq 0 ]
	[ "$(printf '%s' "${lines[0]}" | tr -d ' ')" = "300" ]
	[ "$(cat "${COMMAND_LOG}")" = "rg needle" ]
}

@test "rg --help whose output run-part saves prints all of its help" {
	export FAKE_RG_LINES=300

	run_saved 'rg --help'

	[ "${status}" -eq 0 ]
	[ "${lines[299]}" = "match 300" ]
	[ "$(cat "${COMMAND_LOG}")" = "rg --help" ]
}
