# Sets up one isolated filesystem, harness homes and temporary directory for a test file.
setup()
{
	export TEST_ROOT="${BATS_TEST_TMPDIR}/root with spaces"
	export FAKE_BIN="${TEST_ROOT}/bin"
	export COMMAND_LOG="${TEST_ROOT}/commands.log"
	export URL_LOG="${TEST_ROOT}/urls.log"
	# The fake updater writes the arguments that setup received here, one per line
	export SETUP_ARGUMENTS="${TEST_ROOT}/setup-arguments"
	# The fake updater writes the path that it ran from, and what it found beside its version directory, here
	export SETUP_LOCATION="${TEST_ROOT}/setup-location"
	export HOME="${TEST_ROOT}/home"
	export CODEX_HOME="${HOME}/codex home"
	export CLAUDE_CONFIG_DIR="${HOME}/claude home"
	export TMPDIR="${TEST_ROOT}/tmp"
	export COMMON_RELEASE_DIRECTORY="${BATS_TEST_DIRNAME}/../../../../lifecycle/src/main/release"
	export GENERATED_RUNTIME_DIRECTORY="${BATS_TEST_DIRNAME}/../../../target/runtime"
	export FAKE_RELEASE_DIRECTORY="${TEST_ROOT}/release"
	# Every asset of the release under test, the entry script included, is downloaded from below this URL
	export RELEASE_DOWNLOAD_URL="https://github.com/holdmyspot-com/cat/releases/download/v1.0"
	# The single command as users paste it, which the fake curl serves from FAKE_RELEASE_DIRECTORY
	export SINGLE_COMMAND_URL="${RELEASE_DOWNLOAD_URL}/cat.sh"
	# The harness that runs the tests, and the user's own overrides, must not decide what the single command does
	unset CLAUDECODE CODEX_THREAD_ID CAT_BOOTSTRAP_REPOSITORY CAT_RELEASE_BASE_URL FAKE_SETUP_STATUS
	# Output capture belongs to the parent command, not the generated hook's isolated harness environment.
	unset CAT_RUN_PART_OUTPUT
	mkdir -p "${FAKE_BIN}" "${HOME}" "${TMPDIR}"
	: > "${COMMAND_LOG}"
	: > "${URL_LOG}"
}

# Runs the single command as a user pastes it into a shell: curl downloads the release's cat.sh and pipes it to sh.
#
# Arguments:
#   $@ - The arguments that the user passes to cat.sh, such as --harness claude.
#
# Environment:
#   PATH must find the fake curl from create_fake_release first.
#
# Exit status:
#   Returns the exit status of cat.sh.
single_command()
{
	curl -fsSL "${SINGLE_COMMAND_URL}" | sh -s -- "$@"
}

# Creates a uname replacement that reports an operating system and a processor.
#
# Arguments:
#   $1 - The operating system's name, as uname -s reports it.
#   $2 - The processor's name, as uname -m reports it; x86_64 when absent.
create_fake_uname()
{
	cat > "${FAKE_BIN}/uname" <<SCRIPT
#!/usr/bin/env sh
case "\$1" in
	-s) printf '%s\\n' '$1' ;;
	-m) printf '%s\\n' '${2:-x86_64}' ;;
esac
SCRIPT
	chmod +x "${FAKE_BIN}/uname"
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

# Writes the SHA-256 digest line of a file in the form that sha256sum writes, naming the file by its base name.
#
# Arguments:
#   $1 - The file.
#
# Output:
#   Writes the digest, two spaces and the file's base name to standard output.
sha256_line()
{
	if command -v sha256sum >/dev/null 2>&1; then
		digest="$(sha256sum < "$1" | awk '{ print $1 }')"
	else
		digest="$(shasum -a 256 < "$1" | awk '{ print $1 }')"
	fi
	printf '%s  %s\n' "${digest}" "$(basename -- "$1")"
}

# Packs a directory into a release archive that holds it as its one top directory.
#
# Arguments:
#   $1 - The directory, whose name becomes the archive's top directory.
#   $2 - The archive to write.
pack_archive()
{
	# Reading and writing through redirections keeps GNU tar from taking a Windows drive, such as C:, for a host
	tar -czf - -C "$(dirname -- "$1")" "$(basename -- "$1")" > "$2"
}

