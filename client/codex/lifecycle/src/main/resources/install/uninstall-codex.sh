#!/usr/bin/env sh
set -eu

SCRIPT_DIRECTORY="$(CDPATH='' cd -- "$(dirname -- "$0")" && pwd)"
COMMON_SCRIPT="${SCRIPT_DIRECTORY}/common.sh"
[ -f "${COMMON_SCRIPT}" ] || {
	echo "CAT installer library not found: ${COMMON_SCRIPT}" >&2
	exit 1
}
# shellcheck source=common.sh
. "${COMMON_SCRIPT}"
trap cat_finish EXIT HUP INT TERM

RERUN_COMMAND="$(cat_rerun_command codex \
	"$(cat_environment_assignments "CODEX_HOME CAT_CODEX_DATA_DIR CAT_CODEX_MARKETPLACE_ROOT")" HEAD \
	"${SCRIPT_DIRECTORY}/uninstall-codex.sh" --uninstall "$@")"
CODEX_HOME="${CODEX_HOME:-${HOME}/.codex}"
CAT_DATA_DIR="${CAT_CODEX_DATA_DIR:-${CODEX_HOME}/plugins/data/cat-cat}"
MARKETPLACE_ROOT="${CAT_CODEX_MARKETPLACE_ROOT:-${CODEX_HOME}/plugins/cat-marketplace}"

cat_validate_marketplace_root "${MARKETPLACE_ROOT}" "CAT_CODEX_MARKETPLACE_ROOT"

# CAT's data is kept by default. Keep it when you plan to reinstall. --delete-data to delete it if you don't.
DELETE_DATA=false
case "$#:${1:-}" in
	0:) ;;
	1:--delete-data) DELETE_DATA=true ;;
	*)
		echo "Usage: $0 [--delete-data]" >&2
		exit 2
		;;
esac
[ "${DELETE_DATA}" = false ] || cat_validate_codex_data_directory "${CAT_DATA_DIR}" "CAT_CODEX_DATA_DIR"
# "codex plugin remove" removes the plugin from config.toml and from the plugin cache
cat_require_writable "${RERUN_COMMAND}" "${CODEX_HOME}/config.toml" "${CODEX_HOME}/plugins/cache"
[ ! -f "${CAT_DATA_DIR}/codex-agent-files" ] ||
	cat_require_writable "${RERUN_COMMAND}" "${CODEX_HOME}/agents" "${CAT_DATA_DIR}"
cat_require_removable "${RERUN_COMMAND}" "${MARKETPLACE_ROOT}" "${HOME}/.cat/bundles"
[ "${DELETE_DATA}" = false ] || cat_require_removable "${RERUN_COMMAND}" "${CAT_DATA_DIR}"

cat_begin_harness_changes removal "${RERUN_COMMAND}"
cat_remove_codex_agents "${CODEX_HOME}" "${CAT_DATA_DIR}"
codex plugin remove cat@cat
codex plugin marketplace remove cat >/dev/null 2>&1 || true
if ! rm -rf "${MARKETPLACE_ROOT}" 2>/dev/null; then
	if [ -e "${MARKETPLACE_ROOT}/plugins" ] ||
		[ -e "${MARKETPLACE_ROOT}/.agents/plugins/marketplace.json" ]; then
		echo "Unable to remove CAT marketplace files: ${MARKETPLACE_ROOT}" >&2
		exit 1
	fi
fi
cat_remove_downloaded_bundles "${HOME}"
if [ "${DELETE_DATA}" = true ] && ! rm -rf "${CAT_DATA_DIR}" 2>/dev/null; then
	if [ -e "${CAT_DATA_DIR}/migration-version" ] ||
		[ -e "${CAT_DATA_DIR}/codex-agent-files" ]; then
		echo "Unable to remove CAT data files: ${CAT_DATA_DIR}" >&2
		exit 1
	fi
fi
