#!/usr/bin/env sh
set -eu

CAT_HARNESS="codex"
SCRIPT_DIRECTORY="$(CDPATH='' cd -- "$(dirname -- "$0")" && pwd)"
if [ -f "${SCRIPT_DIRECTORY}/bootstrap-uninstall.sh" ]; then
	sh "${SCRIPT_DIRECTORY}/bootstrap-uninstall.sh" "${CAT_HARNESS}" "$@"
else
	curl -fsSL \
		"https://raw.githubusercontent.com/holdmyspot-com/cat/HEAD/client/common/lifecycle/src/main/resources/install/"\
		"bootstrap-uninstall.sh" |
		sh -s "${CAT_HARNESS}" "$@"
fi
