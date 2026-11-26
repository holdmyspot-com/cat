#!/usr/bin/env bats
# Tests how the Release workflow chooses the Build run whose packages it publishes, checks that run's artifacts, and
# verifies the downloaded archives against the release tag, the release commit and the release public key. The tests
# call the script's functions on sample run lists and on archives that they generate; they never reach GitHub.

bats_require_minimum_version 1.5.0

setup()
{
	SCRIPT="${BATS_TEST_DIRNAME}/../../../../../../.github/scripts/release-packages.sh"
	# shellcheck source=/dev/null
	source "${SCRIPT}"
	export RUNNER_TEMP="${BATS_TEST_TMPDIR}"
	REPOSITORY="example/cat"
	FULL_COMMIT="0123456789abcdef0123456789abcdef01234567"
	COMMIT="0123456789ab"
	TAG="v1.0"
	KEY="MCowBQYDK2VwAyEAdGVzdC1yZWxlYXNlLWtleQ=="
	RUNS="${BATS_TEST_TMPDIR}/runs.json"
	ARTIFACTS="${BATS_TEST_TMPDIR}/artifacts.json"
	NATIVE="${BATS_TEST_TMPDIR}/native"
	FIVE_PLATFORMS=(linux-x64 linux-arm64 macos-x64 macos-arm64 windows-x64)
}

# Prints the SHA-256 digest of a file, with the tool that the machine has.
# Arguments: the file.
# Output: the digest in lower-case hexadecimal.
digest()
{
	if command -v sha256sum > /dev/null; then
		sha256sum "$1" | cut -d ' ' -f 1
	else
		shasum -a 256 "$1" | cut -d ' ' -f 1
	fi
}

# Prints one workflow run as the GitHub API lists it.
# Arguments: the run's id, its event, its status, its conclusion (empty while it runs), its head commit and the
#   repository that holds it.
# Output: one JSON object.
build_run()
{
	jq -n --argjson id "$1" --arg event "$2" --arg status "$3" --arg conclusion "$4" --arg sha "$5" --arg repository "$6" \
		'{id: $id, event: $event, status: $status, conclusion: (if $conclusion == "" then null else $conclusion end),
			head_sha: $sha, head_repository: {full_name: $repository}, repository: {full_name: $repository}}'
}

# Writes the objects that it reads from standard input as the API's list of a workflow's runs.
# Arguments: the file to write.
runs_to()
{
	jq -s '{total_count: length, workflow_runs: .}' > "$1"
}

# Writes the API's list of a run's artifacts.
# Arguments: the file to write, then one "name" or "name:expired" for each artifact.
artifacts_to()
{
	local file="$1"
	local entry
	shift
	for entry in "$@"; do
		jq -n --arg name "${entry%%:*}" --argjson expired "$([[ "${entry}" == *:expired ]] && echo true || echo false)" \
			'{name: $name, expired: $expired}'
	done | jq -s '{total_count: length, artifacts: .}' > "${file}"
}

# Creates one plugin archive the way the release build lays it out.
# Arguments: the folder to create it in, the harness, the platform, the version file's text, and the base64 release key
#   that its default policy lists (empty for none).
make_archive()
{
	local directory="$1" harness="$2" platform="$3" stamp="$4" key="$5"
	local top="cat-${harness}-${platform}-${TAG}"
	local staging="${BATS_TEST_TMPDIR}/staging-${harness}-${platform}"
	rm -rf -- "${staging}"
	mkdir -p "${staging}/${top}/bin/conf/installer" "${directory}"
	printf '%s\n' "${stamp}" > "${staging}/${top}/bin/VERSION"
	if [[ -n "${key}" ]]; then
		jq -n --arg key "${key}" '{format: 1, keys: [{id: "cat-release", ed25519_x509_base64: $key}]}'
	else
		jq -n '{format: 1, keys: []}'
	fi > "${staging}/${top}/bin/conf/installer/default-policy.json"
	tar -czf "${directory}/${top}.tar.gz" -C "${staging}" "${top}"
}

