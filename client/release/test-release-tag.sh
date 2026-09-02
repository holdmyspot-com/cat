#!/usr/bin/env sh

# Exercises immutable version-tag publication against local bare repositories.

set -eu

[ "$#" -eq 3 ] || { echo "Usage: $0 PERSONAL_TREE COMMERCIAL_TREE VERSION" >&2; exit 2; }
personal="$(cd "$1" && pwd)"
commercial="$(cd "$2" && pwd)"
version="$3"
git_executable="$(command -v git)"
publisher="$(dirname "$0")/publish-release.sh"

create_remote()
{
	edition="$1"
	remote_root="$(mktemp -d)"
	remote="${remote_root}/${edition}.git"
	"${git_executable}" init --bare "${remote}" >/dev/null
	echo "${remote_root}"
}

publish_and_verify()
{
	edition="$1"
	tree="$2"
	remote_root="$(create_remote "${edition}")"
	remote="${remote_root}/${edition}.git"
	CAT_RELEASE_TEST_MODE=1 GIT_CONFIG_COUNT=2 GIT_CONFIG_KEY_0=user.name \
		GIT_CONFIG_VALUE_0="CAT release test" GIT_CONFIG_KEY_1=user.email \
		GIT_CONFIG_VALUE_1=release-test@example.invalid "${publisher}" --edition "${edition}" --staged-tree "${tree}" \
		--repository "file://${remote}" --version "${version}" --git "${git_executable}"
	"${git_executable}" --git-dir="${remote}" show-ref --verify --quiet "refs/tags/${version}"
	"${git_executable}" --git-dir="${remote}" show "${version}:release-manifest.json" | \
		grep -Fq "\"edition\" : \"${edition}\""
	if CAT_RELEASE_TEST_MODE=1 GIT_CONFIG_COUNT=2 GIT_CONFIG_KEY_0=user.name \
		GIT_CONFIG_VALUE_0="CAT release test" GIT_CONFIG_KEY_1=user.email \
		GIT_CONFIG_VALUE_1=release-test@example.invalid "${publisher}" --edition "${edition}" --staged-tree "${tree}" \
		--repository "file://${remote}" --version "${version}" --git "${git_executable}" >/dev/null 2>&1; then
		echo "Publisher replaced immutable tag ${version}" >&2
		rm -rf "${remote_root}"
		exit 1
	fi
	rm -rf "${remote_root}"
}

reject_non_main_repository()
{
	remote_root="$(mktemp -d)"
	remote="${remote_root}/non-main.git"
	seed="${remote_root}/seed"
	"${git_executable}" init --bare "${remote}" >/dev/null
	"${git_executable}" clone "${remote}" "${seed}" >/dev/null 2>&1
	"${git_executable}" -C "${seed}" checkout -b release >/dev/null
	"${git_executable}" -C "${seed}" config user.name "CAT release test"
	"${git_executable}" -C "${seed}" config user.email "release-test@example.invalid"
	: > "${seed}/README.md"
	"${git_executable}" -C "${seed}" add README.md
	"${git_executable}" -C "${seed}" commit -m "Initialize non-main release repository" >/dev/null
	"${git_executable}" -C "${seed}" push origin release >/dev/null
	if CAT_RELEASE_TEST_MODE=1 GIT_CONFIG_COUNT=2 GIT_CONFIG_KEY_0=user.name \
		GIT_CONFIG_VALUE_0="CAT release test" GIT_CONFIG_KEY_1=user.email \
		GIT_CONFIG_VALUE_1=release-test@example.invalid "${publisher}" --edition personal --staged-tree "${personal}" \
		--repository "file://${remote}" --version "${version}" --git "${git_executable}" >/dev/null 2>&1; then
		echo "Publisher accepted a release repository without main" >&2
		rm -rf "${remote_root}"
		exit 1
	fi
	"${git_executable}" --git-dir="${remote}" show-ref --verify --quiet "refs/heads/main" && {
		echo "Publisher created main in a non-empty repository without main" >&2
		rm -rf "${remote_root}"
		exit 1
	}
	rm -rf "${remote_root}"
}

[ -d "${personal}" ] || { echo "Personal release tree is not a directory: ${personal}" >&2; exit 1; }
[ -d "${commercial}" ] || { echo "Commercial release tree is not a directory: ${commercial}" >&2; exit 1; }
publish_and_verify personal "${personal}"
publish_and_verify commercial "${commercial}"
reject_non_main_repository
