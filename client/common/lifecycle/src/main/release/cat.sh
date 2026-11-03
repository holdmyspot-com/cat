#!/usr/bin/env sh
# Installs or uninstalls the CAT release that publishes this script, and installs CAT's trusted updater, for Claude Code
# or Codex.
#
#   curl -fsSL https://github.com/holdmyspot-com/cat/releases/download/<tag>/cat.sh | sh -s -- --harness claude
#     Installs the release's plugin, then installs the release's updater and sets it up for the harness.
#   ... | sh -s -- --harness claude --updater-only
#     Installs or upgrades only the updater and sets it up, leaving the installed plugin alone.
#   ... | sh -s -- --harness claude --uninstall [--delete-data]
#     Uninstalls the plugin and keeps the updater. CAT's Codex data is kept unless --delete-data is given.
#
# The release is fixed: the script takes no version and downloads only this release's assets, each of which it checks
# against the SHA-256 digest that it embeds before it extracts or runs anything from it. No argument or environment
# variable changes where it downloads from.
#
# Inside an agent session the script detects the harness when --harness is absent. When the installer cannot write a
# path that the operation changes, it changes nothing, exits with status 77 and prints the command to run outside the
# agent's sandbox.
set -eu

# The generator replaces these two values when it publishes the script with a release
CAT_RELEASE_TAG='@CAT_RELEASE_TAG@'
CAT_SHA256SUMS='@CAT_SHA256SUMS@'
CAT_VERSION="${CAT_RELEASE_TAG#v}"
CAT_DOWNLOAD_URL="https://github.com/holdmyspot-com/cat/releases/download/${CAT_RELEASE_TAG}"
CAT_ENTRY_URL="${CAT_DOWNLOAD_URL}/cat.sh"
# The temporary directory that holds the downloaded and extracted assets, deleted at exit
CAT_WORK_DIRECTORY=""
# The staging directory of an updater installation that has not finished, deleted at exit
CAT_STAGING_DIRECTORY=""

# Prints this script's usage and exits with the command-line usage status.
#
# Errors:
#   Writes the usage to standard error.
#
# Exit status:
#   Exits with status 2.
cat_print_usage()
{
	echo "Usage: cat.sh [--harness claude|codex]" >&2
	echo "       cat.sh [--harness claude|codex] --updater-only" >&2
	echo "       cat.sh [--harness claude|codex] --uninstall [--delete-data]" >&2
	echo "This script installs ${CAT_RELEASE_TAG} and takes no version: to install another release, run that" \
		"release's cat.sh." >&2
	exit 2
}

# Deletes the temporary and staging directories that this script created and has not handed over.
#
# Side effects:
#   Deletes CAT_WORK_DIRECTORY and CAT_STAGING_DIRECTORY when they are set.
cat_cleanup()
{
	[ -z "${CAT_STAGING_DIRECTORY}" ] || rm -rf -- "${CAT_STAGING_DIRECTORY}"
	[ -z "${CAT_WORK_DIRECTORY}" ] || rm -rf -- "${CAT_WORK_DIRECTORY}"
}

# Detects the platform identifier used in CAT release asset names.
#
# Output:
#   Writes the platform identifier to standard output.
#
# Exit status:
#   Returns zero when the operating system and architecture are supported; otherwise reports them to standard error
#   and returns nonzero.
cat_detect_platform()
{
	cat_os="$(uname -s)"
	cat_architecture="$(uname -m)"
	case "${cat_os}:${cat_architecture}" in
		Linux:x86_64|Linux:amd64) echo "linux-x64" ;;
		Linux:aarch64|Linux:arm64) echo "linux-arm64" ;;
		Darwin:x86_64|Darwin:amd64) echo "macos-x64" ;;
		Darwin:aarch64|Darwin:arm64) echo "macos-arm64" ;;
		MINGW64_NT*:x86_64|MINGW64_NT*:amd64|MSYS_NT*:x86_64|MSYS_NT*:amd64) echo "windows-x64" ;;
		*) echo "Unsupported platform: ${cat_os} ${cat_architecture}" >&2; return 1 ;;
	esac
}