# Writes a platform's checksum list and release assets fragment from the archives in its artifact folder.
# Arguments: the platform and the commit that the fragment names.
refresh_platform()
{
	local platform="$1" commit="$2"
	local directory="${NATIVE}/cat-${platform}"
	local harness
	local archive
	: > "${directory}/SHA256SUMS.txt"
	for harness in claude codex; do
		archive="cat-${harness}-${platform}-${TAG}.tar.gz"
		printf '%s  %s\n' "$(digest "${directory}/${archive}")" "${archive}" >> "${directory}/SHA256SUMS.txt"
		jq -n --arg name "${archive}" --arg harness "${harness}" --arg platform "${platform}" \
			--arg sha "$(digest "${directory}/${archive}")" --arg top "cat-${harness}-${platform}-${TAG}" \
			'{name: $name, kind: "plugin", harness: $harness, platform: $platform, size: 1, sha256: $sha,
				top_directory: $top}'
	done | jq -s --arg commit "${commit}" '{build: $commit, assets: .}' > "${directory}/cat-release-assets-${platform}.json"
}

# Creates the artifacts of all five platforms, as their Build jobs upload them.
make_native()
{
	local platform
	for platform in "${FIVE_PLATFORMS[@]}"; do
		make_archive "${NATIVE}/cat-${platform}" claude "${platform}" "1.0+b${COMMIT}" "${KEY}"
		make_archive "${NATIVE}/cat-${platform}" codex "${platform}" "1.0+b${COMMIT}" "${KEY}"
		refresh_platform "${platform}" "${COMMIT}"
	done
}

@test "the newest successful push run of the release commit is the one that Release uses" {
	{
		build_run 100 push completed success "${FULL_COMMIT}" "${REPOSITORY}"
		build_run 300 push completed success "${FULL_COMMIT}" "${REPOSITORY}"
		build_run 200 push completed success "${FULL_COMMIT}" "${REPOSITORY}"
	} | runs_to "${RUNS}"
	run --separate-stderr select_build_run "${FULL_COMMIT}" "${REPOSITORY}" "${RUNS}"
	[ "${status}" -eq 0 ]
	[ "${output}" = "300" ]
}

@test "a passing run on a pull request is never used, even for the release commit" {
	build_run 100 pull_request completed success "${FULL_COMMIT}" "${REPOSITORY}" | runs_to "${RUNS}"
	run select_build_run "${FULL_COMMIT}" "${REPOSITORY}" "${RUNS}"
	[ "${status}" -eq 1 ]
	[[ "${output}" == *"No successful Build run on a push"* ]]
	[[ "${output}" == *"run 100 (pull_request, completed, success)"* ]]
}

@test "a failed run and a running run are listed as the reason there is no Build to use" {
	{
		build_run 100 push completed failure "${FULL_COMMIT}" "${REPOSITORY}"
		build_run 200 push in_progress "" "${FULL_COMMIT}" "${REPOSITORY}"
	} | runs_to "${RUNS}"
	run select_build_run "${FULL_COMMIT}" "${REPOSITORY}" "${RUNS}"
	[ "${status}" -eq 1 ]
	[[ "${output}" == *"run 100 (push, completed, failure)"* ]]
	[[ "${output}" == *"run 200 (push, in_progress, none)"* ]]
	[[ "${output}" == *"run Release again"* ]]
}

@test "a passing run of another commit or of another repository is not used" {
	{
		build_run 100 push completed success "fedcba9876543210fedcba9876543210fedcba98" "${REPOSITORY}"
		build_run 200 push completed success "${FULL_COMMIT}" "someone/cat"
	} | runs_to "${RUNS}"
	run select_build_run "${FULL_COMMIT}" "${REPOSITORY}" "${RUNS}"
	[ "${status}" -eq 1 ]
	[[ "${output}" == *"No successful Build run on a push"* ]]
	[[ "${output}" != *"run 100"* ]]
}

@test "a commit that no Build ran for is refused with the next step" {
	runs_to "${RUNS}" < /dev/null
	run select_build_run "${FULL_COMMIT}" "${REPOSITORY}" "${RUNS}"
	[ "${status}" -eq 1 ]
	[[ "${output}" == *"${FULL_COMMIT}"* ]]
	[[ "${output}" == *"No Build run was started for it"* ]]
	[[ "${output}" == *"does not rebuild"* ]]
}

