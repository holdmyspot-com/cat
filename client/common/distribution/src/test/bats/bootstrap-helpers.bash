# Sets up one isolated filesystem and harness homes for a bootstrap test file.
setup()
{
	export TEST_ROOT="${BATS_TEST_TMPDIR}/root with spaces"
	export FAKE_BIN="${TEST_ROOT}/bin"
	export COMMAND_LOG="${TEST_ROOT}/commands.log"
	export HOME="${TEST_ROOT}/home"
	export CODEX_HOME="${HOME}/.codex"
	export CLAUDE_CONFIG_DIR="${HOME}/.claude"
	export COMMON_INSTALL_DIRECTORY="${BATS_TEST_DIRNAME}/../../../../lifecycle/src/main/resources/install"
	export CLAUDE_INSTALL_DIRECTORY="${BATS_TEST_DIRNAME}/../../../../../claude/lifecycle/src/main/resources/install"
	export CODEX_INSTALL_DIRECTORY="${BATS_TEST_DIRNAME}/../../../../../codex/lifecycle/src/main/resources/install"
	export INSTALL_DIRECTORY="${TEST_ROOT}/install"
	export GENERATED_RUNTIME_DIRECTORY="${BATS_TEST_DIRNAME}/../../../target/runtime"
	export FAKE_RELEASE_DIRECTORY="${TEST_ROOT}/release"
	# The single command as users paste it, which the fake curl serves from FAKE_RELEASE_DIRECTORY
	export SINGLE_COMMAND_URL="https://raw.githubusercontent.com/holdmyspot-com/cat/v1.0/client/common/lifecycle/src/\
main/resources/install/cat.sh"
	# The harness that runs the tests must not decide which harness the single command installs for
	unset CLAUDECODE CODEX_THREAD_ID
	mkdir -p "${FAKE_BIN}" "${HOME}" "${INSTALL_DIRECTORY}"
	cp -R "${COMMON_INSTALL_DIRECTORY}/." "${INSTALL_DIRECTORY}/"
	cp -R "${CLAUDE_INSTALL_DIRECTORY}/." "${INSTALL_DIRECTORY}/"
	cp -R "${CODEX_INSTALL_DIRECTORY}/." "${INSTALL_DIRECTORY}/"
	: > "${COMMAND_LOG}"
}

# Restores write permission on everything a test made read-only, so that Bats can delete the test's directory.
teardown()
{
	chmod -R u+w "${TEST_ROOT}" 2>/dev/null || true
}

# Runs the single command as a user pastes it into a shell: curl downloads cat.sh and pipes it to sh.
#
# Arguments:
#   $@ - The arguments that the user passes to cat.sh, such as v1.0 or --uninstall.
#
# Environment:
#   PATH must find the fake curl from create_fake_release first, and publish_install_scripts must have published the
#   scripts that cat.sh downloads.
#
# Exit status:
#   Returns the exit status of cat.sh.
single_command()
{
	curl -fsSL "${SINGLE_COMMAND_URL}" | sh -s -- "$@"
}

# Publishes the installer scripts in the fake release, so that the fake curl serves them as GitHub serves them to the
# single command and to the per-harness bootstrap scripts.
publish_install_scripts()
{
	mkdir -p "${FAKE_RELEASE_DIRECTORY}"
	cp "${INSTALL_DIRECTORY}/cat.sh" "${INSTALL_DIRECTORY}/bootstrap.sh" "${INSTALL_DIRECTORY}/bootstrap-uninstall.sh" \
		"${INSTALL_DIRECTORY}/common.sh" "${INSTALL_DIRECTORY}/install-claude.sh" \
		"${INSTALL_DIRECTORY}/uninstall-claude.sh" "${INSTALL_DIRECTORY}/install-codex.sh" \
		"${INSTALL_DIRECTORY}/uninstall-codex.sh" "${FAKE_RELEASE_DIRECTORY}/"
}

# Removes write permission from a file or directory, and skips the test when the file system still lets it be written,
# as it does for root and on Windows.
#
# Arguments:
#   $1 - The existing file or directory.
make_read_only()
{
	chmod a-w "$1"
	if [ -d "$1" ]; then
		if (: > "$1/.write-check") 2>/dev/null; then
			rm -f "$1/.write-check"
			chmod u+w "$1"
			skip "this user can write to a directory without write permission"
		fi
	elif (: >> "$1") 2>/dev/null; then
		chmod u+w "$1"
		skip "this user can write to a file without write permission"
	fi
}

# Writes a digest of a directory's tree: every path below it and the checksum of every file, in a stable order.
#
# Arguments:
#   $1 - The directory.
tree_digest()
{
	(cd "$1" && find . -print | LC_ALL=C sort && find . -type f -exec cksum {} + | LC_ALL=C sort)
}