# Writes the SHA-256 digest of a file.
#
# Arguments:
#   $1 - The file.
#
# Output:
#   Writes the lowercase hexadecimal digest to standard output.
#
# Exit status:
#   Returns nonzero when neither sha256sum nor shasum is available or the file cannot be read.
cat_sha256()
{
	if command -v sha256sum >/dev/null 2>&1; then
		sha256sum < "$1" | awk '{ print $1 }'
	elif command -v shasum >/dev/null 2>&1; then
		shasum -a 256 < "$1" | awk '{ print $1 }'
	else
		echo "CAT needs sha256sum or shasum to verify its downloads." >&2
		return 1
	fi
}

# Downloads one of this release's assets and verifies it against the digest that this script embeds.
#
# Arguments:
#   $1 - The asset's name, such as cat-claude-v1.0-linux-x64.tar.gz.
#
# Environment:
#   CAT_WORK_DIRECTORY - The directory that receives the asset.
#
# Output:
#   Writes the path of the verified asset to standard output.
#
# Exit status:
#   Exits nonzero, keeping nothing of the asset, when the release lists no digest for it, the download fails or the
#   digest differs.
cat_download_verified()
{
	cat_asset="$1"
	cat_expected="$(printf '%s\n' "${CAT_SHA256SUMS}" |
		awk -v cat_name="${cat_asset}" '$2 == cat_name { print $1; cat_found = 1 } END { exit !cat_found }')" || {
		echo "CAT ${CAT_RELEASE_TAG} publishes no asset named ${cat_asset}." >&2
		exit 1
	}
	cat_file="${CAT_WORK_DIRECTORY}/${cat_asset}"
	curl -fsSL "${CAT_DOWNLOAD_URL}/${cat_asset}" -o "${cat_file}"
	cat_actual="$(cat_sha256 "${cat_file}")"
	if [ "${cat_actual}" != "${cat_expected}" ]; then
		rm -f -- "${cat_file}"
		echo "The download of ${cat_asset} does not match the SHA-256 digest that CAT ${CAT_RELEASE_TAG} published," \
			"so CAT changed nothing. Expected ${cat_expected}, received ${cat_actual}." >&2
		exit 1
	fi
	printf '%s\n' "${cat_file}"
}

# Extracts a verified archive into an empty directory and requires that it holds exactly the expected top directory.
#
# Arguments:
#   $1 - The verified archive.
#   $2 - The empty directory that receives its contents.
#   $3 - The name of the one directory that the archive must hold at its top.
#
# Exit status:
#   Exits nonzero when extraction fails or the archive holds anything other than that one directory.
cat_extract()
{
	# Reading the archive from standard input keeps tar from taking a Windows drive, such as C:, for a remote host
	(cd "$2" && tar -xzf - < "$1")
	if [ "$(ls -A "$2")" != "$3" ] || [ ! -d "$2/$3" ] || [ -L "$2/$3" ]; then
		echo "The archive $(basename -- "$1") does not hold exactly the directory $3." >&2
		exit 1
	fi
}

# Downloads, verifies and extracts this release's plugin for the harness and platform.
#
# Arguments:
#   $1 - Harness identifier, claude or codex.
#   $2 - Platform identifier.
#
# Output:
#   Writes the extracted plugin directory to standard output.
cat_extract_plugin()
{
	cat_plugin_name="cat-$1-${CAT_RELEASE_TAG}-$2"
	cat_plugin_archive="$(cat_download_verified "${cat_plugin_name}.tar.gz")"
	cat_plugin_parent="$(mktemp -d "${CAT_WORK_DIRECTORY}/plugin.XXXXXX")"
	cat_extract "${cat_plugin_archive}" "${cat_plugin_parent}" "${cat_plugin_name}"
	printf '%s\n' "${cat_plugin_parent}/${cat_plugin_name}"
}

