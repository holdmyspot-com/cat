#!/usr/bin/env sh

# Verifies one complete staged CAT edition tree before it is published.

set -eu

[ "$#" -eq 3 ] || {
	echo "Usage: $0 STAGED_TREE personal|commercial VERSION" >&2
	exit 2
}
tree="$1"
edition="$2"
version="$3"
case "${edition}" in personal|commercial) ;; *) exit 2 ;; esac
[ -d "${tree}" ] || { echo "Staged tree is not a directory: ${tree}" >&2; exit 1; }

for entry in "${tree}"/* "${tree}"/.[!.]* "${tree}"/..?*; do
	[ -e "${entry}" ] || continue
	case "$(basename "${entry}")" in .git|LICENSE.md|README.md|release-manifest.json|codex|claude) ;; *)
		echo "Staged tree contains an unlisted root entry: ${entry}" >&2
		exit 1
	;; esac
done
for entry in LICENSE.md README.md release-manifest.json codex claude; do
	[ -e "${tree}/${entry}" ] || { echo "Staged tree is missing ${entry}" >&2; exit 1; }
done
for harness in codex claude; do
	for platform in linux-x86_64 linux-arm64 macos-x86_64 macos-arm64; do
		[ -d "${tree}/${harness}/${platform}" ] || {
			echo "Staged tree is missing ${harness}/${platform}" >&2
			exit 1
		}
	done
done
find "${tree}" -type l -print -quit | grep -q . && {
	echo "Staged tree contains a symbolic link" >&2
	exit 1
}
manifest="${tree}/release-manifest.json"
grep -Fq '"schema_version" : "cat-release-v1"' "${manifest}" || {
	echo "Staged tree manifest has an unsupported schema" >&2
	exit 1
}
grep -Fq "\"version\" : \"${version}\"" "${manifest}" || {
	echo "Staged tree manifest version does not match ${version}" >&2
	exit 1
}
grep -Fq "\"edition\" : \"${edition}\"" "${manifest}" || {
	echo "Staged tree manifest edition does not match ${edition}" >&2
	exit 1
}
entries="$(mktemp)"
trap 'rm -f "${entries}"' EXIT HUP INT TERM
awk '
	/"path"[[:space:]]*:/ { path = $0; sub(/^.*"path"[[:space:]]*:[[:space:]]*"/, "", path); sub(/".*$/, "", path) }
	/"sha256"[[:space:]]*:/ { hash = $0; sub(/^.*"sha256"[[:space:]]*:[[:space:]]*"/, "", hash); sub(/".*$/, "", hash) }
	/"size"[[:space:]]*:/ {
		size = $0; sub(/^.*"size"[[:space:]]*:[[:space:]]*/, "", size); sub(/[^0-9].*$/, "", size)
		if (path == "" || hash !~ /^[0-9a-f]{64}$/ || size !~ /^[0-9]+$/) exit 1
		print path "\t" hash "\t" size; path = ""; hash = ""; size = ""
	}
	END { if (NR == 0 || path != "" || hash != "" || size != "") exit 1 }
' "${manifest}" > "${entries}" || { echo "Staged tree manifest is malformed" >&2; exit 1; }
while IFS="$(printf '\t')" read -r path hash size; do
	case "${path}" in LICENSE.md|README.md|codex/*|claude/*) ;; *)
		echo "Staged tree manifest contains an unsupported path: ${path}" >&2
		exit 1
	;; esac
	case "${path}" in ''|/*|*'..'*|*'//'*) echo "Staged tree manifest contains an unsafe path: ${path}" >&2; exit 1 ;; esac
	file="${tree}/${path}"
	if [ ! -f "${file}" ] || [ -L "${file}" ]; then
		echo "Staged tree manifest file is missing or unsafe: ${path}" >&2
		exit 1
	fi
	[ "$(wc -c < "${file}" | tr -d '[:space:]')" = "${size}" ] || {
		echo "Staged tree manifest size mismatch: ${path}" >&2
		exit 1
	}
	if command -v sha256sum >/dev/null 2>&1; then actual="$(sha256sum "${file}" | awk '{ print $1 }')";
	else actual="$(shasum -a 256 "${file}" | awk '{ print $1 }')"; fi
	[ "${actual}" = "${hash}" ] || { echo "Staged tree manifest checksum mismatch: ${path}" >&2; exit 1; }
done < "${entries}"
find "${tree}" -path "${tree}/.git" -prune -o -type f ! -name release-manifest.json -print | while IFS= read -r file; do
	path="${file#"${tree}"/}"
	awk -F '\t' -v expected="${path}" '$1 == expected { found = 1 } END { exit !found }' "${entries}" || {
		echo "Staged tree contains an unlisted file: ${path}" >&2
		exit 1
	}
done
