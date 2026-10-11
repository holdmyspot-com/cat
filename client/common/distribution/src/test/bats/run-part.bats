#!/usr/bin/env bats

# Tests the Codex image's run-part and read-part launchers: output that run-part saves and that read-part prints in
# later calls by following the line that ends each part, the command's exit status, a file whose name is not ASCII in
# the C locale, the caller's locale for the command, and usage errors.

bats_require_minimum_version 1.5.0

# Bats invokes this before each test to create its fixture directory under the project's .cat/work/temp and to keep
# the saved output and Codex's configuration inside it.
#
# Side effects:
#   Sets GENERATED_RUNTIME_DIRECTORY, BIN, TEST_ROOT, TMPDIR and CODEX_HOME, and unsets CODEX_THREAD_ID.
setup()
{
	export GENERATED_RUNTIME_DIRECTORY="${BATS_TEST_DIRNAME}/../../../target/runtime"
	BIN="${GENERATED_RUNTIME_DIRECTORY}/codex/client/bin"
	cat_temp="$(realpath "${BATS_TEST_DIRNAME}/../../../../../..")/.cat/work/temp"
	mkdir -p "${cat_temp}"
	TEST_ROOT="$(mktemp -d -p "${cat_temp}" run-part-XXXXXX)"
	export TEST_ROOT
	export TMPDIR="${TEST_ROOT}/tmp"
	mkdir "${TMPDIR}"
	export CODEX_HOME="${TEST_ROOT}/codex"
	mkdir "${CODEX_HOME}"
	unset CODEX_THREAD_ID
}

# Removes the test's fixture directory after its last assertion completes.
#
# Side effects:
#   Deletes the temporary directory created by setup.
teardown()
{
	if [ -n "${TEST_ROOT:-}" ]; then
		rm -rf -- "${TEST_ROOT}"
	fi
}

@test "read-part prints the output that run-part saved, part by part, and the last part exits with its status" {
	printf 'tool_output_token_limit = 1000\n' > "${CODEX_HOME}/config.toml"
	export CODEX_THREAD_ID=thread
	part="${TEST_ROOT}/part"
	lines="${TEST_ROOT}/lines"
	status=0
	"${BIN}/run-part" > "${part}" <<'EOF' || status=$?
seq 1 3000
exit 5
EOF
	calls=1
	while true; do
		# Each byte of these parts is ASCII, and JSON escapes each newline in 2 bytes.
		shown=$(( $(wc -c < "${part}") + $(wc -l < "${part}") ))
		[ "${shown}" -le $(( 1000 * 4 - 512 )) ]
		last=$(tail -n 1 "${part}")
		sed '$d' "${part}" >> "${lines}"
		case ${last} in
			"[More output follows. Before any other command or answer, run: "*)
				[ "${status}" -eq 0 ]
				next=${last#*run: }
				next=${next%]}
				status=0
				eval "${next}" > "${part}" || status=$?
				calls=$(( calls + 1 ))
				;;
			"[End of "*)
				break
				;;
			*)
				false
				;;
		esac
	done
	[ "${status}" -eq 5 ]
	[ "${calls}" -gt 1 ]
	seq 1 3000 | cmp - "${lines}"
}

@test "read-part prints a file whose name is not ASCII in the C locale" {
	file="${TEST_ROOT}/café.txt"
	printf 'first\nsecond\n' > "${file}"
	run env LC_ALL=C LANG=C "${BIN}/read-part" "${file}"
	[ "${status}" -eq 0 ]
	[ "${output}" = "$(printf 'first\nsecond\n[End of %s]' "${file}")" ]
}

@test "run-part runs the command in the caller's locale" {
	run env LC_ALL=C LC_CTYPE=POSIX "${BIN}/run-part" <<'EOF'
printf '%s|%s|%s\n' "${LC_ALL-unset}" "${LC_CTYPE-unset}" "${CAT_CALLER_LC_ALL-unset}"
EOF
	[ "${status}" -eq 0 ]
	[ "${lines[0]}" = "C|POSIX|unset" ]
	run env -u LC_ALL -u LC_CTYPE "${BIN}/run-part" <<'EOF'
printf '%s|%s\n' "${LC_ALL-unset}" "${LC_CTYPE-unset}"
EOF
	[ "${status}" -eq 0 ]
	[ "${lines[0]}" = "unset|unset" ]
}

@test "run-part without a command prints its usage" {
	run "${BIN}/run-part" < /dev/null
	[ "${status}" -eq 2 ]
	[ "${output}" = "usage: run-part <<'EOF', then the command on the lines that follow, then EOF" ]
}