# Creates a fake harness command that appends its invocation to the test command log.
create_fake_engine_command()
{
	harness="$1"
	cat > "${FAKE_BIN}/${harness}" <<'SCRIPT'
#!/usr/bin/env sh
printf '%s %s\n' "$(basename "$0")" "$*" >> "${COMMAND_LOG}"
SCRIPT
	chmod +x "${FAKE_BIN}/${harness}"
}

# Creates a uname replacement that reports an operating system on a 64-bit x86 processor.
#
# Arguments:
#   $1 - The operating system's name, as uname -s reports it.
create_fake_uname()
{
	cat > "${FAKE_BIN}/uname" <<SCRIPT
#!/usr/bin/env sh
case "\$1" in
	-s) printf '%s\\n' '$1' ;;
	-m) printf '%s\\n' x86_64 ;;
esac
SCRIPT
	chmod +x "${FAKE_BIN}/uname"
}

# Creates a bubblewrap replacement that fails as bubblewrap does inside a sandbox that cannot create another namespace.
create_failing_bwrap()
{
	cat > "${FAKE_BIN}/bwrap" <<'SCRIPT'
#!/usr/bin/env sh
printf '%s\n' 'bwrap: No permissions to create new namespace' >&2
exit 1
SCRIPT
	chmod +x "${FAKE_BIN}/bwrap"
}

# Creates a minimal extracted CAT artifact for the supplied harness and writes its directory to standard output.
create_test_artifact()
{
	harness="$1"
	artifact="${TEST_ROOT}/${harness} artifact"
	mkdir -p "${artifact}/.${harness}-plugin" "${artifact}/bin" "${artifact}/client" "${artifact}/install/migrations"
	if [ "${harness}" = codex ]; then
		cp "${CODEX_INSTALL_DIRECTORY}/codex-marketplace.json" "${artifact}/install/codex-marketplace.json"
		mkdir -p "${artifact}/agents/codex"
		cat > "${artifact}/agents/codex/reviewer.toml" <<'SCRIPT'
name = "cat-reviewer"
developer_instructions = '''
Review evidence without editing.
'''
SCRIPT
	else
		cp "${CLAUDE_INSTALL_DIRECTORY}/claude-marketplace.json" "${artifact}/install/claude-marketplace.json"
	fi
	printf '{}\n' > "${artifact}/.${harness}-plugin/plugin.json"
	printf '1.0\n' > "${artifact}/client/VERSION"
	printf '# target-version<TAB>script-relative-to-this-directory\n' > "${artifact}/install/migrations/registry.tsv"
	cat > "${artifact}/bin/plugin-info" <<'SCRIPT'
#!/usr/bin/env sh
printf 'plugin-info\n' >> "${COMMAND_LOG}"
SCRIPT
	chmod +x "${artifact}/bin/plugin-info"
	printf '%s\n' "${artifact}"
}

# Adds a versioned migration script to a fake extracted artifact.
add_test_migration()
{
	artifact="$1"
	target_version="$2"
	script_body="$3"
	script_name="${target_version}.sh"
	printf '%s\t%s\n' "${target_version}" "${script_name}" >> "${artifact}/install/migrations/registry.tsv"
	printf '%s\n' "${script_body}" > "${artifact}/install/migrations/${script_name}"
}

# Writes a path as a JSON string's content in the form that harnesses give hooks: a Windows path under Git Bash, whose
# own form names a folder that Windows programs cannot find, and the path unchanged elsewhere.
#
# Arguments:
#   $1 - The path.
json_path()
{
	if command -v cygpath >/dev/null 2>&1; then
		cygpath -w "$1" | sed 's/\\/\\\\/g'
	else
		printf '%s\n' "$1"
	fi
}

# Writes the CAT platform identifier of the current operating system and architecture.
current_platform()
{
	case "$(uname -s):$(uname -m)" in
		Linux:x86_64|Linux:amd64) printf '%s\n' linux-x64 ;;
		Linux:aarch64|Linux:arm64) printf '%s\n' linux-arm64 ;;
		Darwin:x86_64|Darwin:amd64) printf '%s\n' macos-x64 ;;
		Darwin:aarch64|Darwin:arm64) printf '%s\n' macos-arm64 ;;
		MINGW64_NT*:x86_64|MINGW64_NT*:amd64|MSYS_NT*:x86_64|MSYS_NT*:amd64) printf '%s\n' windows-x64 ;;
		*) return 1 ;;
	esac
}

