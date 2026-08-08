#!/usr/bin/env sh
set -eu

SCRIPT_DIRECTORY="$(CDPATH='' cd -- "$(dirname -- "$0")" && pwd)"
COMMON_SCRIPT="${SCRIPT_DIRECTORY}/common.sh"
MARKETPLACE_METADATA="${SCRIPT_DIRECTORY}/claude-marketplace.json"
[ -f "${COMMON_SCRIPT}" ] || {
	echo "CAT installer library not found: ${COMMON_SCRIPT}" >&2
	exit 1
}
# shellcheck source=common.sh
. "${COMMON_SCRIPT}"
trap cat_delete_download_temp_directory EXIT HUP INT TERM

[ "$#" -le 1 ] || cat_print_install_usage "$0"
cat_resolve_artifact_directory "claude" "${1:-${CAT_RELEASE_TAG}}"
cat_validate_artifact "${CAT_ARTIFACT_DIRECTORY}" ".claude-plugin/plugin.json" "Claude"

CLAUDE_CONFIG_DIR="${CLAUDE_CONFIG_DIR:-${HOME}/.claude}"
CAT_DATA_DIR="${CAT_CLAUDE_DATA_DIR:-${CLAUDE_PLUGIN_DATA:-${CLAUDE_CONFIG_DIR}/plugins/data/cat-cat}}"
MARKETPLACE_ROOT="${CAT_CLAUDE_MARKETPLACE_ROOT:-${CLAUDE_CONFIG_DIR}/plugins/cat-marketplace}"
cat_validate_marketplace_root "${MARKETPLACE_ROOT}" "CAT_CLAUDE_MARKETPLACE_ROOT"
cat_migrate_data "${CAT_DATA_DIR}" "${CAT_ARTIFACT_DIRECTORY}/install/migrations"
claude plugin uninstall cat@cat >/dev/null 2>&1 || true
claude plugin marketplace remove cat >/dev/null 2>&1 || true
rm -rf "${MARKETPLACE_ROOT}"
mkdir -p "${MARKETPLACE_ROOT}/plugins/cat" "${MARKETPLACE_ROOT}/.claude-plugin"
cp -R "${CAT_ARTIFACT_DIRECTORY}/." "${MARKETPLACE_ROOT}/plugins/cat/"
cp "${MARKETPLACE_METADATA}" "${MARKETPLACE_ROOT}/.claude-plugin/marketplace.json"

claude plugin marketplace add "${MARKETPLACE_ROOT}"
claude plugin install cat@cat
"${MARKETPLACE_ROOT}/plugins/cat/client/bin/plugin-info"
