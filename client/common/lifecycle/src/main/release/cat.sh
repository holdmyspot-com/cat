#!/usr/bin/env sh
# Installs CAT for Claude Code or Codex: the release's plugin, with its Java runtime and installer, and the agent
# permissions that later updates need, by running the install of the installer in the plugin that this release
# publishes for the harness.
#
#   curl -fsSL https://github.com/holdmyspot-com/cat/releases/download/<tag>/cat.sh | sh -s -- --harness claude
#
# The release is fixed: the script takes no version and downloads only this release's plugin archive for the harness
# and platform, which it checks against the SHA-256 digest that it embeds before it extracts or runs anything from it.
# No argument or environment variable changes where it downloads from. It extracts the archive into a temporary
# directory, runs the plugin's installer from there, prints the installer's result, exits with its exit status and
# deletes the temporary directory. The installer asks the harness to install the plugin.
#
# Inside an agent session the script detects the harness when --harness is absent. To uninstall CAT, ask the agent to
# uninstall it: the agent runs the installed plugin's installer uninstall.
set -eu

# The generator replaces these two values when it publishes the script with a release
CAT_RELEASE_TAG='@CAT_RELEASE_TAG@'
CAT_SHA256SUMS='@CAT_SHA256SUMS@'
CAT_DOWNLOAD_URL="https://github.com/holdmyspot-com/cat/releases/download/${CAT_RELEASE_TAG}"
CAT_ENTRY_URL="${CAT_DOWNLOAD_URL}/cat.sh"
# The temporary directory that holds the downloaded and extracted plugin, deleted at exit
CAT_WORK_DIRECTORY=""

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
	echo "This script installs ${CAT_RELEASE_TAG} and takes no version: to install another release, run that" \
		"release's cat.sh." >&2
	exit 2
}

# Refuses an option that earlier releases accepted, telling the user how to reach what it did, and exits with the
# command-line usage status.
#
# Arguments:
#   $1 - The retired option, such as --uninstall.
#
# Errors:
#   Writes the refusal to standard error.
#
# Exit status:
#   Exits with status 2.
cat_refuse_retired_option()
{
	echo "$1 is no longer supported. Run cat.sh [--harness claude|codex] to install CAT, including its" \
		"installer. To uninstall CAT, ask your agent to uninstall CAT: it runs the installed plugin's" \
		"bin/installer uninstall, and asks you first whether to delete CAT's data." >&2
	exit 2
}

# Deletes the temporary directory that this script created.
#
# Side effects:
#   Deletes CAT_WORK_DIRECTORY when it is set.
cat_cleanup()
{
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
#   $1 - The asset's name, such as cat-claude-linux-x64-v1.0.tar.gz.
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

# Downloads, verifies and extracts this release's plugin for a harness, then runs the install of the plugin's installer
# from the extracted archive. The installer prints its result to standard output.
#
# Arguments:
#   $1 - Platform identifier.
#   $2 - Harness identifier, claude or codex.
#   $3 - The harness's home directory.
#
# Environment:
#   CAT_WORK_DIRECTORY - The temporary directory that receives the archive and its extracted content.
#
# Exit status:
#   Returns the exit status of the installer; exits nonzero when the download, its verification or the extraction
#   fails, or the plugin has no installer launcher at the expected place.
cat_run_install()
{
	cat_plugin_name="cat-$2-$1-${CAT_RELEASE_TAG}"
	cat_plugin_archive="$(cat_download_verified "${cat_plugin_name}.tar.gz")"
	cat_extracted="${CAT_WORK_DIRECTORY}/extracted"
	mkdir "${cat_extracted}"
	cat_extract "${cat_plugin_archive}" "${cat_extracted}" "${cat_plugin_name}"
	# The plugin's launcher runs the Java runtime in the plugin's own bin folder
	cat_launcher="${cat_extracted}/${cat_plugin_name}/bin/installer"
	if [ ! -f "${cat_launcher}" ] || [ ! -x "${cat_launcher}" ]; then
		echo "CAT's $2 plugin ${CAT_RELEASE_TAG} has no installer for $1 at ${cat_launcher}." >&2
		exit 1
	fi
	"${cat_launcher}" install --harness "$2" --home "$(cat_native_path "$1" "$3")"
}

CAT_HARNESS=""
while [ "$#" -gt 0 ]; do
	case "$1" in
		--harness)
			[ "$#" -ge 2 ] || cat_print_usage
			CAT_HARNESS="$2"
			shift
			;;
		--updater-only|--uninstall|--delete-data) cat_refuse_retired_option "$1" ;;
		*) cat_print_usage ;;
	esac
	shift
done

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

# Each harness moves its home with its own variable
if [ "${CAT_HARNESS}" = claude ]; then
	CAT_HARNESS_HOME="${CLAUDE_CONFIG_DIR:-${HOME:?HOME must be set}/.claude}"
else
	CAT_HARNESS_HOME="${CODEX_HOME:-${HOME:?HOME must be set}/.codex}"
fi
CAT_PLATFORM="$(cat_detect_platform)"
trap cat_cleanup EXIT
trap 'exit 1' HUP INT TERM
CAT_TEMP_ROOT="${TMPDIR:-/tmp}"
mkdir -p "${CAT_TEMP_ROOT}"
CAT_WORK_DIRECTORY="$(mktemp -d "${CAT_TEMP_ROOT}/cat.XXXXXX")"
# As the last command, the installer's exit status becomes the script's, after the exit trap deletes the temporary
# directory
cat_run_install "${CAT_PLATFORM}" "${CAT_HARNESS}" "${CAT_HARNESS_HOME}"
