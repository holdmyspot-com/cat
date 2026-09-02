#!/usr/bin/env sh

# Publishes one complete, already verified CAT release tree to its edition repository.

set -eu

usage()
{
	echo "Usage: $0 --edition personal|commercial --staged-tree ABSOLUTE_PATH --repository HTTPS_OR_SSH_GIT_URL --version VERSION --git ABSOLUTE_GIT_EXECUTABLE" >&2
	exit 2
}

edition=""
staged_tree=""
repository=""
version=""
git_executable=""
while [ "$#" -gt 0 ]; do
	case "$1" in
		--edition) edition="${2-}"; shift 2 ;;
		--staged-tree) staged_tree="${2-}"; shift 2 ;;
		--repository) repository="${2-}"; shift 2 ;;
		--version) version="${2-}"; shift 2 ;;
		--git) git_executable="${2-}"; shift 2 ;;
		*) usage ;;
	esac
done

case "${edition}" in personal|commercial) ;; *) usage ;; esac
case "${staged_tree}" in /*) ;; *) usage ;; esac
case "${version}" in [0-9]*.[0-9]*|[0-9]*.[0-9]*.[0-9]*) ;; *) usage ;; esac
[ -d "${staged_tree}" ] || { echo "Staged tree is not a directory: ${staged_tree}" >&2; exit 1; }
[ -x "${git_executable}" ] || { echo "Git executable is not executable: ${git_executable}" >&2; exit 1; }
case "${repository}" in
	https://*|ssh://*|git@*:* ) ;;
	file://*)
		[ "${CAT_RELEASE_TEST_MODE:-}" = 1 ] || usage
		;;
	*) usage ;;
esac

validate_entries()
{
	root="$1"
	for entry in "${root}"/* "${root}"/.[!.]* "${root}"/..?*; do
		[ -e "${entry}" ] || continue
		name="$(basename "${entry}")"
		case "${name}" in LICENSE.md|README.md|release-manifest.json|codex|claude) ;; *)
			echo "Staged release tree contains an unlisted root entry: ${name}" >&2
			exit 1
			;; esac
	done
	for name in LICENSE.md README.md release-manifest.json codex claude; do
		[ -e "${root}/${name}" ] || { echo "Staged release tree is missing ${name}" >&2; exit 1; }
	done
}

sha256()
{
	if command -v sha256sum >/dev/null 2>&1; then
		sha256sum "$1" | awk '{ print $1 }'
	else
		shasum -a 256 "$1" | awk '{ print $1 }'
	fi
}

validate_manifest()
{
	root="$1"
	manifest="${root}/release-manifest.json"
	grep -Fq '"schema_version" : "cat-release-v1"' "${manifest}" || {
		echo "Staged release manifest has an unsupported schema" >&2
		exit 1
	}
	grep -Fq "\"version\" : \"${version}\"" "${manifest}" || {
		echo "Staged release manifest version does not match ${version}" >&2
		exit 1
	}
	grep -Fq "\"edition\" : \"${edition}\"" "${manifest}" || {
		echo "Staged release manifest edition does not match ${edition}" >&2
		exit 1
	}
	manifest_entries="$(mktemp)"
	awk '
		/"path"[[:space:]]*:/ {
			path = $0; sub(/^.*"path"[[:space:]]*:[[:space:]]*"/, "", path); sub(/".*$/, "", path)
		}
		/"sha256"[[:space:]]*:/ {
			hash = $0; sub(/^.*"sha256"[[:space:]]*:[[:space:]]*"/, "", hash); sub(/".*$/, "", hash)
		}
		/"size"[[:space:]]*:/ {
			size = $0; sub(/^.*"size"[[:space:]]*:[[:space:]]*/, "", size); sub(/[^0-9].*$/, "", size)
			if (path == "" || hash !~ /^[0-9a-f]{64}$/ || size !~ /^[0-9]+$/) exit 1
			print path "\t" hash "\t" size; path = ""; hash = ""; size = ""
		}
		END { if (NR == 0 || path != "" || hash != "" || size != "") exit 1 }
	' "${manifest}" > "${manifest_entries}" || { echo "Staged release manifest is malformed" >&2; exit 1; }
	[ -s "${manifest_entries}" ] || { echo "Staged release manifest contains no files" >&2; exit 1; }
	cut -f 1 "${manifest_entries}" | sort | uniq -d | grep -q . && {
		echo "Staged release manifest contains duplicate paths" >&2
		exit 1
	}
	while IFS="$(printf '\t')" read -r path hash size; do
		case "${path}" in ''|/*|*'..'*|*'//'*) echo "Staged release manifest has unsafe path: ${path}" >&2; exit 1 ;; esac
		file="${root}/${path}"
		if [ ! -f "${file}" ] || [ -L "${file}" ]; then
			echo "Staged release manifest file is missing or unsafe: ${path}" >&2
			exit 1
		fi
		[ "$(wc -c < "${file}" | tr -d '[:space:]')" = "${size}" ] || {
			echo "Staged release manifest size mismatch: ${path}" >&2
			exit 1
		}
		[ "$(sha256 "${file}")" = "${hash}" ] || {
			echo "Staged release manifest checksum mismatch: ${path}" >&2
			exit 1
		}
	done < "${manifest_entries}"
	find "${root}" -type l -print -quit | grep -q . && {
		echo "Staged release tree contains a symbolic link" >&2
		exit 1
	}
	find "${root}" -type f ! -name release-manifest.json -print | while IFS= read -r file; do
		path="${file#"${root}"/}"
		awk -F '\t' -v expected="${path}" '$1 == expected { found = 1 } END { exit !found }' "${manifest_entries}" || {
			echo "Staged release tree contains an unlisted file: ${path}" >&2
			exit 1
		}
	done
}

validate_targets()
{
	root="$1"
	for harness in codex claude; do
		for entry in "${root}/${harness}"/* "${root}/${harness}"/.[!.]* "${root}/${harness}"/..?*; do
			[ -e "${entry}" ] || continue
			platform="$(basename "${entry}")"
			case "${platform}" in linux-x86_64|linux-arm64|macos-x86_64|macos-arm64) ;; *)
				echo "Staged release tree contains an unlisted ${harness} target: ${platform}" >&2
				exit 1
			;; esac
		done
		for platform in linux-x86_64 linux-arm64 macos-x86_64 macos-arm64; do
			[ -d "${root}/${harness}/${platform}" ] || {
				echo "Staged release tree is missing ${harness}/${platform}" >&2
				exit 1
			}
		done
	done
}

validate_entries "${staged_tree}"
validate_targets "${staged_tree}"
validate_manifest "${staged_tree}"

checkout="$(mktemp -d)"
published=false
cleanup()
{
	if [ "${published}" = true ]; then
		rm -rf "${checkout}"
	else
		echo "Retained failed release checkout: ${checkout}" >&2
	fi
	rm -f "${manifest_entries}"
}
trap cleanup EXIT HUP INT TERM
main_ref="$("${git_executable}" ls-remote --heads "${repository}" refs/heads/main)"
if [ -n "${main_ref}" ]; then
	"${git_executable}" clone --branch main "${repository}" "${checkout}"
else
	remote_refs="$("${git_executable}" ls-remote --heads --tags "${repository}")"
	[ -z "${remote_refs}" ] || {
		echo "Release repository has refs but no main branch: ${repository}" >&2
		exit 1
	}
	"${git_executable}" init --initial-branch main "${checkout}" >/dev/null
	"${git_executable}" -C "${checkout}" remote add origin "${repository}"
fi
"${git_executable}" -C "${checkout}" diff --quiet
"${git_executable}" -C "${checkout}" diff --cached --quiet
"${git_executable}" -C "${checkout}" show-ref --verify --quiet "refs/tags/${version}" && {
	echo "Release tag already exists locally: ${version}" >&2
	exit 1
}
"${git_executable}" -C "${checkout}" ls-remote --exit-code --tags origin "refs/tags/${version}" >/dev/null 2>&1 && {
	echo "Release tag already exists remotely: ${version}" >&2
	exit 1
}
"${git_executable}" -C "${checkout}" config user.name >/dev/null
"${git_executable}" -C "${checkout}" config user.email >/dev/null
rm -rf "${checkout}/LICENSE.md" "${checkout}/README.md" "${checkout}/release-manifest.json" \
	"${checkout}/codex" "${checkout}/claude"
cp -R "${staged_tree}/LICENSE.md" "${staged_tree}/README.md" "${staged_tree}/release-manifest.json" \
	"${staged_tree}/codex" "${staged_tree}/claude" "${checkout}/"
"$(dirname "$0")/test-staged-release.sh" "${checkout}" "${edition}" "${version}"
"${git_executable}" -C "${checkout}" add LICENSE.md README.md release-manifest.json codex claude
"${git_executable}" -C "${checkout}" diff --cached --quiet && {
	echo "Staged release tree makes no release-tree change" >&2
	exit 1
}
"${git_executable}" -C "${checkout}" commit -m "Release CAT ${version} (${edition})"
"${git_executable}" -C "${checkout}" tag -a "${version}" -m "CAT ${version} (${edition})"
"${git_executable}" -C "${checkout}" push --atomic origin main "refs/tags/${version}"
published=true