# Publishes a fake CAT release v1.0 for the current platform: the updater's archive, a plugin archive that the entry
# script must not download, their SHA-256 list and the release's cat.sh, which the generator fills with that list. Also
# creates a fake curl that serves the release's files by name.
#
# The updater archive mirrors the installed layout: a runtime directory beside the version directory 1.0, whose
# bin/cat-updater stands for the release's updater. That launcher writes each argument it receives on its own line to
# SETUP_ARGUMENTS, writes the path it ran from and the content of the runtime marker beside its version directory to
# SETUP_LOCATION, prints a setup result and exits with FAKE_SETUP_STATUS, or zero when that is unset. The fake curl
# appends each URL that it is asked for to URL_LOG and fails as curl -f does when the release has no such file.
#
# Side effects:
#   Creates FAKE_RELEASE_DIRECTORY and FAKE_BIN/curl.
create_fake_release()
{
	updater_directory="cat-updater-v1.0-$(current_platform)"
	plugin_directory="cat-claude-v1.0-$(current_platform)"
	staging="${TEST_ROOT}/release staging"
	mkdir -p "${FAKE_RELEASE_DIRECTORY}" "${staging}/${updater_directory}/1.0/bin" \
		"${staging}/${updater_directory}/runtime" "${staging}/${plugin_directory}"
	printf 'release runtime\n' > "${staging}/${updater_directory}/runtime/marker"
	cat > "${staging}/${updater_directory}/1.0/bin/cat-updater" <<'SCRIPT'
#!/usr/bin/env sh
printf '%s\n' "$@" > "${SETUP_ARGUMENTS}"
version_directory="$(cd "$(dirname -- "$0")/.." && pwd)"
printf '%s\n%s\n' "$0" "$(cat "${version_directory}/../runtime/marker")" > "${SETUP_LOCATION}"
printf '%s\n' '{"result":"set_up"}'
exit "${FAKE_SETUP_STATUS:-0}"
SCRIPT
	chmod +x "${staging}/${updater_directory}/1.0/bin/cat-updater"
	printf 'plugin\n' > "${staging}/${plugin_directory}/marker"
	pack_archive "${staging}/${updater_directory}" "${FAKE_RELEASE_DIRECTORY}/${updater_directory}.tar.gz"
	pack_archive "${staging}/${plugin_directory}" "${FAKE_RELEASE_DIRECTORY}/${plugin_directory}.tar.gz"
	{
		sha256_line "${FAKE_RELEASE_DIRECTORY}/${plugin_directory}.tar.gz"
		sha256_line "${FAKE_RELEASE_DIRECTORY}/${updater_directory}.tar.gz"
	} > "${TEST_ROOT}/SHA256SUMS.txt"
	sh "${COMMON_RELEASE_DIRECTORY}/generate-cat-sh.sh" "${COMMON_RELEASE_DIRECTORY}/cat.sh" v1.0 \
		"${TEST_ROOT}/SHA256SUMS.txt" "${FAKE_RELEASE_DIRECTORY}/cat.sh"
	cat > "${FAKE_BIN}/curl" <<'SCRIPT'
#!/usr/bin/env sh
output=''
url=''
while [ "$#" -gt 0 ]; do
	case "$1" in
		-o) shift; output="$1" ;;
		-*) ;;
		*) url="$1" ;;
	esac
	shift
done
printf '%s\n' "${url}" >> "${URL_LOG}"
source="${FAKE_RELEASE_DIRECTORY}/${url##*/}"
[ -f "${source}" ] || exit 22
if [ -n "${output}" ]; then
	cp "${source}" "${output}"
else
	cat "${source}"
fi
SCRIPT
	chmod +x "${FAKE_BIN}/curl"
}

# Writes a path in the form that native programs on the current platform read.
#
# Arguments:
#   $1 - The path.
#
# Output:
#   Writes the Windows form of the path under Git Bash, and the path unchanged elsewhere.
native_path()
{
	if command -v cygpath >/dev/null 2>&1; then
		cygpath -w "$1"
	else
		printf '%s\n' "$1"
	fi
}

# Checks that the last single command ran the updater setup from the release's extracted updater archive, for a
# harness and home, with exactly the arguments setup accepts, and that the temporary directory it used is gone.
#
# Arguments:
#   $1 - The harness, claude or codex.
#   $2 - The harness home that setup must receive.
#
# Environment:
#   SETUP_ARGUMENTS and SETUP_LOCATION hold what the fake updater recorded; TMPDIR is the entry script's temporary
#   directory parent.
expect_setup_ran_for()
{
	[ "$(cat "${SETUP_ARGUMENTS}")" = "setup
--harness
$1
--home
$(native_path "$2")" ]
	launcher="$(sed -n '1p' "${SETUP_LOCATION}")"
	[[ "${launcher}" == "${TMPDIR}/"*"/cat-updater-v1.0-$(current_platform)/1.0/bin/cat-updater" ]]
	[ "$(sed -n '2p' "${SETUP_LOCATION}")" = "release runtime" ]
	[ -z "$(ls -A "${TMPDIR}")" ]
}
