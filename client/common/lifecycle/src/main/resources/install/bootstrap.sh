#!/usr/bin/env sh
set -eu

CAT_HARNESS="${1:?Usage: bootstrap.sh HARNESS [VERSION]}"
shift
[ "$#" -le 1 ] || {
	echo "Usage: $0 HARNESS [VERSION]" >&2
	exit 2
}
CAT_REPOSITORY="${CAT_BOOTSTRAP_REPOSITORY:-holdmyspot-com/cat}"
CAT_DOWNLOAD_TEMP_ROOT="${TMPDIR:-/tmp}"
mkdir -p "${CAT_DOWNLOAD_TEMP_ROOT}"
CAT_VERSION="${1:-}"
case "${CAT_VERSION}" in
	'')
		CAT_VERSION="$(curl -fsSL "https://api.github.com/repos/${CAT_REPOSITORY}/releases/latest" | \
			sed -n 's/^[[:space:]]*"tag_name"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' | head -n 1)"
		[ -n "${CAT_VERSION}" ] || {
			echo "Unable to determine CAT's latest release version." >&2
			exit 1
		}
		;;
	v[0-9]*.[0-9]*|v[0-9]*.[0-9]*.[0-9]*) ;;
	*) echo "CAT version must be a release tag such as v1.0: ${CAT_VERSION}" >&2; exit 2 ;;
esac
CAT_DOWNLOAD_TEMP_DIRECTORY="$(mktemp -d "${CAT_DOWNLOAD_TEMP_ROOT}/cat-${CAT_HARNESS}-bootstrap.XXXXXX")"
trap 'rm -rf "${CAT_DOWNLOAD_TEMP_DIRECTORY}"' EXIT HUP INT TERM
CAT_COMMON_SOURCE_URL="https://raw.githubusercontent.com/${CAT_REPOSITORY}/${CAT_VERSION}/client/common/lifecycle/"\
"src/main/resources/install"
CAT_HARNESS_SOURCE_URL="https://raw.githubusercontent.com/${CAT_REPOSITORY}/${CAT_VERSION}/client/${CAT_HARNESS}/lifecycle/"\
"src/main/resources/install"
curl -fsSL "${CAT_COMMON_SOURCE_URL}/common.sh" -o "${CAT_DOWNLOAD_TEMP_DIRECTORY}/common.sh"
curl -fsSL "${CAT_HARNESS_SOURCE_URL}/install-${CAT_HARNESS}.sh" -o "${CAT_DOWNLOAD_TEMP_DIRECTORY}/install-${CAT_HARNESS}.sh"
sh "${CAT_DOWNLOAD_TEMP_DIRECTORY}/install-${CAT_HARNESS}.sh" "${CAT_VERSION}"
