#!/usr/bin/env bash
# Decides which packages the Release workflow publishes. A release publishes the archives that the Build workflow built
# and tested for the release commit, so this script finds that Build run, checks that its artifacts are the five
# platforms' packages, and verifies the downloaded archives against the release tag, the release commit and the release
# public key. It reads files and archives only; the workflow runs gh and downloads the artifacts.
#
#   release-packages.sh public-key
#   release-packages.sh select-run COMMIT REPOSITORY RUNS_JSON
#   release-packages.sh check-artifacts RUN_ID ARTIFACTS_JSON
#   release-packages.sh verify-packages NATIVE_DIRECTORY TAG BUILD PUBLIC_KEY
#
# COMMIT is the release commit's full object name and BUILD its first 12 hexadecimal digits. RUNS_JSON and
# ARTIFACTS_JSON are the GitHub API's lists of the Build workflow's runs and of one run's artifacts. NATIVE_DIRECTORY
# holds one folder per artifact, named as the artifact is.

set -euo pipefail

readonly -a RELEASE_PLATFORMS=(linux-x64 linux-arm64 macos-x64 macos-arm64 windows-x64)
readonly -a RELEASE_HARNESSES=(claude codex)

# Prints a message to standard error.
# Arguments: the message.
# Exit status: always 1, so that a function can end with this call to report its failure.
fail()
{
	echo "$*" >&2
	return 1
}

# Prints the SHA-256 digest of a file, with the tool that the machine has.
# Arguments:
#   $1 - The file.
# Output: the digest in lower-case hexadecimal.
package_digest()
{
	if command -v sha256sum > /dev/null; then
		sha256sum "$1" | cut -d ' ' -f 1
	else
		shasum -a 256 "$1" | cut -d ' ' -f 1
	fi
}

# Derives the release public key from the release signing key, as the build embeds it in every archive.
# Environment:
#   CAT_RELEASE_SIGNING_KEY - The PEM text of the release signing key, an Ed25519 private key.
#   RUNNER_TEMP - The folder in which the key is held for the duration of the call; /tmp when unset.
# Output: the base64 of the X.509 encoding of the public key, on one line.
# Errors: reports an empty signing key, and text that openssl cannot read as a private key.
# Exit status: 0 on success, or 1 on an error.
derive_public_key()
{
	local key_file="${RUNNER_TEMP:-${TMPDIR:-/tmp}}/cat-release-signing-key.pem"
	local public_key

	if [[ -z "${CAT_RELEASE_SIGNING_KEY:-}" ]]; then
		fail "The secret CAT_RELEASE_SIGNING_KEY is empty or unavailable to this workflow."
		return
	fi
	(umask 077 && printf '%s\n' "${CAT_RELEASE_SIGNING_KEY}" > "${key_file}")
	public_key="$(openssl pkey -in "${key_file}" -pubout -outform DER 2> /dev/null | base64 | tr -d '\r\n')" ||
		public_key=""
	rm -f -- "${key_file}"
	if [[ -z "${public_key}" ]]; then
		fail "CAT_RELEASE_SIGNING_KEY holds no private key that openssl can read."
		return
	fi
	printf '%s\n' "${public_key}"
}

# Chooses the Build run whose packages the release publishes: the newest run on a push, in the repository, that
# finished successfully with the release commit as its head. A run on a pull request never qualifies.
# Arguments:
#   $1 - The release commit's full object name.
#   $2 - The repository, as owner/name.
#   $3 - The file that holds the API's list of the Build workflow's runs.
# Output: the chosen run's id.
# Errors: reports, with the next step, that no run qualifies, and lists the runs that the commit has.
# Exit status: 0 when a run qualifies, or 1 otherwise.
select_build_run()
{
	local commit="$1" repository="$2" runs_file="$3"
	local run_id
	local found

	run_id="$(jq -r --arg commit "${commit}" --arg repository "${repository}" '
		[.workflow_runs[] |
			select(.head_sha == $commit and .event == "push" and .status == "completed" and
				.conclusion == "success" and .head_repository.full_name == $repository and
				.repository.full_name == $repository)] |
		max_by(.id) | .id // empty' "${runs_file}")"
	if [[ -n "${run_id}" ]]; then
		printf '%s\n' "${run_id}"
		return 0
	fi
	found="$(jq -r --arg commit "${commit}" '
		.workflow_runs[] | select(.head_sha == $commit) |
		"  run \(.id) (\(.event), \(.status), \(.conclusion // "none"))"' "${runs_file}")"
	{
		echo "No successful Build run on a push has the head commit ${commit} in ${repository}."
		if [[ -n "${found}" ]]; then
			echo "The Build runs that the commit has:"
			echo "${found}"
		else
			echo "No Build run was started for it."
		fi
		echo "Push the commit to a branch, wait until the Build workflow passes on all five platforms, then run" \
			"Release again. Release does not rebuild the packages."
	} >&2
	return 1
}

