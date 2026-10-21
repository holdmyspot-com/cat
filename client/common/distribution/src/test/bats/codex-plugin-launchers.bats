#!/usr/bin/env bats

# Tests the run-part and read-part launchers in the Codex plugin's bin folder: they start the client's programs from an
# installed plugin path that contains spaces, run-part saves a command's output in the temporary folder of the Codex
# thread's CAT session directory in the project, the line that ends a part names the plugin's read-part, and rg in a
# run-part command keeps the saved output short.

load bootstrap-helpers

# The command runs in a folder below the CAT project, so run-part must look upward for the project that receives the
# output. In a Codex thread whose configuration sets no limit, a part holds about 40,000
# bytes, so the command's output takes several parts; the second is printed by running the command the first names.
@test "the plugin's run-part saves output in the project and the next part's command runs the plugin's read-part" {
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
	size=$(seq 1 20000 | wc -c)
	remaining=$((size - $(seq 1 "${last}" | wc -c)))
	prefix="[More output follows: ${remaining} of the output's ${size} bytes remain, in at least "
	[[ "${closing}" == "${prefix}"*" more parts. To read the next part, run: '${runtime}/bin/read-part' "* ]]
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

@test "rg in run-part shortens long lines and keeps rg's exit status" {
	export FAKE_RG_LINES=3 FAKE_RG_STATUS=1

	run_part 'rg -n needle src'

	[ "${status}" -eq 1 ]
	[ "${lines[0]}" = "match 1" ]
	[ "${lines[2]}" = "match 3" ]
	[ "$(cat "${COMMAND_LOG}")" = "rg --max-columns=500 --max-columns-preview -n needle src" ]
}

@test "rg in run-part prints nothing and fails when its output passes 250 lines" {
	export FAKE_RG_LINES=251

	run_part 'rg needle'

	[ "${status}" -eq 2 ]
	[[ "${output}" != *"match 1"* ]]
	[[ "${lines[0]}" == "rg: "*"more than 250 lines"* ]]
}

@test "rg in run-part prints every line when CAT_RG_LINE_LIMIT is 0" {
	export FAKE_RG_LINES=251

	run_part 'CAT_RG_LINE_LIMIT=0 rg needle'

	[ "${status}" -eq 0 ]
	[ "${lines[250]}" = "match 251" ]
}

@test "rg in run-part that writes into a pipe runs unchanged" {
	export FAKE_RG_LINES=300

	run_part 'rg needle | wc -l'

	[ "${status}" -eq 0 ]
	[ "$(printf '%s' "${lines[0]}" | tr -d ' ')" = "300" ]
	[ "$(cat "${COMMAND_LOG}")" = "rg needle" ]
}

@test "rg --help in run-part prints all of its help" {
	export FAKE_RG_LINES=300

	run_part 'rg --help'

	[ "${status}" -eq 0 ]
	[ "${lines[299]}" = "match 300" ]
	[ "$(cat "${COMMAND_LOG}")" = "rg --help" ]
}
