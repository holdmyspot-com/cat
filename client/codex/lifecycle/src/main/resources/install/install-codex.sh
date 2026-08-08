#!/usr/bin/env sh
set -eu

SCRIPT_DIRECTORY="$(CDPATH='' cd -- "$(dirname -- "$0")" && pwd)"
COMMON_SCRIPT="${SCRIPT_DIRECTORY}/common.sh"
MARKETPLACE_METADATA="${SCRIPT_DIRECTORY}/codex-marketplace.json"
[ -f "${COMMON_SCRIPT}" ] || {
	echo "CAT installer library not found: ${COMMON_SCRIPT}" >&2
	exit 1
}
# shellcheck source=common.sh
. "${COMMON_SCRIPT}"
trap cat_delete_download_temp_directory EXIT HUP INT TERM

[ "$#" -le 1 ] || cat_print_install_usage "$0"
cat_resolve_artifact_directory "codex" "${1:-${CAT_RELEASE_TAG}}"
cat_validate_artifact "${CAT_ARTIFACT_DIRECTORY}" ".codex-plugin/plugin.json" "Codex"

CODEX_HOME="${CODEX_HOME:-${HOME}/.codex}"
CAT_DATA_DIR="${CAT_CODEX_DATA_DIR:-${CODEX_HOME}/plugins/data/cat-cat}"
MARKETPLACE_ROOT="${CAT_CODEX_MARKETPLACE_ROOT:-${CODEX_HOME}/plugins/cat-marketplace}"
cat_validate_marketplace_root "${MARKETPLACE_ROOT}" "CAT_CODEX_MARKETPLACE_ROOT"
cat_migrate_data "${CAT_DATA_DIR}" "${CAT_ARTIFACT_DIRECTORY}/install/migrations"
cat_install_codex_agents "${CAT_ARTIFACT_DIRECTORY}" "${CODEX_HOME}" "${CAT_DATA_DIR}"
codex plugin remove cat@cat >/dev/null 2>&1 || true
codex plugin marketplace remove cat >/dev/null 2>&1 || true
rm -rf "${MARKETPLACE_ROOT}"
mkdir -p "${MARKETPLACE_ROOT}/plugins/cat" "${MARKETPLACE_ROOT}/.agents/plugins"
cp -R "${CAT_ARTIFACT_DIRECTORY}/." "${MARKETPLACE_ROOT}/plugins/cat/"
cp "${MARKETPLACE_METADATA}" "${MARKETPLACE_ROOT}/.agents/plugins/marketplace.json"

codex plugin marketplace add "${MARKETPLACE_ROOT}"
codex plugin add cat@cat
"${MARKETPLACE_ROOT}/plugins/cat/client/bin/plugin-info"
