#!/usr/bin/env sh
set -eu

CAT_HARNESS="${1:?Usage: bootstrap-uninstall.sh HARNESS [UNINSTALLER-OPTION]...}"
shift
CAT_REPOSITORY="${CAT_BOOTSTRAP_REPOSITORY:-holdmyspot-com/cat}"
CAT_DOWNLOAD_TEMP_ROOT="${TMPDIR:-/tmp}"
mkdir -p "${CAT_DOWNLOAD_TEMP_ROOT}"
CAT_DOWNLOAD_TEMP_DIRECTORY="$(mktemp -d "${CAT_DOWNLOAD_TEMP_ROOT}/cat-${CAT_HARNESS}-uninstall.XXXXXX")"
trap 'rm -rf "${CAT_DOWNLOAD_TEMP_DIRECTORY}"' EXIT HUP INT TERM
CAT_COMMON_SOURCE_URL="https://raw.githubusercontent.com/${CAT_REPOSITORY}/HEAD/client/common/lifecycle/src/main/resources/install"
CAT_HARNESS_SOURCE_URL="https://raw.githubusercontent.com/${CAT_REPOSITORY}/HEAD/client/${CAT_HARNESS}/lifecycle/src/main/resources/install"
curl -fsSL "${CAT_COMMON_SOURCE_URL}/common.sh" -o "${CAT_DOWNLOAD_TEMP_DIRECTORY}/common.sh"
curl -fsSL "${CAT_HARNESS_SOURCE_URL}/uninstall-${CAT_HARNESS}.sh" -o "${CAT_DOWNLOAD_TEMP_DIRECTORY}/uninstall-${CAT_HARNESS}.sh"
sh "${CAT_DOWNLOAD_TEMP_DIRECTORY}/uninstall-${CAT_HARNESS}.sh" "$@"
