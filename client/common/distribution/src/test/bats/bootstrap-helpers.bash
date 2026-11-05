# Sets up one isolated filesystem, harness homes and temporary directory for a test file.
setup()
{
	export TEST_ROOT="${BATS_TEST_TMPDIR}/root with spaces"
	export FAKE_BIN="${TEST_ROOT}/bin"
	export COMMAND_LOG="${TEST_ROOT}/commands.log"
	export URL_LOG="${TEST_ROOT}/urls.log"
	# The fake installer writes the arguments that it received here, one per line
	export INSTALL_ARGUMENTS="${TEST_ROOT}/install-arguments"
	# The fake installer writes the path that it ran from, and what it found in its plugin's Java runtime, here
	export INSTALL_LOCATION="${TEST_ROOT}/install-location"
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
	unset CLAUDECODE CODEX_THREAD_ID CAT_BOOTSTRAP_REPOSITORY CAT_RELEASE_BASE_URL FAKE_INSTALL_STATUS
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

# Writes the name of the fake release's plugin archive for a harness on the current platform, without its extension.
#
# Arguments:
#   $1 - The harness, claude or codex.
plugin_name()
{
	printf '%s\n' "cat-$1-$(current_platform)-v1.0"
}

# Publishes a fake CAT release v1.0 for the current platform: one plugin archive for each harness, their SHA-256 list
# and the release's cat.sh, which the generator fills with that list. Also creates a fake curl that serves the
# release's files by name.
#
# Each plugin archive holds the plugin folder, whose bin/installer stands for the plugin's installer and whose
# bin/runtime holds a marker for the plugin's Java runtime. That launcher writes each argument it receives on its own
# line to INSTALL_ARGUMENTS, writes the path it ran from and the content of the runtime marker in its bin folder to
# INSTALL_LOCATION, prints an install result and exits with FAKE_INSTALL_STATUS, or zero when that is unset. The fake
# curl appends each URL that it is asked for to URL_LOG and fails as curl -f does when the release has no such file.
#
# Side effects:
#   Creates FAKE_RELEASE_DIRECTORY and FAKE_BIN/curl.
create_fake_release()
{
	staging="${TEST_ROOT}/release staging"
	mkdir -p "${FAKE_RELEASE_DIRECTORY}"
	: > "${TEST_ROOT}/SHA256SUMS.txt"
	for harness in claude codex; do
		plugin_directory="$(plugin_name "${harness}")"
		mkdir -p "${staging}/${plugin_directory}/bin/runtime"
		printf 'release runtime\n' > "${staging}/${plugin_directory}/bin/runtime/marker"
		cat > "${staging}/${plugin_directory}/bin/installer" <<'SCRIPT'
#!/usr/bin/env sh
printf '%s\n' "$@" > "${INSTALL_ARGUMENTS}"
bin="$(cd "$(dirname -- "$0")" && pwd)"
printf '%s\n%s\n' "$0" "$(cat "${bin}/runtime/marker")" > "${INSTALL_LOCATION}"
printf '%s\n' '{"result":"installed"}'
exit "${FAKE_INSTALL_STATUS:-0}"
SCRIPT
		chmod +x "${staging}/${plugin_directory}/bin/installer"
		pack_archive "${staging}/${plugin_directory}" "${FAKE_RELEASE_DIRECTORY}/${plugin_directory}.tar.gz"
		sha256_line "${FAKE_RELEASE_DIRECTORY}/${plugin_directory}.tar.gz" >> "${TEST_ROOT}/SHA256SUMS.txt"
	done
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

# Writes a path in the form that native programs on the platform that uname reports read.
#
# Arguments:
#   $1 - The path.
#
# Output:
#   Writes the Windows form of the path when uname reports Windows, and the path unchanged elsewhere.
native_path()
{
	case "$(current_platform)" in
		windows-*) cygpath -w "$1" ;;
		*) printf '%s\n' "$1" ;;
	esac
}

# Makes a test that installs Codex through cat.sh see Linux when the tests run under Git Bash, because cat.sh refuses
# Codex on Windows, where PowerShell installs it. Elsewhere it changes nothing. The test must put FAKE_BIN first in
# PATH before it creates the fake release, so that the release and cat.sh name the same platform.
#
# Side effects:
#   Under Git Bash, creates a uname replacement in FAKE_BIN that reports Linux on x86_64.
simulate_posix_platform()
{
	case "$(current_platform)" in
		windows-*) create_fake_uname Linux x86_64 ;;
	esac
}

# Checks that the last single command ran the install of the installer in the release's extracted plugin archive for a
# harness, with exactly the arguments that install accepts, and that the temporary directory it used is gone.
#
# Arguments:
#   $1 - The harness, claude or codex.
#   $2 - The harness home that install must receive.
#
# Environment:
#   INSTALL_ARGUMENTS and INSTALL_LOCATION hold what the fake installer recorded; TMPDIR is the entry script's
#   temporary directory parent.
expect_install_ran_for()
{
	[ "$(cat "${INSTALL_ARGUMENTS}")" = "--home
$(native_path "$2")
install
--harness
$1" ]
	launcher="$(sed -n '1p' "${INSTALL_LOCATION}")"
	[[ "${launcher}" == "${TMPDIR}/"*"/$(plugin_name "$1")/bin/installer" ]]
	[ "$(sed -n '2p' "${INSTALL_LOCATION}")" = "release runtime" ]
	[ -z "$(ls -A "${TMPDIR}")" ]
}
