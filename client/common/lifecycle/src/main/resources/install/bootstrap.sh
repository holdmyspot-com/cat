#!/usr/bin/env sh
set -eu

CAT_HARNESS="${1:?Usage: bootstrap.sh HARNESS [VERSION]}"
shift
[ "$#" -le 1 ] || {
	echo "Usage: $0 HARNESS [VERSION]" >&2
	exit 2
}
CAT_REPOSITORY="${CAT_BOOTSTRAP_REPOSITORY:-https://github.com/catsforbots/cat.git}"
CAT_GIT="${CAT_BOOTSTRAP_GIT:-git}"
CAT_LOCAL_RELEASE_TREE="${CAT_BOOTSTRAP_RELEASE_TREE:-}"
CAT_DOWNLOAD_TEMP_ROOT="${TMPDIR:-/tmp}"
mkdir -p "${CAT_DOWNLOAD_TEMP_ROOT}"
CAT_VERSION="${1:-1.0}"
case "${CAT_VERSION}" in
	[0-9]*.[0-9]*|[0-9]*.[0-9]*.[0-9]*) ;;
	*) echo "CAT version must be an immutable numeric release tag such as 1.0: ${CAT_VERSION}" >&2; exit 2 ;;
esac
CAT_DOWNLOAD_TEMP_DIRECTORY="$(mktemp -d "${CAT_DOWNLOAD_TEMP_ROOT}/cat-${CAT_HARNESS}-bootstrap.XXXXXX")"
trap 'rm -rf "${CAT_DOWNLOAD_TEMP_DIRECTORY}"' EXIT HUP INT TERM
command -v "${CAT_GIT}" >/dev/null 2>&1 || {
	echo "CAT requires Git to install a release tag: ${CAT_GIT}" >&2
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
CAT_ARTIFACT_DIRECTORY="${CAT_REPOSITORY_DIRECTORY}/${CAT_HARNESS}/${CAT_PLATFORM}"
[ -f "${CAT_REPOSITORY_DIRECTORY}/release-manifest.json" ] || {
	echo "CAT release tag does not contain release-manifest.json: ${CAT_VERSION}" >&2
	exit 1
}
[ -d "${CAT_ARTIFACT_DIRECTORY}" ] || {
	echo "CAT release tag does not support ${CAT_HARNESS}/${CAT_PLATFORM}" >&2
	exit 1
}
CAT_MANIFEST_ENTRIES="${CAT_DOWNLOAD_TEMP_DIRECTORY}/manifest-entries.tsv"
awk '
	/"path"[[:space:]]*:/ {
		path = $0
		sub(/^.*"path"[[:space:]]*:[[:space:]]*"/, "", path)
		sub(/".*$/, "", path)
	}
	/"sha256"[[:space:]]*:/ {
		hash = $0
		sub(/^.*"sha256"[[:space:]]*:[[:space:]]*"/, "", hash)
		sub(/".*$/, "", hash)
	}
	/"size"[[:space:]]*:/ {
		size = $0
		sub(/^.*"size"[[:space:]]*:[[:space:]]*/, "", size)
		sub(/[^0-9].*$/, "", size)
		if (path != "" && hash != "" && size != "")
			print path "\t" hash "\t" size
		path = ""
		hash = ""
		size = ""
	}
' "${CAT_REPOSITORY_DIRECTORY}/release-manifest.json" > "${CAT_MANIFEST_ENTRIES}"
[ -s "${CAT_MANIFEST_ENTRIES}" ] || {
	echo "CAT release manifest contains no file entries: ${CAT_VERSION}" >&2
	exit 1
}
CAT_MANIFEST_PATHS="${CAT_DOWNLOAD_TEMP_DIRECTORY}/manifest-paths.txt"
cut -f 1 "${CAT_MANIFEST_ENTRIES}" > "${CAT_MANIFEST_PATHS}"
while IFS='	' read -r CAT_MANIFEST_PATH CAT_MANIFEST_SHA256 CAT_MANIFEST_SIZE; do
	case "${CAT_MANIFEST_PATH}" in
		LICENSE.md|README.md|codex/*|claude/*) ;;
		*) echo "CAT release manifest contains an unsupported path: ${CAT_MANIFEST_PATH}" >&2; exit 1 ;;
	esac
	case "${CAT_MANIFEST_PATH}" in /*|*'..'*|*'//'*)
		echo "CAT release manifest contains an unsafe target path: ${CAT_MANIFEST_PATH}" >&2
		exit 1
		;;
	esac
	CAT_MANIFEST_FILE="${CAT_REPOSITORY_DIRECTORY}/${CAT_MANIFEST_PATH}"
	if [ ! -f "${CAT_MANIFEST_FILE}" ] || [ -L "${CAT_MANIFEST_FILE}" ]; then
		echo "CAT release manifest file is missing or unsafe: ${CAT_MANIFEST_PATH}" >&2
		exit 1
	fi
	[ "$(wc -c < "${CAT_MANIFEST_FILE}" | tr -d '[:space:]')" = "${CAT_MANIFEST_SIZE}" ] || {
		echo "CAT release manifest size mismatch: ${CAT_MANIFEST_PATH}" >&2
		exit 1
	}
	if command -v sha256sum >/dev/null 2>&1; then
		CAT_ACTUAL_SHA256="$(sha256sum "${CAT_MANIFEST_FILE}" | awk '{ print $1 }')"
	else
		CAT_ACTUAL_SHA256="$(shasum -a 256 "${CAT_MANIFEST_FILE}" | awk '{ print $1 }')"
	fi
	[ "${CAT_ACTUAL_SHA256}" = "${CAT_MANIFEST_SHA256}" ] || {
		echo "CAT release manifest checksum mismatch: ${CAT_MANIFEST_PATH}" >&2
		exit 1
	}
done < "${CAT_MANIFEST_ENTRIES}"
find "${CAT_REPOSITORY_DIRECTORY}" -type l -print -quit | grep -q . && {
	echo "CAT release tree contains a symbolic link" >&2
	exit 1
}
find "${CAT_REPOSITORY_DIRECTORY}" -type f ! -name release-manifest.json -print | while IFS= read -r CAT_ACTUAL_FILE; do
	CAT_ACTUAL_PATH="${CAT_ACTUAL_FILE#"${CAT_REPOSITORY_DIRECTORY}"/}"
	grep -Fqx "${CAT_ACTUAL_PATH}" "${CAT_MANIFEST_PATHS}" || {
		echo "CAT release target contains an unlisted file: ${CAT_ACTUAL_PATH}" >&2
		exit 1
	}
done
sh "${CAT_ARTIFACT_DIRECTORY}/install/install-${CAT_HARNESS}.sh" "${CAT_ARTIFACT_DIRECTORY}"