# Checks that a Build run holds the five platforms' packages and nothing else of the release.
# Arguments:
#   $1 - The Build run's id.
#   $2 - The file that holds the API's list of the run's artifacts.
# Errors: names each platform artifact that is missing or expired and each other artifact whose name starts with cat-,
#   and says how to rebuild them. An artifact whose name does not start with cat-, such as a test report, is ignored.
# Exit status: 0 when the run holds exactly the five artifacts, or 1 otherwise.
check_build_artifacts()
{
	local run_id="$1" artifacts_file="$2"
	local platform
	local name
	local expected_json
	local -a problems=()

	expected_json="$(printf 'cat-%s\n' "${RELEASE_PLATFORMS[@]}" | jq -R . | jq -s .)"
	for platform in "${RELEASE_PLATFORMS[@]}"; do
		name="cat-${platform}"
		if jq -e --arg name "${name}" 'any(.artifacts[]; .name == $name and (.expired | not))' "${artifacts_file}" \
			> /dev/null; then
			continue
		fi
		if jq -e --arg name "${name}" 'any(.artifacts[]; .name == $name)' "${artifacts_file}" > /dev/null; then
			problems+=("The artifact ${name} has expired.")
		else
			problems+=("The artifact ${name} does not exist.")
		fi
	done
	while IFS= read -r name; do
		problems+=("The artifact ${name} is not a platform that CAT releases.")
	done < <(jq -r --argjson expected "${expected_json}" '
		.artifacts[] | select(.name | startswith("cat-")) | select(.name as $name | $expected | index($name) | not) |
		.name' "${artifacts_file}")
	if ((${#problems[@]} == 0)); then
		return 0
	fi
	{
		echo "Build run ${run_id} does not hold the release's packages:"
		printf '  %s\n' "${problems[@]}"
		echo "Artifacts expire after their retention period. Re-run Build run ${run_id} to build and upload them" \
			"again. A Build job uploads nothing when the secret CAT_RELEASE_SIGNING_KEY is unavailable to it."
	} >&2
	return 1
}

# Checks the files that one platform's Build job uploaded: exactly its two archives, its checksum list and its release
# assets fragment, with the archives matching the checksum list and the fragment.
# Arguments:
#   $1 - The folder of the platform's artifact.
#   $2 - The platform.
#   $3 - The release tag.
#   $4 - The first 12 hexadecimal digits of the release commit.
# Errors: reports the first file that fails a check.
# Exit status: 0 when every check passes, or 1 otherwise.
verify_platform_files()
{
	local directory="$1" platform="$2" tag="$3" build="$4"
	local artifact="cat-${platform}"
	local fragment="${directory}/cat-release-assets-${platform}.json"
	local expected
	local found
	local line
	local harness
	local archive
	local digest
	local fragment_build

	if [[ ! -d "${directory}" ]]; then
		fail "${artifact}: the artifact's folder ${directory} does not exist."
		return
	fi
	expected="$(printf '%s\n' "cat-claude-${platform}-${tag}.tar.gz" "cat-codex-${platform}-${tag}.tar.gz" \
		SHA256SUMS.txt "cat-release-assets-${platform}.json" | LC_ALL=C sort)"
	found="$(cd "${directory}" && find . -type f | sed 's|^\./||' | LC_ALL=C sort)"
	if [[ "${found}" != "${expected}" ]]; then
		{
			echo "${artifact}: it must hold exactly these files:"
			while IFS= read -r line; do echo "  ${line}"; done <<< "${expected}"
			echo "but it holds:"
			while IFS= read -r line; do echo "  ${line}"; done <<< "${found}"
		} >&2
		return 1
	fi
	if [[ "$(wc -l < "${directory}/SHA256SUMS.txt" | tr -d ' ')" -ne "${#RELEASE_HARNESSES[@]}" ]]; then
		fail "${artifact}: SHA256SUMS.txt must list exactly the platform's two archives."
		return
	fi
	if ! fragment_build="$(jq -r '.build // ""' "${fragment}" 2> /dev/null)"; then
		fail "${artifact}: ${fragment##*/} is not JSON."
		return
	fi
	if [[ "${fragment_build}" != "${build}" ]]; then
		fail "${artifact}: ${fragment##*/} names the build ${fragment_build}, not the release commit ${build}."
		return
	fi
	for harness in "${RELEASE_HARNESSES[@]}"; do
		archive="cat-${harness}-${platform}-${tag}.tar.gz"
		digest="$(package_digest "${directory}/${archive}")"
		if ! grep -Fxq -- "${digest}  ${archive}" "${directory}/SHA256SUMS.txt"; then
			fail "${artifact}: ${archive} does not match its line in SHA256SUMS.txt."
			return
		fi
		if ! jq -e --arg name "${archive}" --arg digest "${digest}" --arg platform "${platform}" \
			--arg top "${archive%.tar.gz}" 'any(.assets[]; .name == $name and .sha256 == $digest and
				.platform == $platform and .top_directory == $top)' "${fragment}" > /dev/null; then
			fail "${artifact}: ${fragment##*/} does not list ${archive} with its digest, platform and top folder."
			return
		fi
	done
}

# Checks what one archive says about the release it belongs to: the build stamp in its bin/VERSION and the release
# public key in its default installer policy.
# Arguments:
#   $1 - The archive.
#   $2 - The version that the build stamp must start with, such as 1.0.
#   $3 - The first 12 hexadecimal digits of the release commit.
#   $4 - The base64 release public key that the policy must list, and nothing else.
# Errors: reports the stamp that the archive holds, or that its policy lists another key than the release's.
# Exit status: 0 when the archive passes, or 1 otherwise.
verify_archive_release()
{
	local archive="$1" version="$2" build="$3" public_key="$4"
	local name="${archive##*/}"
	local top="${name%.tar.gz}"
	local stamp
	local policy

	if ! stamp="$(tar -xzOf "${archive}" "${top}/bin/VERSION" 2> /dev/null)"; then
		fail "${name}: it holds no ${top}/bin/VERSION."
		return
	fi
	if [[ "${stamp}" != "${version}+b${build}" ]]; then
		fail "${name}: its bin/VERSION holds ${stamp}, not ${version}+b${build}."
		return
	fi
	if ! policy="$(tar -xzOf "${archive}" "${top}/bin/conf/installer/default-policy.json" 2> /dev/null)"; then
		fail "${name}: it holds no ${top}/bin/conf/installer/default-policy.json."
		return
	fi
	if ! printf '%s' "${policy}" | jq -e --arg key "${public_key}" \
		'.keys == [{id: "cat-release", ed25519_x509_base64: $key}]' > /dev/null 2>&1; then
		fail "${name}: its default policy does not list exactly the release public key that the signing secret derives."
		return
	fi
}

# Verifies the downloaded packages of the five platforms: every platform's files and checksums, and every archive's
# build stamp and release public key, against the release tag, the release commit and the release public key.
# Arguments:
#   $1 - The folder that holds one folder per platform artifact, each named cat-<platform>.
#   $2 - The release tag, a v followed by the version, such as v1.0.
#   $3 - The first 12 hexadecimal digits of the release commit.
#   $4 - The base64 release public key that every archive's default policy must list.
# Errors: reports the first failed check, naming the artifact and the archive.
# Exit status: 0 when every check passes, or 1 otherwise.
verify_packages()
{
	local native="$1" tag="$2" build="$3" public_key="$4"
	local platform
	local harness

	if ! printf '%s\n' "${tag}" | grep -Eqx 'v[0-9]+\.[0-9]+(\.[0-9]+)?'; then
		fail "The release tag must look like v1.0 or v1.0.1: ${tag}"
		return
	fi
	for platform in "${RELEASE_PLATFORMS[@]}"; do
		verify_platform_files "${native}/cat-${platform}" "${platform}" "${tag}" "${build}" || return 1
		for harness in "${RELEASE_HARNESSES[@]}"; do
			verify_archive_release "${native}/cat-${platform}/cat-${harness}-${platform}-${tag}.tar.gz" "${tag#v}" \
				"${build}" "${public_key}" || return 1
		done
	done
}

# Prints the script's usage and ends the script.
# Errors: writes the usage to standard error.
# Exit status: ends the script with status 2.
print_usage()
{
	echo "Usage: $0 public-key" >&2
	echo "       $0 select-run COMMIT REPOSITORY RUNS_JSON" >&2
	echo "       $0 check-artifacts RUN_ID ARTIFACTS_JSON" >&2
	echo "       $0 verify-packages NATIVE_DIRECTORY TAG BUILD PUBLIC_KEY" >&2
	exit 2
}

# Runs the command that the arguments name.
# Arguments: the command, then its arguments as the usage describes.
# Exit status: that of the command, or 2 for an unknown command or the wrong number of arguments.
main()
{
	local command="${1:-}"

	case "${command}" in
	public-key)
		[[ $# -eq 1 ]] || print_usage
		derive_public_key
		;;
	select-run)
		[[ $# -eq 4 ]] || print_usage
		select_build_run "$2" "$3" "$4"
		;;
	check-artifacts)
		[[ $# -eq 3 ]] || print_usage
		check_build_artifacts "$2" "$3"
		;;
	verify-packages)
		[[ $# -eq 5 ]] || print_usage
		verify_packages "$2" "$3" "$4" "$5"
		;;
	*)
		print_usage
		;;
	esac
}

if [[ "${BASH_SOURCE[0]}" == "${0}" ]]; then
	main "$@"
fi
