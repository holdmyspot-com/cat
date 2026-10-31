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
# The Codex archive for Windows holds only batch launchers, which Codex runs from PowerShell
case "$(uname -s)" in
	MINGW*|MSYS*)
		echo "On Windows, install CAT for Codex from PowerShell with bootstrap-codex.ps1, as described in" \
			"https://github.com/holdmyspot-com/cat/blob/HEAD/docs/installation.md" >&2
		exit 1
		;;
esac
CAT_SOURCE="${1:-${CAT_RELEASE_TAG}}"
[ ! -d "${CAT_SOURCE}" ] || CAT_SOURCE="$(CDPATH='' cd -- "${CAT_SOURCE}" && pwd)"
RERUN_COMMAND="$(cat_rerun_command codex \
	"$(cat_environment_assignments "CODEX_HOME CAT_CODEX_DATA_DIR CAT_CODEX_MARKETPLACE_ROOT")" \
	"${CAT_SOURCE}" "${SCRIPT_DIRECTORY}/install-codex.sh" "" "${CAT_SOURCE}")"

CODEX_HOME="${CODEX_HOME:-${HOME}/.codex}"
CAT_DATA_DIR="${CAT_CODEX_DATA_DIR:-${CODEX_HOME}/plugins/data/cat-cat}"
MARKETPLACE_ROOT="${CAT_CODEX_MARKETPLACE_ROOT:-${CODEX_HOME}/plugins/cat-marketplace}"
cat_validate_marketplace_root "${MARKETPLACE_ROOT}" "CAT_CODEX_MARKETPLACE_ROOT"
# "codex plugin marketplace add" and "codex plugin add" replace config.toml by renaming a temporary file from
# CODEX_HOME over it, stage the marketplace in .tmp/marketplaces, write a helper in tmp/arg0 and copy the plugin into
# plugins/cache, all within CODEX_HOME; CAT itself writes the agents, its data and its marketplace. The Codex files were
# observed by tracing Codex 0.160.0, so a later version of Codex may need them checked again.
cat_require_writable "${RERUN_COMMAND}" "${CODEX_HOME}" "${CODEX_HOME}/.tmp" "${CODEX_HOME}/tmp" \
	"${CODEX_HOME}/config.toml" "${CODEX_HOME}/plugins/cache" "${CODEX_HOME}/agents" "${CAT_DATA_DIR}" \
	"$(dirname -- "${MARKETPLACE_ROOT}")"
cat_require_removable "${RERUN_COMMAND}" "${MARKETPLACE_ROOT}"

cat_resolve_artifact_directory "codex" "${CAT_SOURCE}"
cat_validate_artifact "${CAT_ARTIFACT_DIRECTORY}" ".codex-plugin/plugin.json" "Codex"
MARKETPLACE_METADATA="${CAT_ARTIFACT_DIRECTORY}/install/codex-marketplace.json"
cat_migrate_data "${CAT_DATA_DIR}" "${CAT_ARTIFACT_DIRECTORY}/install/migrations"
cat_begin_harness_changes installation "${RERUN_COMMAND}"
cat_install_codex_agents "${CAT_ARTIFACT_DIRECTORY}" "${CODEX_HOME}" "${CAT_DATA_DIR}"
codex plugin remove cat@cat >/dev/null 2>&1 || true
codex plugin marketplace remove cat >/dev/null 2>&1 || true
rm -rf "${MARKETPLACE_ROOT}"
mkdir -p "${MARKETPLACE_ROOT}/plugins/cat" "${MARKETPLACE_ROOT}/.agents/plugins"
cp -R "${CAT_ARTIFACT_DIRECTORY}/." "${MARKETPLACE_ROOT}/plugins/cat/"
cp "${MARKETPLACE_METADATA}" "${MARKETPLACE_ROOT}/.agents/plugins/marketplace.json"

codex plugin marketplace add "${MARKETPLACE_ROOT}"
codex plugin add cat@cat
"${MARKETPLACE_ROOT}/plugins/cat/bin/plugin-info"