# Publishes a release archive of a minimal CAT artifact for the current platform with its checksum list, and creates a
# fake curl that serves the release's files by name and appends each invocation to the test command log.
#
# Arguments:
#   $1 - Harness identifier, such as claude or codex.
#
# Output:
#   Writes the archive's file name to standard output.
#
# Side effects:
#   Creates FAKE_RELEASE_DIRECTORY and FAKE_BIN/curl.
create_fake_release()
{
	harness="$1"
	archive_directory="cat-${harness}-v1.0-$(current_platform)"
	mkdir -p "${FAKE_RELEASE_DIRECTORY}/staging"
	cp -R "$(create_test_artifact "${harness}")" "${FAKE_RELEASE_DIRECTORY}/staging/${archive_directory}"
	# GNU tar reads an archive path that starts with a Windows drive, such as C:, as a remote host's name
	(cd "${FAKE_RELEASE_DIRECTORY}" && tar -czf "${archive_directory}.tar.gz" -C staging "${archive_directory}")
	if command -v sha256sum >/dev/null 2>&1; then
		(cd "${FAKE_RELEASE_DIRECTORY}" && sha256sum "${archive_directory}.tar.gz") > \
			"${FAKE_RELEASE_DIRECTORY}/SHA256SUMS.txt"
	else
		(cd "${FAKE_RELEASE_DIRECTORY}" && shasum -a 256 "${archive_directory}.tar.gz") > \
			"${FAKE_RELEASE_DIRECTORY}/SHA256SUMS.txt"
	fi
	cat > "${FAKE_BIN}/curl" <<'SCRIPT'
#!/usr/bin/env sh
printf 'curl %s\n' "$*" >> "${COMMAND_LOG}"
output=''
url=''
while [ "$#" -gt 0 ]; do
	case "$1" in
		-o) shift; output="$1" ;;
		-*) ;;
		https://*) url="$1" ;;
		*) exit 22 ;;
	esac
	shift
done
source="${FAKE_RELEASE_DIRECTORY}/${url##*/}"
[ -f "${source}" ] || exit 22
if [ -n "${output}" ]; then
	cp "${source}" "${output}"
else
	cat "${source}"
fi
SCRIPT
	chmod +x "${FAKE_BIN}/curl"
	printf '%s\n' "${archive_directory}.tar.gz"
}

# Runs one operation through the single command and checks that it changed nothing because a path was not writable.
#
# The message must name the path and the command, and tell an agent that ran the command to show it to the user and end
# its turn. Only Claude Code lets a user type the command at the prompt prefixed with "! ", so only the Claude message
# may say so.
#
# Arguments:
#   $1 - The harness, claude or codex.
#   $2 - The path that the operation cannot write, which its message must name.
#   $3 - The command that the message must give for running the operation outside the sandbox.
#   $4... - The single command's arguments.
#
# Environment:
#   HOME holds every directory that the operation could change; COMMAND_LOG records the harness commands.
expect_unchanged_by_single_command()
{
	expected_harness="$1"
	expected_path="$2"
	expected_command="$3"
	shift 3
	home_before="$(tree_digest "${HOME}")"
	: > "${COMMAND_LOG}"

	run single_command "$@"

	[ "${status}" -eq 77 ]
	[[ "${output}" == *"CAT changed nothing because it cannot write ${expected_path}."* ]]
	[[ "${output}" == *"sandbox"* ]]
	[[ "${output}" == *"
  ${expected_command}"* ]]
	expect_agent_told_to_hand_command_to_user "${expected_harness}"
	[ "$(tree_digest "${HOME}")" = "${home_before}" ]
	! grep -q '^claude \|^codex ' "${COMMAND_LOG}" || false
}

# Checks that the last command's output tells an agent to hand the printed command to the user, and that it offers the
# "! " prompt prefix only for Claude Code.
#
# Arguments:
#   $1 - The harness, claude or codex.
#
# Environment:
#   output holds the last command's output, as Bats sets it.
expect_agent_told_to_hand_command_to_user()
{
	[[ "${output}" == *"If you are an agent that ran the command that printed this, show the user the command above \
exactly as printed, ask them to run it outside the sandbox, and end your turn there."* ]]
	if [ "$1" = claude ]; then
		[[ "${output}" == *"prefixed with \"! \""* ]]
	else
		[[ "${output}" != *"prefixed with"* ]]
		[[ "${output}" != *'"! "'* ]]
	fi
}

# Appends the outcome of an operation to a record: its exit status, the harness commands that it ran and a digest of
# the user's home afterwards.
#
# Arguments:
#   $1 - The file that receives the record.
#   $2... - The operation's command and arguments.
record_operation()
{
	record="$1"
	shift
	: > "${COMMAND_LOG}"
	operation_status=0
	"$@" >/dev/null 2>&1 || operation_status="$?"
	{
		printf 'exit status %s\n' "${operation_status}"
		grep -v '^curl ' "${COMMAND_LOG}" || true
		tree_digest "${HOME}"
	} >> "${record}"
}
