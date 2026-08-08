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

CODEX_HOME="${CODEX_HOME:-${HOME}/.codex}"
CAT_DATA_DIR="${CAT_CODEX_DATA_DIR:-${CODEX_HOME}/plugins/data/cat-cat}"
MARKETPLACE_ROOT="${CAT_CODEX_MARKETPLACE_ROOT:-${CODEX_HOME}/plugins/cat-marketplace}"

cat_validate_marketplace_root "${MARKETPLACE_ROOT}" "CAT_CODEX_MARKETPLACE_ROOT"
cat_validate_codex_data_directory "${CAT_DATA_DIR}" "CAT_CODEX_DATA_DIR"

[ "$#" -eq 0 ] || {
	echo "Usage: $0" >&2
	exit 2
}

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
if ! rm -rf "${CAT_DATA_DIR}" 2>/dev/null; then
	if [ -e "${CAT_DATA_DIR}/migration-version" ] ||
		[ -e "${CAT_DATA_DIR}/codex-agent-files" ]; then
		echo "Unable to remove CAT data files: ${CAT_DATA_DIR}" >&2
		exit 1
	fi
fi
