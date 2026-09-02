#!/usr/bin/env sh
set -eu

CAT_HARNESS="codex"
SCRIPT_DIRECTORY="$(CDPATH='' cd -- "$(dirname -- "$0")" && pwd)"
if [ -f "${SCRIPT_DIRECTORY}/bootstrap.sh" ]; then
	"${SCRIPT_DIRECTORY}/bootstrap.sh" "${CAT_HARNESS}" "$@"
else
	echo "CAT bootstrap files are incomplete: ${SCRIPT_DIRECTORY}/bootstrap.sh" >&2
	exit 1
fi