@test "a run with exactly the five platform artifacts is accepted" {
	artifacts_to "${ARTIFACTS}" cat-linux-x64 cat-linux-arm64 cat-macos-x64 cat-macos-arm64 cat-windows-x64
	run check_build_artifacts 7 "${ARTIFACTS}"
	[ "${status}" -eq 0 ]
}

@test "test reports of a failed attempt are not counted among the platform artifacts" {
	artifacts_to "${ARTIFACTS}" cat-linux-x64 cat-linux-arm64 cat-macos-x64 cat-macos-arm64 cat-windows-x64 \
		test-reports-windows-x64
	run check_build_artifacts 7 "${ARTIFACTS}"
	[ "${status}" -eq 0 ]
}

@test "an expired platform artifact is refused with advice to re-run the Build" {
	artifacts_to "${ARTIFACTS}" cat-linux-x64 cat-linux-arm64 cat-macos-x64:expired cat-macos-arm64 cat-windows-x64
	run check_build_artifacts 7 "${ARTIFACTS}"
	[ "${status}" -eq 1 ]
	[[ "${output}" == *"cat-macos-x64"* ]]
	[[ "${output}" == *"Re-run Build run 7"* ]]
}

@test "a platform whose Build job uploaded nothing is refused by name" {
	artifacts_to "${ARTIFACTS}" cat-linux-x64 cat-linux-arm64 cat-macos-arm64 cat-windows-x64
	run check_build_artifacts 7 "${ARTIFACTS}"
	[ "${status}" -eq 1 ]
	[[ "${output}" == *"cat-macos-x64"* ]]
	[[ "${output}" == *"CAT_RELEASE_SIGNING_KEY"* ]]
}

@test "an artifact of a platform that CAT does not release is refused" {
	artifacts_to "${ARTIFACTS}" cat-linux-x64 cat-linux-arm64 cat-macos-x64 cat-macos-arm64 cat-windows-x64 cat-freebsd-x64
	run check_build_artifacts 7 "${ARTIFACTS}"
	[ "${status}" -eq 1 ]
	[[ "${output}" == *"cat-freebsd-x64"* ]]
}

@test "the archives of a complete Build are accepted" {
	make_native
	run verify_packages "${NATIVE}" "${TAG}" "${COMMIT}" "${KEY}"
	[ "${status}" -eq 0 ]
}

@test "an archive stamped with another commit is refused naming its platform and harness" {
	make_native
	make_archive "${NATIVE}/cat-macos-arm64" codex macos-arm64 "1.0+bfedcba987654" "${KEY}"
	refresh_platform macos-arm64 "${COMMIT}"
	run verify_packages "${NATIVE}" "${TAG}" "${COMMIT}" "${KEY}"
	[ "${status}" -eq 1 ]
	[[ "${output}" == *"cat-codex-macos-arm64-v1.0.tar.gz"* ]]
	[[ "${output}" == *"1.0+bfedcba987654"* ]]
	[[ "${output}" == *"1.0+b${COMMIT}"* ]]
}

@test "archives built for another version than the tag's are refused" {
	make_native
	run verify_packages "${NATIVE}" "v1.1" "${COMMIT}" "${KEY}"
	[ "${status}" -eq 1 ]
	[[ "${output}" == *"cat-linux-x64"* ]]
	[[ "${output}" == *"v1.1"* ]]
}

@test "a tag that is not a version is refused before any archive is read" {
	run verify_packages "${NATIVE}" "release-1" "${COMMIT}" "${KEY}"
	[ "${status}" -eq 1 ]
	[[ "${output}" == *"release-1"* ]]
}

@test "an archive that changed after its checksum was written is refused" {
	make_native
	make_archive "${NATIVE}/cat-linux-arm64" claude linux-arm64 "1.0+b${COMMIT}" "${KEY}"
	printf 'tampered' >> "${NATIVE}/cat-linux-arm64/cat-claude-linux-arm64-v1.0.tar.gz"
	run verify_packages "${NATIVE}" "${TAG}" "${COMMIT}" "${KEY}"
	[ "${status}" -eq 1 ]
	[[ "${output}" == *"cat-claude-linux-arm64-v1.0.tar.gz"* ]]
	[[ "${output}" == *"SHA256SUMS.txt"* ]]
}

