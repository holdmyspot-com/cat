#!/usr/bin/env bats

# Tests the run-part and read-part launchers in the Codex plugin's bin folder: they start the client's programs from an
# installed plugin path that contains spaces, run-part saves a command's output in the project's CAT temporary folder,
# and the line that ends a part names the plugin's read-part.

load bootstrap-helpers

# The project holds a CAT temporary folder and the command runs in a folder below it, so run-part must look upward for
# the folder that receives the output. In a Codex thread whose configuration sets no limit, a part holds about 40,000
# bytes, so the command's output takes several parts; the second is printed by running the command the first names.
@test "the plugin's run-part saves output in the project and the next part's command runs the plugin's read-part" {
	runtime="${TEST_ROOT}/installed plugin"
	cp -R "${GENERATED_RUNTIME_DIRECTORY}/codex" "${runtime}"
	project="${TEST_ROOT}/project"
	mkdir -p "${project}/.cat/work/temp" "${project}/src"
	cd "${project}/src"

	run env CODEX_THREAD_ID=thread LC_ALL=C LANG=C "${runtime}/bin/run-part" <<'EOF'
seq 1 20000
EOF

	[ "${status}" -eq 0 ]
	[ "${lines[0]}" = "1" ]
	last="${lines[${#lines[@]}-2]}"
	closing="${lines[${#lines[@]}-1]}"
	prefix="[More output follows. Before any other command or answer, run: '${runtime}/bin/read-part' "
	[[ "${closing}" == "${prefix}'${project}/.cat/work/temp/run-part-"*"/output.txt' "* ]]
	command="${closing#*run: }"
	command="${command%]}"

	run env CODEX_THREAD_ID=thread LC_ALL=C LANG=C bash -c "${command}"

	[ "${status}" -eq 0 ]
	[ "${lines[0]}" = "$((last + 1))" ]
}
