#!/usr/bin/env bats

# Tests the template that every generated jlink launcher shares: a launcher runs its program from a runtime path that
# contains spaces, with or without Bubblewrap's private /tmp, and Java can name a file that is not ASCII in the caller's
# C locale.

load bootstrap-helpers

@test "generated launchers run their programs from a path with spaces and name files that are not ASCII in the C locale" {
	runtime="${TEST_ROOT}/generated runtime"
	cp -R "${GENERATED_RUNTIME_DIRECTORY}/codex" "${runtime}"
	create_fake_bwrap
	file="${TEST_ROOT}/café.txt"
	printf 'first\n' > "${file}"

	run env PATH="${FAKE_BIN}:${PATH}" LC_ALL=C LANG=C "${runtime}/client/bin/plugin-info"

	[ "${status}" -eq 0 ]
	[[ "${output}" == "harness=codex"$'\n'* ]]

	run env -u CODEX_THREAD_ID LC_ALL=C LANG=C "${runtime}/client/bin/read-part" "${file}"

	[ "${status}" -eq 0 ]
	[ "${output}" = "$(printf 'first\n[End of %s]' "${file}")" ]
}
