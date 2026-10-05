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

# Follows the commands that read-file prints, starting from a read of the whole file, and checks that every part is
# well formed and that the parts deliver every line exactly once, in order.
#
# Arguments:
#   $1 - Path of a file at least three parts long.
assert_commands_deliver_every_line_once()
{
	delivered="${TEST_ROOT}/delivered"
	: > "${delivered}"
	command="$(printf '%q %q' "${READ_FILE}" "$1")"
	parts=0
	while [ -n "${command}" ]; do
		part="$(eval "${command}")"
		parts=$((parts + 1))
		[ "${parts}" -le 20 ]
		command="$(next_command "${part}" "$1")"
		printf '%s\n' "${part%$'\n'*}" >> "${delivered}"
	done

	[ "${parts}" -ge 3 ]
	diff "${delivered}" <(numbered "$1")
}

# Runs one fixed sequence of read-file calls that covers explicit first lines, the end of a file, a first line past
# the end, usage errors, and the continuation of repeated reads within one Codex thread.
#
# Arguments:
#   $1 - The read-file script to run.
#   $2 - Path of a file several parts long.
#   $3 - Path of a file smaller than one part.
#   $4 - New directory that holds the script's per-thread state.
#
# Output:
#   The standard output, standard error and exit status of every call, in order.
transcript()
{
	mkdir "$4"
	(
		export TMPDIR="$4"
		unset CODEX_THREAD_ID
		"$1" "$3" 2>&1; echo "status $?"
		"$1" "$2" 2>&1; echo "status $?"
		"$1" "$2" 400 2>&1; echo "status $?"
		"$1" "$3" 9 2>&1; echo "status $?"
		"$1" 2>&1; echo "status $?"
		"$1" "$3" 0 2>&1; echo "status $?"
		"$1" "$3.missing" 2>&1; echo "status $?"
		export CODEX_THREAD_ID=thread-under-test
		for _ in $(seq 1 15); do
			"$1" "$2" 2>&1; echo "status $?"
		done
		"$1" "$2" 1 2>&1; echo "status $?"
		"$1" "$2" 2>&1; echo "status $?"
	)
}

# Creates a directory that holds links to the host commands that read-file needs, other than stat and the SHA-256
# command.
#
# Arguments:
#   $1 - New directory to create.
link_common_tools()
{
	mkdir "$1"
	for tool in bash awk readlink mkdir cut seq; do
		ln -s "$(command -v "${tool}")" "$1/${tool}"
	done
}

# Writes the commands that stat and the SHA-256 command of a tool directory run on this host: the host's stat if it
# accepts the format option of the wanted family, and otherwise the host's stat with the other family's option and
# fields; sha256sum if the host has it, and otherwise "shasum -a 256". The tests run on Linux, which has GNU tools, and
# on macOS, which has BSD stat and shasum.
#
# Arguments:
#   $1 - The format option that the tool directory's stat accepts: -c for GNU stat, -f for BSD stat.
#
# Output:
#   Shell code that defines run_host_stat, which takes that option's format and the paths, and run_host_sha256, which
#   hashes standard input.
host_tool_commands()
{
	host_stat="$(command -v stat)"
	if [ "$1" = -c ]; then
		other_option=-f
		other_fields='format="${format//%Y/%m}"; format="${format//%s/%z}"'
	else
		other_option=-c
		other_fields='format="${format//%m/%Y}"; format="${format//%z/%s}"'
	fi
	if "${host_stat}" "$1" '' / > /dev/null 2>&1; then
		printf 'run_host_stat() { format="$1"; shift; exec %q %q "${format}" "$@"; }\n' "${host_stat}" "$1"
	else
		printf 'run_host_stat() { format="$1"; shift; %s; exec %q %q "${format}" "$@"; }\n' "${other_fields}" \
			"${host_stat}" "${other_option}"
	fi
	if command -v sha256sum > /dev/null; then
		printf 'run_host_sha256() { exec %q; }\n' "$(command -v sha256sum)"
	else
		printf 'run_host_sha256() { exec %q -a 256; }\n' "$(command -v shasum)"
	fi
}

# Creates a directory of the commands read-file needs in which, as on Linux, stat accepts only GNU options and the
# SHA-256 command is sha256sum.
#
# Output:
#   The directory, for use as PATH.
gnu_tool_directory()
{
	tools="${TEST_ROOT}/gnu-tools"
	link_common_tools "${tools}"
	cat > "${tools}/stat" <<-EOF
	#!${BASH}
	# Accepts only GNU stat's -c option.
	$(host_tool_commands -c)
	if [ "\$1" != -c ]; then
		echo "stat: invalid option -- '\${1#-}'" >&2
		exit 1
	fi
	shift
	run_host_stat "\$@"
	EOF
	cat > "${tools}/sha256sum" <<-EOF
	#!${BASH}
	# Accepts no arguments and reads standard input, as read-file calls it.
	$(host_tool_commands -c)
	[ "\$#" -eq 0 ] || exit 1
	run_host_sha256
	EOF
	chmod 755 "${tools}/stat" "${tools}/sha256sum"
	printf '%s\n' "${tools}"
}

