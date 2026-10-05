#!/usr/bin/env bats

# Tests the Codex image's packaged read-file script: numbered output in bounded parts that name the command for the
# next part, per-thread continuation of repeated reads, restarts after a file changes, and usage errors.

bats_require_minimum_version 1.5.0

# Bats invokes this before each test to create its fixture directory under the project's .cat/work/temp and to keep
# the script's per-thread state inside it.
#
# Side effects:
#   Sets GENERATED_RUNTIME_DIRECTORY, READ_FILE, TEST_ROOT and TMPDIR, and unsets CODEX_THREAD_ID.
setup()
{
	export GENERATED_RUNTIME_DIRECTORY="${BATS_TEST_DIRNAME}/../../../target/runtime"
	READ_FILE="${GENERATED_RUNTIME_DIRECTORY}/codex/client/bin/read-file"
	TEST_ROOT="$("${GENERATED_RUNTIME_DIRECTORY}/codex/client/bin/workflow-temp" create read-file)"
	export TEST_ROOT
	export TMPDIR="${TEST_ROOT}/tmp"
	mkdir "${TMPDIR}"
	unset CODEX_THREAD_ID
}

# Removes the test's fixture directory after its last assertion completes.
#
# Side effects:
#   Deletes the temporary directory created by setup.
teardown()
{
	if [ -n "${TEST_ROOT:-}" ]; then
		"${GENERATED_RUNTIME_DIRECTORY}/codex/client/bin/workflow-temp" delete "${TEST_ROOT}"
	fi
}

# Writes a text file several parts long, whose lines differ in length.
#
# Arguments:
#   $1 - Path of the file to create.
write_large_file()
{
	awk 'BEGIN { for (i = 1; i <= 2500; ++i) { printf "line %d:", i; for (j = 0; j < i % 23; ++j) printf " word"
		printf "\n" } }' > "$1"
}

# Prints a file the way read-file numbers its lines.
#
# Arguments:
#   $1 - Path of the file to number.
#
# Output:
#   Each line of the file, preceded by its line number right-aligned in six columns and a tab.
numbered()
{
	awk '{ printf "%6d\t%s\n", NR, $0 }' "$1"
}

# Checks that one part holds at most 18,000 bytes of numbered lines and ends with either the command for the next part
# or the end marker of the file.
#
# Arguments:
#   $1 - The complete output of one read-file invocation.
#   $2 - Path of the file that was read.
#
# Output:
#   The command that prints the next part, or nothing after the last part.
#
# Exit status:
#   0 if the part is well formed; 1 otherwise.
next_command()
{
	part="$1"
	trailer="${part##*$'\n'}"
	lines="${part%$'\n'*}"
	[ "$(printf '%s\n' "${lines}" | LC_ALL=C wc -c)" -le 18000 ] || return 1
	if [ "${trailer}" = "[end of $2]" ]; then
		return 0
	fi
	[[ "${trailer}" =~ ^\["$2"\ continues:\ run\ (.*)\ now,\ before\ any\ other\ command\ or\ your\ answer\]$ ]] ||
		return 1
	printf '%s\n' "${BASH_REMATCH[1]}"
}

@test "packaged Codex read-file prints a file smaller than one part with numbered lines and its end marker" {
	file="${TEST_ROOT}/small.txt"
	printf 'first\n\tindented\nlast\n' > "${file}"

	run "${READ_FILE}" "${file}"

	[ "${status}" -eq 0 ]
	[ "${output}" = "$(numbered "${file}")"$'\n'"[end of ${file}]" ]
}

@test "following each part's command delivers every line of a large file exactly once in parts of at most 18,000 bytes" {
	file="${TEST_ROOT}/large.txt"
	write_large_file "${file}"
	delivered="${TEST_ROOT}/delivered"
	: > "${delivered}"

	command="$(printf '%q %q' "${READ_FILE}" "${file}")"
	parts=0
	while [ -n "${command}" ]; do
		part="$(eval "${command}")"
		parts=$((parts + 1))
		[ "${parts}" -le 20 ]
		command="$(next_command "${part}" "${file}")"
		printf '%s\n' "${part%$'\n'*}" >> "${delivered}"
	done

	[ "${parts}" -ge 3 ]
	diff "${delivered}" <(numbered "${file}")
}

@test "within one Codex thread a repeated read continues at the next unread part and then reports that all was read" {
	file="${TEST_ROOT}/large.txt"
	write_large_file "${file}"
	delivered="${TEST_ROOT}/delivered"
	: > "${delivered}"
	export CODEX_THREAD_ID=thread-under-test

	parts=0
	while true; do
		part="$("${READ_FILE}" "${file}")"
		parts=$((parts + 1))
		[ "${parts}" -le 20 ]
		printf '%s\n' "${part%$'\n'*}" >> "${delivered}"
		next="$(next_command "${part}" "${file}")"
		if [ -z "${next}" ]; then
			break
		fi
	done
	run "${READ_FILE}" "${file}"

	[ "${parts}" -ge 3 ]
	diff "${delivered}" <(numbered "${file}")
	[ "${status}" -eq 0 ]
	again="$(printf '%q %q' "$(realpath "${READ_FILE}")" "${file}")"
	[ "${output}" = "[you already have all of ${file}; to read it again, run ${again} 1]" ]
}

@test "within one Codex thread a read of a file that changed since its last read restarts at line 1" {
	file="${TEST_ROOT}/large.txt"
	write_large_file "${file}"
	export CODEX_THREAD_ID=thread-under-test
	"${READ_FILE}" "${file}" > /dev/null
	printf 'appended line\n' >> "${file}"

	run "${READ_FILE}" "${file}"

	[ "${status}" -eq 0 ]
	[ "${lines[0]}" = "$(numbered "${file}" | head -n 1)" ]
}

@test "packaged Codex read-file rejects a call without a path or with extra arguments" {
	run --separate-stderr "${READ_FILE}"
	[ "${status}" -eq 2 ]
	[ "${stderr}" = "usage: read-file <path> [<first line>]" ]

	run --separate-stderr "${READ_FILE}" a b c
	[ "${status}" -eq 2 ]
	[ "${stderr}" = "usage: read-file <path> [<first line>]" ]
}

@test "packaged Codex read-file rejects a first line that is not a positive integer" {
	file="${TEST_ROOT}/small.txt"
	printf 'only\n' > "${file}"

	for first in 0 -3 abc 2x; do
		run --separate-stderr "${READ_FILE}" "${file}" "${first}"
		[ "${status}" -eq 2 ]
		[ "${stderr}" = "read-file: the first line must be a positive integer, not \"${first}\"" ]
	done
}

@test "packaged Codex read-file rejects a path that is missing, not a regular file or not readable" {
	unreadable="${TEST_ROOT}/unreadable.txt"
	printf 'secret\n' > "${unreadable}"
	chmod 000 "${unreadable}"
	paths=("${TEST_ROOT}/missing.txt" "${TEST_ROOT}")
	if [ ! -r "${unreadable}" ]; then
		paths+=("${unreadable}")
	fi

	for path in "${paths[@]}"; do
		run --separate-stderr "${READ_FILE}" "${path}"
		[ "${status}" -eq 1 ]
		[ "${stderr}" = "read-file: cannot read \"${path}\"" ]
	done
}

@test "the Claude image does not ship read-file" {
	[ ! -e "${GENERATED_RUNTIME_DIRECTORY}/claude/client/bin/read-file" ]
}