@test "an archive whose default policy trusts no key is refused" {
	make_native
	make_archive "${NATIVE}/cat-windows-x64" claude windows-x64 "1.0+b${COMMIT}" ""
	refresh_platform windows-x64 "${COMMIT}"
	run verify_packages "${NATIVE}" "${TAG}" "${COMMIT}" "${KEY}"
	[ "${status}" -eq 1 ]
	[[ "${output}" == *"cat-claude-windows-x64-v1.0.tar.gz"* ]]
	[[ "${output}" == *"release public key"* ]]
}

@test "archives that hold another release key than the secret's are refused" {
	make_native
	run verify_packages "${NATIVE}" "${TAG}" "${COMMIT}" "MCowBQYDK2VwAyEAb3RoZXIta2V5LW90aGVyLWtleQ=="
	[ "${status}" -eq 1 ]
	[[ "${output}" == *"release public key"* ]]
}

@test "a platform that the Build did not upload is refused" {
	make_native
	rm -rf -- "${NATIVE}/cat-macos-x64"
	run verify_packages "${NATIVE}" "${TAG}" "${COMMIT}" "${KEY}"
	[ "${status}" -eq 1 ]
	[[ "${output}" == *"cat-macos-x64"* ]]
}

@test "an artifact with a file besides its four is refused" {
	make_native
	printf 'extra' > "${NATIVE}/cat-linux-x64/notes.txt"
	run verify_packages "${NATIVE}" "${TAG}" "${COMMIT}" "${KEY}"
	[ "${status}" -eq 1 ]
	[[ "${output}" == *"cat-linux-x64"* ]]
	[[ "${output}" == *"notes.txt"* ]]
}

@test "a fragment that names another commit is refused" {
	make_native
	refresh_platform linux-arm64 "fedcba987654"
	run verify_packages "${NATIVE}" "${TAG}" "${COMMIT}" "${KEY}"
	[ "${status}" -eq 1 ]
	[[ "${output}" == *"cat-release-assets-linux-arm64.json"* ]]
	[[ "${output}" == *"fedcba987654"* ]]
}

@test "a fragment whose digest differs from its archive is refused" {
	make_native
	jq '.assets[0].sha256 = ("0" * 64)' "${NATIVE}/cat-linux-x64/cat-release-assets-linux-x64.json" \
		> "${NATIVE}/fragment.json"
	mv -- "${NATIVE}/fragment.json" "${NATIVE}/cat-linux-x64/cat-release-assets-linux-x64.json"
	run verify_packages "${NATIVE}" "${TAG}" "${COMMIT}" "${KEY}"
	[ "${status}" -eq 1 ]
	[[ "${output}" == *"cat-release-assets-linux-x64.json"* ]]
}

@test "the public key derived from a signing key is one line of base64 that decodes to an Ed25519 key" {
	local private_key="${BATS_TEST_TMPDIR}/private.pem"
	openssl genpkey -algorithm ed25519 -out "${private_key}"
	CAT_RELEASE_SIGNING_KEY="$(cat "${private_key}")" run --separate-stderr derive_public_key
	[ "${status}" -eq 0 ]
	[ "${#lines[@]}" -eq 1 ]
	[[ "${output}" == MCowBQYDK2VwAyEA* ]]
	[ "$(printf '%s' "${output}" | base64 -d | wc -c | tr -d ' ')" -eq 44 ]
}

@test "an empty signing key is refused" {
	CAT_RELEASE_SIGNING_KEY="" run derive_public_key
	[ "${status}" -eq 1 ]
	[[ "${output}" == *"CAT_RELEASE_SIGNING_KEY is empty or unavailable"* ]]
}

@test "text that is not a private key is refused" {
	CAT_RELEASE_SIGNING_KEY="not a key" run derive_public_key
	[ "${status}" -eq 1 ]
	[[ "${output}" == *"holds no private key that openssl can read"* ]]
}