# Creates a directory of the commands read-file needs in which, as on macOS, stat accepts only BSD options and the
# only SHA-256 command is shasum.
#
# Output:
#   The directory, for use as PATH.
bsd_tool_directory()
{
	tools="${TEST_ROOT}/bsd-tools"
	link_common_tools "${tools}"
	cat > "${tools}/stat" <<-EOF
	#!${BASH}
	# Accepts only BSD stat's -f option.
	$(host_tool_commands -f)
	if [ "\$1" != -f ]; then
		echo "stat: illegal option -- \${1#-}" >&2
		exit 1
	fi
	shift
	run_host_stat "\$@"
	EOF
	cat > "${tools}/shasum" <<-EOF
	#!${BASH}
	# Accepts only "-a 256" and reads standard input, as read-file calls it.
	$(host_tool_commands -f)
	[ "\$*" = "-a 256" ] || exit 1
	run_host_sha256
	EOF
	chmod 755 "${tools}/stat" "${tools}/shasum"
	printf '%s\n' "${tools}"
}

@test "packaged Codex read-file prints a file smaller than one part with numbered lines and its end marker" {
	file="${TEST_ROOT}/small.txt"
	printf 'first\n\tindented\nlast\n' > "${file}"

	run "${READ_FILE}" "${file}"

	[ "${status}" -eq 0 ]
	[ "${output}" = "$(numbered "${file}")"$'\n'"[end of ${file}]" ]
}

@test "following each part's command delivers every line of a large file exactly once in parts of at most 18,000 bytes" {
	file="${TEST_ROOT}/large notes.txt"
	write_large_file "${file}"

	assert_commands_deliver_every_line_once "${file}"
}

@test "following each part's command delivers every line once for a path with a backslash and quotes" {
	file="${TEST_ROOT}/it's a \\back\"slash\".txt"
	write_large_file "${file}"

	assert_commands_deliver_every_line_once "${file}"
}

@test "parts of a non-ASCII file hold at most 18,000 bytes in a UTF-8 locale" {
	file="${TEST_ROOT}/non-ascii.txt"
	awk 'BEGIN { for (i = 1; i <= 2000; ++i) { printf "ligne %d :", i; for (j = 0; j < i % 17; ++j) printf " été→✓"
		printf "\n" } }' > "${file}"
	export LC_ALL=C.UTF-8

	assert_commands_deliver_every_line_once "${file}"
}

@test "for a plain path the packaged read-file prints exactly what the certified script printed" {
	file="${TEST_ROOT}/large.txt"
	write_large_file "${file}"
	small="${TEST_ROOT}/small.txt"
	printf 'first\nsecond\n' > "${small}"
	mkdir "${TEST_ROOT}/bin"
	script="${TEST_ROOT}/bin/read-file"

	gnu_tools="$(gnu_tool_directory)"

	install -m 755 "${BATS_TEST_DIRNAME}/fixtures/read-file-certified" "${script}"
	certified="$(PATH="${gnu_tools}" transcript "${script}" "${file}" "${small}" "${TEST_ROOT}/certified-state")"
	install -m 755 "${READ_FILE}" "${script}"
	packaged="$(PATH="${gnu_tools}" transcript "${script}" "${file}" "${small}" "${TEST_ROOT}/packaged-state")"

	[ "$(grep -c 'continues: run' <<< "${certified}")" -ge 6 ]
	[[ "${certified}" == *"[you already have all of ${file}; to read it again, run ${script} ${file} 1]"* ]]
	diff <(printf '%s\n' "${certified}") <(printf '%s\n' "${packaged}")
}

@test "with BSD stat and shasum instead of the GNU tools read-file prints the same output" {
	file="${TEST_ROOT}/large.txt"
	write_large_file "${file}"
	small="${TEST_ROOT}/small.txt"
	printf 'first\nsecond\n' > "${small}"
	gnu_tools="$(gnu_tool_directory)"
	bsd_tools="$(bsd_tool_directory)"

	gnu="$(PATH="${gnu_tools}" transcript "${READ_FILE}" "${file}" "${small}" "${TEST_ROOT}/gnu-state")"
	bsd="$(PATH="${bsd_tools}" transcript "${READ_FILE}" "${file}" "${small}" "${TEST_ROOT}/bsd-state")"

	[ -n "$(find "${TEST_ROOT}/bsd-state" -type f)" ]
	diff <(printf '%s\n' "${gnu}") <(printf '%s\n' "${bsd}")
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
