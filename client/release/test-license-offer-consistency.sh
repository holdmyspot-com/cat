#!/usr/bin/env sh

# Verifies the release editions agree with CAT's published licensing and commercial offer.

set -eu

[ "$#" -eq 5 ] || {
	echo "Usage: $0 PERSONAL_TREE COMMERCIAL_TREE PRICING LICENSE VERSION" >&2
	exit 2
}
personal="$1"
commercial="$2"
pricing="$3"
license="$4"
version="$5"

for path in "${personal}" "${commercial}"; do
	[ -d "${path}" ] || { echo "Release tree is not a directory: ${path}" >&2; exit 1; }
	document="${path}/release-manifest.json"
	[ -f "${document}" ] || { echo "Release tree has no manifest: ${path}" >&2; exit 1; }
	grep -Fq "\"version\" : \"${version}\"" "${document}" || {
		echo "Release tree manifest version does not match ${version}: ${path}" >&2
		exit 1
	}
done
if [ ! -f "${pricing}" ] || [ ! -f "${license}" ]; then
	echo "Pricing or license document is missing" >&2
	exit 1
fi
grep -Fq "USD \$15 per licensed person each month" "${pricing}" || {
	echo "Pricing does not state the commercial monthly per-person offer" >&2
	exit 1
}
grep -Fq 'Each employee or contractor who uses CAT for organizational work must be covered by a paid seat.' "${license}" || {
	echo "License does not state the commercial per-person requirement" >&2
	exit 1
}
for tree in "${personal}" "${commercial}"; do
	grep -Fq '"edition" : "'"$(basename "${tree}")"'"' "${tree}/release-manifest.json" || {
		echo "Release tree manifest edition does not match its directory: ${tree}" >&2
		exit 1
	}
done
for harness in codex claude; do
	for platform in linux-x86_64 linux-arm64 macos-x86_64 macos-arm64; do
		[ ! -e "${personal}/${harness}/${platform}/skills/prompt-optimizer" ] || {
			echo "Personal release exposes prompt optimization: ${harness}/${platform}" >&2
			exit 1
		}
		[ -f "${commercial}/${harness}/${platform}/skills/prompt-optimizer/SKILL.md" ] || {
			echo "Commercial release is missing prompt optimization: ${harness}/${platform}" >&2
			exit 1
		}
	done
done
