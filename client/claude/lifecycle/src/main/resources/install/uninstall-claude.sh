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

if [ -n "${CLAUDE_CONFIG_DIR:-}" ]; then
	CLAUDE_GLOBAL_CONFIG_FILE="${CLAUDE_CONFIG_DIR}/.claude.json"
else
	CLAUDE_GLOBAL_CONFIG_FILE="${HOME}/.claude.json"
fi
CLAUDE_CONFIG_DIR="${CLAUDE_CONFIG_DIR:-${HOME}/.claude}"
MARKETPLACE_ROOT="${CAT_CLAUDE_MARKETPLACE_ROOT:-${CLAUDE_CONFIG_DIR}/plugins/cat-marketplace}"

cat_validate_marketplace_root "${MARKETPLACE_ROOT}" "CAT_CLAUDE_MARKETPLACE_ROOT"
# "claude plugin uninstall" and "claude plugin marketplace remove" write the lock of .claude.json in the directory that
# holds .claude.json, which is CLAUDE_CONFIG_DIR when it is set and HOME otherwise. In the configuration directory they
# write .storage-write.lock, settings.json, and known_marketplaces.json and installed_plugins.json in plugins, each by
# renaming a temporary file over it, and they mark each cached version of the plugin in plugins/cache/cat/cat as
# orphaned by writing a file into it. These files were observed by tracing Claude Code 2.1.312, so a later version of
# Claude Code may need them checked again.
set -- "${CLAUDE_GLOBAL_CONFIG_FILE}" "${CLAUDE_CONFIG_DIR}" "${CLAUDE_CONFIG_DIR}/plugins" \
	"${CLAUDE_CONFIG_DIR}/settings.json" "${CLAUDE_CONFIG_DIR}/plugins/known_marketplaces.json" \
	"${CLAUDE_CONFIG_DIR}/plugins/installed_plugins.json" "${CLAUDE_CONFIG_DIR}/plugins/cache" \
	"${CLAUDE_CONFIG_DIR}/plugins/cache/cat/cat"
for CACHED_VERSION in "${CLAUDE_CONFIG_DIR}/plugins/cache/cat/cat"/*; do
	[ ! -d "${CACHED_VERSION}" ] || set -- "$@" "${CACHED_VERSION}"
done
cat_require_writable "${RERUN_COMMAND}" "$@"
cat_require_removable "${RERUN_COMMAND}" "${MARKETPLACE_ROOT}" "${HOME}/.cat/bundles"

cat_begin_harness_changes removal "${RERUN_COMMAND}"
claude plugin uninstall cat@cat
claude plugin marketplace remove cat >/dev/null 2>&1 || true
rm -rf "${MARKETPLACE_ROOT}"
cat_remove_downloaded_bundles "${HOME}"
