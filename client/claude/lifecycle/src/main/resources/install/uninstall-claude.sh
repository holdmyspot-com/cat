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

[ "$#" -eq 0 ] || {
	echo "Usage: $0" >&2
	exit 2
}
RERUN_COMMAND="$(cat_rerun_command claude "$(cat_environment_assignments "CLAUDE_CONFIG_DIR CAT_CLAUDE_MARKETPLACE_ROOT")" \
	HEAD "${SCRIPT_DIRECTORY}/uninstall-claude.sh" --uninstall)"

CLAUDE_CONFIG_DIR="${CLAUDE_CONFIG_DIR:-${HOME}/.claude}"
MARKETPLACE_ROOT="${CAT_CLAUDE_MARKETPLACE_ROOT:-${CLAUDE_CONFIG_DIR}/plugins/cat-marketplace}"

cat_validate_marketplace_root "${MARKETPLACE_ROOT}" "CAT_CLAUDE_MARKETPLACE_ROOT"
# "claude plugin uninstall" disables the plugin in the user's settings.json, removes it from installed_plugins.json and
# from the plugin cache; "claude plugin marketplace remove" removes the marketplace from known_marketplaces.json
cat_require_writable "${RERUN_COMMAND}" "${CLAUDE_CONFIG_DIR}/settings.json" \
	"${CLAUDE_CONFIG_DIR}/plugins/known_marketplaces.json" "${CLAUDE_CONFIG_DIR}/plugins/installed_plugins.json" \
	"${CLAUDE_CONFIG_DIR}/plugins/cache"
cat_require_removable "${RERUN_COMMAND}" "${MARKETPLACE_ROOT}" "${HOME}/.cat/bundles"

cat_begin_harness_changes removal "${RERUN_COMMAND}"
claude plugin uninstall cat@cat
claude plugin marketplace remove cat >/dev/null 2>&1 || true
rm -rf "${MARKETPLACE_ROOT}"
cat_remove_downloaded_bundles "${HOME}"
