#!/usr/bin/env sh
set -eu

CAT_HARNESS="${1:?Usage: bootstrap-uninstall.sh HARNESS}"
shift
[ "$#" -eq 0 ] || {
	echo "Usage: $0 HARNESS" >&2
	exit 2
}
CAT_REPOSITORY="${CAT_BOOTSTRAP_REPOSITORY:-https://github.com/catsforbots/cat.git}"
CAT_GIT="${CAT_BOOTSTRAP_GIT:-git}"
CAT_LOCAL_RELEASE_TREE="${CAT_BOOTSTRAP_RELEASE_TREE:-}"
CAT_VERSION="${CAT_BOOTSTRAP_VERSION:-1.0}"
CAT_DOWNLOAD_TEMP_ROOT="${TMPDIR:-/tmp}"
mkdir -p "${CAT_DOWNLOAD_TEMP_ROOT}"
CAT_DOWNLOAD_TEMP_DIRECTORY="$(mktemp -d "${CAT_DOWNLOAD_TEMP_ROOT}/cat-${CAT_HARNESS}-uninstall.XXXXXX")"
trap 'rm -rf "${CAT_DOWNLOAD_TEMP_DIRECTORY}"' EXIT HUP INT TERM
command -v "${CAT_GIT}" >/dev/null 2>&1 || {
	echo "CAT requires Git to uninstall a release tag: ${CAT_GIT}" >&2
	exit 1
}
if [ -n "${CAT_LOCAL_RELEASE_TREE}" ]; then
	CAT_REPOSITORY_DIRECTORY="$(cd "${CAT_LOCAL_RELEASE_TREE}" && pwd)"
else
	CAT_REPOSITORY_DIRECTORY="${CAT_DOWNLOAD_TEMP_DIRECTORY}/repository"
	"${CAT_GIT}" clone --depth 1 --branch "${CAT_VERSION}" "${CAT_REPOSITORY}" "${CAT_REPOSITORY_DIRECTORY}"
fi
CAT_CHECKED_OUT_TAG="$("${CAT_GIT}" -C "${CAT_REPOSITORY_DIRECTORY}" describe --exact-match --tags HEAD 2>/dev/null)" || {
	echo "CAT release tree is not checked out at an immutable version tag: ${CAT_VERSION}" >&2
	exit 1
}
[ "${CAT_CHECKED_OUT_TAG}" = "${CAT_VERSION}" ] || {
	echo "CAT release tree tag must be ${CAT_VERSION}, found ${CAT_CHECKED_OUT_TAG}" >&2
	exit 1
}
case "$(uname -s):$(uname -m)" in
	Linux:x86_64|Linux:amd64) CAT_PLATFORM="linux-x86_64" ;;
	Linux:aarch64|Linux:arm64) CAT_PLATFORM="linux-arm64" ;;
	Darwin:x86_64|Darwin:amd64) CAT_PLATFORM="macos-x86_64" ;;
	Darwin:aarch64|Darwin:arm64) CAT_PLATFORM="macos-arm64" ;;
	*) echo "Unsupported platform: $(uname -s) $(uname -m)" >&2; exit 1 ;;
esac
CAT_UNINSTALLER="${CAT_REPOSITORY_DIRECTORY}/${CAT_HARNESS}/${CAT_PLATFORM}/install/uninstall-${CAT_HARNESS}.sh"
[ -f "${CAT_UNINSTALLER}" ] || {
	echo "CAT release tag does not support ${CAT_HARNESS}/${CAT_PLATFORM}: ${CAT_VERSION}" >&2
	exit 1
}
sh "${CAT_UNINSTALLER}"
