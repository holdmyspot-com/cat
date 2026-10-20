#!/usr/bin/env bats

# Tests the template that every generated jlink launcher shares: a launcher runs its program from a runtime path that
# contains spaces, without Bubblewrap even where it cannot create a namespace, and Java can name a file that is not ASCII
# in the caller's C locale.
# The launchers that it runs are in the plugin's bin folder, so they also start the Java runtime in its client folder.

load bootstrap-helpers

@test "generated launchers run their programs from a path with spaces where bwrap fails and name non-ASCII files in the C locale" {
	runtime="${TEST_ROOT}/generated runtime"
	cp -R "${GENERATED_RUNTIME_DIRECTORY}/codex" "${runtime}"
	create_failing_bwrap
	temporary="${TEST_ROOT}/temp café"
	mkdir -p "${temporary}/run-part-1"
	file="${temporary}/run-part-1/output.txt"
	printf 'first\n' > "${file}"
	printf '0\n' > "${file}.exit-status"

	run env PATH="${FAKE_BIN}:${PATH}" LC_ALL=C LANG=C "${runtime}/bin/plugin-info"

	[ "${status}" -eq 0 ]
	[[ "${output}" == "harness=codex"$'\n'* ]]

	run env -u CODEX_THREAD_ID PATH="${FAKE_BIN}:${PATH}" TMPDIR="${temporary}" LC_ALL=C LANG=C "${runtime}/bin/read-part" \
		"${file}"

	[ "${status}" -eq 0 ]
	[ "${output}" = "$(printf 'first\n[End of %s. The file was deleted]' "${file}")" ]
}
