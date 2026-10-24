#!/usr/bin/env sh
set -eu

CAT_HARNESS="claude"
SCRIPT_DIRECTORY="$(CDPATH='' cd -- "$(dirname -- "$0")" && pwd)"
if [ -f "${SCRIPT_DIRECTORY}/bootstrap.sh" ]; then
	"${SCRIPT_DIRECTORY}/bootstrap.sh" "${CAT_HARNESS}" "$@"
else
	CAT_BOOTSTRAP_SCRIPT="$(curl -fsSL \
		"https://raw.githubusercontent.com/holdmyspot-com/cat/HEAD/client/common/lifecycle/src/main/resources/install/bootstrap.sh")"
	printf '%s\n' "${CAT_BOOTSTRAP_SCRIPT}" | sh -s "${CAT_HARNESS}" "$@"
fi