# Writes the directory that holds the updater's installed versions.
#
# Arguments:
#   $1 - Platform identifier.
#
# Output:
#   Writes the directory to standard output: %LOCALAPPDATA%\cat-updater on Windows, otherwise cat-updater in
#   XDG_DATA_HOME, or in ~/.local/share when XDG_DATA_HOME is unset or not an absolute path.
cat_updater_data_directory()
{
	if [ "$1" = windows-x64 ]; then
		[ -n "${LOCALAPPDATA:-}" ] || {
			echo "LOCALAPPDATA is not set, so CAT cannot find the directory for its updater." >&2
			exit 1
		}
		printf '%s/cat-updater\n' "$(cygpath -u "${LOCALAPPDATA}")"
		return
	fi
	case "${XDG_DATA_HOME:-}" in
		/*) printf '%s/cat-updater\n' "${XDG_DATA_HOME}" ;;
		*) printf '%s/.local/share/cat-updater\n' "${HOME:?HOME must be set}" ;;
	esac
}

# Writes a path in the form that the platform's native programs read.
#
# Arguments:
#   $1 - Platform identifier.
#   $2 - The path.
#
# Output:
#   Writes the Windows form of the path on Windows, and the path unchanged elsewhere.
cat_native_path()
{
	if [ "$1" = windows-x64 ]; then
		cygpath -w "$2"
	else
		printf '%s\n' "$2"
	fi
}

# Downloads, verifies and installs this release's updater as the version directory in the updater's data directory.
#
# Arguments:
#   $1 - Platform identifier.
#   $2 - The updater's data directory.
#
# Side effects:
#   Extracts the updater into a staging directory beside the version directory and renames it into place. An existing
#   version directory is renamed aside first and deleted only once the new one is in place; it is renamed back when
#   that fails. A failed download or verification changes nothing.
cat_install_updater()
{
	cat_updater_name="cat-updater-${CAT_RELEASE_TAG}-$1"
	cat_updater_archive="$(cat_download_verified "${cat_updater_name}.tar.gz")"
	cat_data_directory="$2"
	cat_live="${cat_data_directory}/${CAT_VERSION}"
	mkdir -p "${cat_data_directory}"
	CAT_STAGING_DIRECTORY="$(mktemp -d "${cat_data_directory}/.cat-staging-XXXXXX")"
	cat_extract "${cat_updater_archive}" "${CAT_STAGING_DIRECTORY}" "${cat_updater_name}"
	if [ -e "${cat_live}" ] || [ -L "${cat_live}" ]; then
		cat_replaced="${CAT_STAGING_DIRECTORY}/replaced"
		mkdir "${cat_replaced}"
		mv -- "${cat_live}" "${cat_replaced}/"
		if ! mv -- "${CAT_STAGING_DIRECTORY}/${cat_updater_name}" "${cat_live}"; then
			mv -- "${cat_replaced}/${CAT_VERSION}" "${cat_live}"
			echo "CAT could not replace its updater at ${cat_live}, so it kept the updater that was there." >&2
			exit 1
		fi
	else
		mv -- "${CAT_STAGING_DIRECTORY}/${cat_updater_name}" "${cat_live}"
	fi
	rm -rf -- "${CAT_STAGING_DIRECTORY}"
	CAT_STAGING_DIRECTORY=""
}

# Runs the installed updater's setup for a harness, which prints its result.
#
# Arguments:
#   $1 - Platform identifier.
#   $2 - The updater's data directory.
#   $3 - Harness identifier.
#   $4 - The harness's home directory.
#
# Exit status:
#   Returns the exit status of setup; exits nonzero when this release's updater has no launcher for the platform.
cat_set_up_updater()
{
	cat_bin="$2/${CAT_VERSION}/bin"
	cat_launcher="${cat_bin}/cat-updater"
	# Git Bash runs the batch launcher that the Windows updater provides
	if [ "$1" = windows-x64 ] && [ -f "${cat_launcher}.cmd" ]; then
		cat_launcher="${cat_launcher}.cmd"
	fi
	[ -x "${cat_launcher}" ] || {
		echo "CAT's updater ${CAT_RELEASE_TAG} has no launcher for $1 at ${cat_launcher}." >&2
		exit 1
	}
	"${cat_launcher}" setup --harness "$3" --home "$(cat_native_path "$1" "$4")"
}

CAT_HARNESS=""
CAT_MODE=install
CAT_DELETE_DATA=false
while [ "$#" -gt 0 ]; do
	case "$1" in
		--harness)
			[ "$#" -ge 2 ] || cat_print_usage
			CAT_HARNESS="$2"
			shift
			;;
		--updater-only)
			[ "${CAT_MODE}" = install ] || cat_print_usage
			CAT_MODE=updater-only
			;;
		--uninstall)
			[ "${CAT_MODE}" = install ] || cat_print_usage
			CAT_MODE=uninstall
			;;
		--delete-data) CAT_DELETE_DATA=true ;;
		*) cat_print_usage ;;
	esac
	shift
done
[ "${CAT_DELETE_DATA}" = false ] || [ "${CAT_MODE}" = uninstall ] || cat_print_usage

case "${CAT_HARNESS}" in
	claude|codex) ;;
	'')
		# Claude Code sets CLAUDECODE=1 in the shells that run an agent's commands. Codex sets CODEX_THREAD_ID in them:
		# see open_session_with_sandbox in codex-rs/core/src/unified_exec/process_manager.rs, which inserts it into
		# every command's environment, and codex-rs/core/src/tasks/user_shell.rs for the commands that a user runs.
		if [ "${CLAUDECODE:-}" = 1 ] && [ -z "${CODEX_THREAD_ID:-}" ]; then
			CAT_HARNESS=claude
		elif [ -n "${CODEX_THREAD_ID:-}" ] && [ "${CLAUDECODE:-}" != 1 ]; then
			CAT_HARNESS=codex
		else
			echo "CAT cannot tell whether this shell belongs to Claude Code or Codex, so it changed nothing. Run the" \
				"command again with --harness claude or --harness codex, such as:" >&2
			echo >&2
			echo "  curl -fsSL ${CAT_ENTRY_URL} | sh -s -- --harness claude" >&2
			exit 2
		fi
		;;
	*)
		echo "Unknown harness: ${CAT_HARNESS}. Use --harness claude or --harness codex." >&2
		exit 2
		;;
esac

# The installer and the uninstaller run from the temporary extracted plugin, so when they fail they print this
# release's entry command instead of their own path
CAT_BOOTSTRAPPED=1
export CAT_BOOTSTRAPPED
# The installers read the same variables to find the harness's home
if [ "${CAT_HARNESS}" = claude ]; then
	CAT_HARNESS_HOME="${CLAUDE_CONFIG_DIR:-${HOME}/.claude}"
else
	CAT_HARNESS_HOME="${CODEX_HOME:-${HOME}/.codex}"
fi
CAT_PLATFORM="$(cat_detect_platform)"
trap cat_cleanup EXIT
trap 'exit 1' HUP INT TERM
CAT_TEMP_ROOT="${TMPDIR:-/tmp}"
mkdir -p "${CAT_TEMP_ROOT}"
CAT_WORK_DIRECTORY="$(mktemp -d "${CAT_TEMP_ROOT}/cat.XXXXXX")"

case "${CAT_MODE}" in
	uninstall)
		CAT_PLUGIN="$(cat_extract_plugin "${CAT_HARNESS}" "${CAT_PLATFORM}")"
		set --
		[ "${CAT_DELETE_DATA}" = false ] || set -- --delete-data
		sh "${CAT_PLUGIN}/install/uninstall-${CAT_HARNESS}.sh" "$@"
		;;
	*)
		if [ "${CAT_MODE}" = install ]; then
			CAT_PLUGIN="$(cat_extract_plugin "${CAT_HARNESS}" "${CAT_PLATFORM}")"
			sh "${CAT_PLUGIN}/install/install-${CAT_HARNESS}.sh" "${CAT_PLUGIN}"
		fi
		CAT_UPDATER_DATA_DIRECTORY="$(cat_updater_data_directory "${CAT_PLATFORM}")"
		cat_install_updater "${CAT_PLATFORM}" "${CAT_UPDATER_DATA_DIRECTORY}"
		cat_set_up_updater "${CAT_PLATFORM}" "${CAT_UPDATER_DATA_DIRECTORY}" "${CAT_HARNESS}" "${CAT_HARNESS_HOME}"
		;;
esac
