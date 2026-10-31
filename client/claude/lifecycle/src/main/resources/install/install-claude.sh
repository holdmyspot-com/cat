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

[ "$#" -le 1 ] || cat_print_install_usage "$0"
CAT_SOURCE="${1:-${CAT_RELEASE_TAG}}"
[ ! -d "${CAT_SOURCE}" ] || CAT_SOURCE="$(CDPATH='' cd -- "${CAT_SOURCE}" && pwd)"
RERUN_COMMAND="$(cat_rerun_command claude \
	"$(cat_environment_assignments "CLAUDE_CONFIG_DIR CAT_CLAUDE_DATA_DIR CLAUDE_PLUGIN_DATA CAT_CLAUDE_MARKETPLACE_ROOT")" \
	"${CAT_SOURCE}" "${SCRIPT_DIRECTORY}/install-claude.sh" "" "${CAT_SOURCE}")"

CLAUDE_CONFIG_DIR="${CLAUDE_CONFIG_DIR:-${HOME}/.claude}"
CAT_DATA_DIR="${CAT_CLAUDE_DATA_DIR:-${CLAUDE_PLUGIN_DATA:-${CLAUDE_CONFIG_DIR}/plugins/data/cat-cat}}"
MARKETPLACE_ROOT="${CAT_CLAUDE_MARKETPLACE_ROOT:-${CLAUDE_CONFIG_DIR}/plugins/cat-marketplace}"
cat_validate_marketplace_root "${MARKETPLACE_ROOT}" "CAT_CLAUDE_MARKETPLACE_ROOT"
# "claude plugin install" enables the plugin in the user's settings.json, records it in installed_plugins.json and
# copies it into the plugin cache; "claude plugin marketplace add" records the marketplace in known_marketplaces.json
cat_require_writable "${RERUN_COMMAND}" "${CLAUDE_CONFIG_DIR}/settings.json" \
	"${CLAUDE_CONFIG_DIR}/plugins/known_marketplaces.json" "${CLAUDE_CONFIG_DIR}/plugins/installed_plugins.json" \
	"${CLAUDE_CONFIG_DIR}/plugins/cache" "${CAT_DATA_DIR}" "$(dirname -- "${MARKETPLACE_ROOT}")"
cat_require_removable "${RERUN_COMMAND}" "${MARKETPLACE_ROOT}"

cat_resolve_artifact_directory "claude" "${CAT_SOURCE}"
cat_validate_artifact "${CAT_ARTIFACT_DIRECTORY}" ".claude-plugin/plugin.json" "Claude"
MARKETPLACE_METADATA="${CAT_ARTIFACT_DIRECTORY}/install/claude-marketplace.json"
cat_migrate_data "${CAT_DATA_DIR}" "${CAT_ARTIFACT_DIRECTORY}/install/migrations"
cat_begin_harness_changes installation "${RERUN_COMMAND}"
claude plugin uninstall cat@cat >/dev/null 2>&1 || true
claude plugin marketplace remove cat >/dev/null 2>&1 || true
rm -rf "${MARKETPLACE_ROOT}"
mkdir -p "${MARKETPLACE_ROOT}/plugins/cat" "${MARKETPLACE_ROOT}/.claude-plugin"
cp -R "${CAT_ARTIFACT_DIRECTORY}/." "${MARKETPLACE_ROOT}/plugins/cat/"
cp "${MARKETPLACE_METADATA}" "${MARKETPLACE_ROOT}/.claude-plugin/marketplace.json"

claude plugin marketplace add "${MARKETPLACE_ROOT}"
claude plugin install cat@cat
"${MARKETPLACE_ROOT}/plugins/cat/bin/plugin-info"
