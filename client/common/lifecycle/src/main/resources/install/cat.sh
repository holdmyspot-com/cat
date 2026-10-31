#!/usr/bin/env sh
# Installs, updates or uninstalls CAT for the coding harness that runs this script: Claude Code or Codex.
#
#   curl -fsSL <url of this script> | sh
#     Installs or updates to the latest CAT release.
#   curl -fsSL <url of this script> | sh -s -- v1.0
#     Installs or updates to the release v1.0.
#   curl -fsSL <url of this script> | sh -s -- --uninstall [--delete-data]
#     Uninstalls CAT. CAT's Codex data is kept unless --delete-data is given.
#
# Outside an agent session, such as in a terminal, name the harness with --harness claude or --harness codex. Inside
# one, the script detects it. When the script cannot write a path that the operation changes, it changes nothing,
# exits with status 77 and prints the command to run outside the agent's sandbox.
set -eu

# An installed plugin holds this script with the scripts that it runs. An update or removal replaces or deletes that
# plugin directory, which fails on Windows while a script in it runs, so the scripts run from a temporary copy.
if [ -z "${CAT_COPY_DIRECTORY:-}" ] && [ "$(basename -- "$0")" = cat.sh ] && [ -f "$0" ] && \
	[ -f "$(dirname -- "$0")/bootstrap.sh" ]; then
	CAT_TEMP_ROOT="${TMPDIR:-/tmp}"
	mkdir -p "${CAT_TEMP_ROOT}"
	CAT_COPY_DIRECTORY="$(mktemp -d "${CAT_TEMP_ROOT}/cat.XXXXXX")"
	export CAT_COPY_DIRECTORY
	for cat_script in cat.sh common.sh bootstrap.sh bootstrap-uninstall.sh uninstall-claude.sh uninstall-codex.sh; do
		[ ! -f "$(dirname -- "$0")/${cat_script}" ] || cp "$(dirname -- "$0")/${cat_script}" "${CAT_COPY_DIRECTORY}/"
	done
	exec sh "${CAT_COPY_DIRECTORY}/cat.sh" "$@"
fi
if [ -n "${CAT_COPY_DIRECTORY:-}" ]; then
	trap 'rm -rf "${CAT_COPY_DIRECTORY}"' EXIT HUP INT TERM
fi

CAT_REPOSITORY="${CAT_BOOTSTRAP_REPOSITORY:-holdmyspot-com/cat}"
CAT_SCRIPT_URL="https://raw.githubusercontent.com/${CAT_REPOSITORY}/HEAD/client/common/lifecycle/src/main/resources/install"

# Prints this script's usage and exits with the command-line usage status.
#
# Errors:
#   Writes the usage to standard error.
#
# Exit status:
#   Exits with status 2.
cat_print_usage()
{
	echo "Usage: cat.sh [--harness claude|codex] [VERSION]" >&2
	echo "       cat.sh [--harness claude|codex] --uninstall [--delete-data]" >&2
	exit 2
}

CAT_HARNESS=""
CAT_UNINSTALL=false
CAT_DELETE_DATA=false
CAT_REQUESTED_VERSION=""
while [ "$#" -gt 0 ]; do
	case "$1" in
		--harness)
			[ "$#" -ge 2 ] || cat_print_usage
			CAT_HARNESS="$2"
			shift
			;;
		--uninstall) CAT_UNINSTALL=true ;;
		--delete-data) CAT_DELETE_DATA=true ;;
		-*) cat_print_usage ;;
		*)
			[ -z "${CAT_REQUESTED_VERSION}" ] || cat_print_usage
			CAT_REQUESTED_VERSION="$1"
			;;
	esac
	shift
done
if [ "${CAT_UNINSTALL}" = true ]; then
	[ -z "${CAT_REQUESTED_VERSION}" ] || cat_print_usage
elif [ "${CAT_DELETE_DATA}" = true ]; then
	cat_print_usage
fi

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
			echo "  curl -fsSL ${CAT_SCRIPT_URL}/cat.sh | sh -s -- --harness claude" >&2
			exit 2
		fi
		;;
	*)
		echo "Unknown harness: ${CAT_HARNESS}. Use --harness claude or --harness codex." >&2
		exit 2
		;;
esac

# The installer and uninstaller can be temporary copies, or in the plugin directory that they replace, so when they
# fail they print the command that downloads this script instead of their own path
CAT_BOOTSTRAPPED=1
export CAT_BOOTSTRAPPED
CAT_SCRIPT_DIRECTORY=""
if [ "$(basename -- "$0")" = cat.sh ] && [ -f "$0" ]; then
	CAT_SCRIPT_DIRECTORY="$(CDPATH='' cd -- "$(dirname -- "$0")" && pwd)"
fi

if [ "${CAT_UNINSTALL}" = true ]; then
	set -- "${CAT_HARNESS}"
	[ "${CAT_DELETE_DATA}" = false ] || set -- "$@" --delete-data
	# An installed plugin holds its own uninstaller, which needs no download
	if [ -n "${CAT_SCRIPT_DIRECTORY}" ] && [ -f "${CAT_SCRIPT_DIRECTORY}/uninstall-${CAT_HARNESS}.sh" ]; then
		shift
		sh "${CAT_SCRIPT_DIRECTORY}/uninstall-${CAT_HARNESS}.sh" "$@"
	elif [ -n "${CAT_SCRIPT_DIRECTORY}" ] && [ -f "${CAT_SCRIPT_DIRECTORY}/bootstrap-uninstall.sh" ]; then
		sh "${CAT_SCRIPT_DIRECTORY}/bootstrap-uninstall.sh" "$@"
	else
		CAT_BOOTSTRAP_SCRIPT="$(curl -fsSL "${CAT_SCRIPT_URL}/bootstrap-uninstall.sh")"
		printf '%s\n' "${CAT_BOOTSTRAP_SCRIPT}" | sh -s "$@"
	fi
else
	set -- "${CAT_HARNESS}"
	[ -z "${CAT_REQUESTED_VERSION}" ] || set -- "$@" "${CAT_REQUESTED_VERSION}"
	if [ -n "${CAT_SCRIPT_DIRECTORY}" ] && [ -f "${CAT_SCRIPT_DIRECTORY}/bootstrap.sh" ]; then
		sh "${CAT_SCRIPT_DIRECTORY}/bootstrap.sh" "$@"
	else
		CAT_BOOTSTRAP_SCRIPT="$(curl -fsSL "${CAT_SCRIPT_URL}/bootstrap.sh")"
		printf '%s\n' "${CAT_BOOTSTRAP_SCRIPT}" | sh -s "$@"
	fi
fi
