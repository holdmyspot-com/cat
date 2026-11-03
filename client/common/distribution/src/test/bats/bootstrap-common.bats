#!/usr/bin/env bats

# Tests what both harnesses share: the release's entry script cat.sh, which the generator fills with the release's tag
# and SHA-256 digests, its release notes, and the functions of the shared installer library, which the tests call with
# set -eu, as the installers run them.
#
# The entry script's tests publish a fake release whose plugin holds installers that only record their arguments, and
# whose updater only records its arguments, so that they decide what cat.sh downloads, verifies and runs.

load bootstrap-helpers

@test "shared installer usage names the extracted-artifact input and the release's entry command" {
	run sh -c '. "$1"; cat_print_install_usage "$2" harness-a' sh "${INSTALL_DIRECTORY}/common.sh" cat-install

	[ "${status}" -eq 2 ]
	[[ "${output}" == *"cat-install EXTRACTED_ARTIFACT_DIRECTORY"* ]]
	[[ "${output}" == *"Install an already extracted CAT artifact directory."* ]]
	[[ "${output}" == *"curl -fsSL https://github.com/holdmyspot-com/cat/releases/download/v1.0/cat.sh | sh -s -- \
--harness harness-a"* ]]
}

@test "shared installer refuses an input that is not an extracted artifact directory" {
	run sh -c 'set -eu; . "$1"; cat_resolve_artifact_directory "$2"' sh "${INSTALL_DIRECTORY}/common.sh" v1.0

	[ "${status}" -eq 2 ]
	[[ "${output}" == *"Expected an extracted CAT artifact directory"* ]]
	[ ! -s "${URL_LOG}" ]
}

@test "data migration runs each pending migration in registry order and then records CAT's version" {
	data_directory="${TEST_ROOT}/data"
	artifact="$(create_test_artifact claude)"
	mkdir -p "${data_directory}"
	printf '0.9\n' > "${data_directory}/migration-version"
	migration_record='printf "%s\\n" "${CAT_MIGRATION_FROM_VERSION}->${CAT_MIGRATION_TO_VERSION}" >> "${COMMAND_LOG}"'
	add_test_migration "${artifact}" "0.10" "${migration_record}"
	add_test_migration "${artifact}" "1.0" "${migration_record}"

	run sh -c 'set -eu; . "$1"; cat_migrate_data "$2" "$3"' sh "${INSTALL_DIRECTORY}/common.sh" "${data_directory}" \
		"${artifact}/install/migrations"

	[ "${status}" -eq 0 ]
	[ "$(cat "${COMMAND_LOG}")" = "0.9->0.10
0.9->1.0" ]
	[ "$(cat "${data_directory}/migration-version")" = "1.0" ]
}

@test "data migration that fails keeps the recorded version and stops with the migration's exit status" {
	data_directory="${TEST_ROOT}/data"
	artifact="$(create_test_artifact claude)"
	mkdir -p "${data_directory}"
	printf '0.9\n' > "${data_directory}/migration-version"
	add_test_migration "${artifact}" "1.0" 'exit 7'

	run sh -c 'set -eu; . "$1"; cat_migrate_data "$2" "$3"' sh "${INSTALL_DIRECTORY}/common.sh" "${data_directory}" \
		"${artifact}/install/migrations"

	[ "${status}" -eq 7 ]
	[ "$(cat "${data_directory}/migration-version")" = "0.9" ]
}

@test "installing Codex agents again replaces the agents CAT recorded and leaves the user's own agents alone" {
	artifact="$(create_test_artifact codex)"
	data_directory="${CODEX_HOME}/plugins/data/cat-cat"
	mkdir -p "${CODEX_HOME}/agents"
	printf 'user agent\n' > "${CODEX_HOME}/agents/reviewer.toml"
	install_agents='set -eu; . "$1"; cat_install_codex_agents "$2" "$3" "$4"'
	run sh -c "${install_agents}" sh "${INSTALL_DIRECTORY}/common.sh" "${artifact}" "${CODEX_HOME}" "${data_directory}"
	[ "${status}" -eq 0 ]
	printf 'old CAT agent\n' > "${CODEX_HOME}/agents/cat-reviewer.toml"

	run sh -c "${install_agents}" sh "${INSTALL_DIRECTORY}/common.sh" "${artifact}" "${CODEX_HOME}" "${data_directory}"

	[ "${status}" -eq 0 ]
	grep -F "Review evidence without editing." "${CODEX_HOME}/agents/cat-reviewer.toml"
	[ "$(cat "${CODEX_HOME}/agents/reviewer.toml")" = "user agent" ]
}

@test "removing Codex agents deletes only the agents and record that CAT wrote" {
	data_directory="${CODEX_HOME}/plugins/data/cat-cat"
	mkdir -p "${CODEX_HOME}/agents" "${data_directory}"
	printf 'cat agent\n' > "${CODEX_HOME}/agents/cat-reviewer.toml"
	printf 'user agent\n' > "${CODEX_HOME}/agents/reviewer.toml"
	printf 'cat-reviewer.toml\n' > "${data_directory}/codex-agent-files"

	run sh -c 'set -eu; . "$1"; cat_remove_codex_agents "$2" "$3"' sh "${INSTALL_DIRECTORY}/common.sh" \
		"${CODEX_HOME}" "${data_directory}"

	[ "${status}" -eq 0 ]
	[ ! -e "${CODEX_HOME}/agents/cat-reviewer.toml" ]
	[ -f "${CODEX_HOME}/agents/reviewer.toml" ]
	[ ! -e "${data_directory}/codex-agent-files" ]
}

@test "removing the downloaded bundles keeps the rest of CAT's user directory" {
	mkdir -p "${HOME}/.cat/bundles/cat-codex-v1.0-linux-x64/bin" "${HOME}/.cat/rules"
	printf 'rule\n' > "${HOME}/.cat/rules/user.md"

	run sh -c 'set -eu; . "$1"; cat_remove_downloaded_bundles "$2"' sh "${INSTALL_DIRECTORY}/common.sh" "${HOME}"

	[ "${status}" -eq 0 ]
	[ ! -e "${HOME}/.cat/bundles" ]
	[ -f "${HOME}/.cat/rules/user.md" ]
}

@test "entry script downloads only this release's assets from the release's download URL" {
	create_fake_release claude recording > /dev/null
	export PATH="${FAKE_BIN}:${PATH}"

	run single_command --harness claude
	[ "${status}" -eq 0 ]
	run single_command --harness claude --updater-only
	[ "${status}" -eq 0 ]
	run single_command --harness claude --uninstall
	[ "${status}" -eq 0 ]

	[ -s "${URL_LOG}" ]
	while IFS= read -r url; do
		[[ "${url}" == "${RELEASE_DOWNLOAD_URL}/"* ]]
		[[ "${url}" != *HEAD* ]]
		[[ "${url}" != *raw.githubusercontent.com* ]]
		[[ "${url}" != *api.github.com* ]]
	done < "${URL_LOG}"
	grep -Fx "${RELEASE_DOWNLOAD_URL}/cat-claude-v1.0-$(current_platform).tar.gz" "${URL_LOG}"
	grep -Fx "${RELEASE_DOWNLOAD_URL}/cat-updater-v1.0-$(current_platform).tar.gz" "${URL_LOG}"
}

@test "entry script ignores the variables that once chose the repository and the download URL" {
	create_fake_release claude recording > /dev/null
	export PATH="${FAKE_BIN}:${PATH}"
	run single_command --harness claude
	[ "${status}" -eq 0 ]
	expected_urls="$(cat "${URL_LOG}")"
	: > "${URL_LOG}"

	run env CAT_BOOTSTRAP_REPOSITORY=someone-else/cat CAT_RELEASE_BASE_URL=https://example.com/cat/releases \
		sh -c 'curl -fsSL "$1" | sh -s -- --harness claude' sh "${SINGLE_COMMAND_URL}"

	[ "${status}" -eq 0 ]
	[ "$(cat "${URL_LOG}")" = "${expected_urls}" ]
}

@test "entry script refuses a version argument before downloading or changing anything" {
	create_fake_release claude recording > /dev/null
	export PATH="${FAKE_BIN}:${PATH}"

	for arguments in "--harness claude v1.0" "v1.0" "--harness claude --uninstall v1.1" "--harness claude --delete-data"; do
		: > "${URL_LOG}"

		# shellcheck disable=SC2086
		run single_command ${arguments}

		[ "${status}" -eq 2 ]
		[[ "${output}" == *"takes no version"* ]]
		[ "$(cat "${URL_LOG}")" = "${SINGLE_COMMAND_URL}" ]
	done
	! grep -q '^install-\|^uninstall-\|^cat-updater ' "${COMMAND_LOG}" || false
	[ ! -e "$(updater_data_directory)" ]
}

@test "entry script installs the extracted plugin, then installs the updater and sets it up for the harness's home" {
	create_fake_release claude recording > /dev/null
	export PATH="${FAKE_BIN}:${PATH}"
	data_directory="$(updater_data_directory)"

	run single_command --harness claude

	[ "${status}" -eq 0 ]
	[[ "${output}" == *'{"result":"set_up"}'* ]]
	run grep -E '^(install-|uninstall-|cat-updater )' "${COMMAND_LOG}"
	[ "${#lines[@]}" -eq 2 ]
	[[ "${lines[0]}" == "install-claude.sh ${TMPDIR}/cat."*"/cat-claude-v1.0-$(current_platform)" ]]
	[ "${lines[1]}" = "cat-updater setup --harness claude --home $(native_path "${CLAUDE_CONFIG_DIR}")" ]
	[ "$(cat "${data_directory}/1.0/marker")" = "release updater" ]
	[ -z "$(ls -A "${TMPDIR}")" ]
	[ "$(ls -A "${data_directory}")" = "1.0" ]
}

@test "entry script --updater-only replaces the updater's version directory and runs no installer" {
	create_fake_release codex recording > /dev/null
	export PATH="${FAKE_BIN}:${PATH}"
	data_directory="$(updater_data_directory)"
	mkdir -p "${data_directory}/1.0"
	printf 'old updater\n' > "${data_directory}/1.0/marker"
	printf '1.0\n' > "${data_directory}/current"

	run single_command --harness codex --updater-only

	[ "${status}" -eq 0 ]
	[ "$(grep -E '^(install-|uninstall-|cat-updater )' "${COMMAND_LOG}")" = \
		"cat-updater setup --harness codex --home $(native_path "${CODEX_HOME}")" ]
	! grep -qF "cat-codex-" "${URL_LOG}" || false
	[ "$(cat "${data_directory}/1.0/marker")" = "release updater" ]
	[ "$(cat "${data_directory}/current")" = "1.0" ]
	[ -z "$(find "${data_directory}" -maxdepth 1 -name '.cat-staging-*')" ]
}

@test "entry script --uninstall runs the release's uninstaller and leaves the updater in place" {
	create_fake_release codex recording > /dev/null
	export PATH="${FAKE_BIN}:${PATH}"
	data_directory="$(updater_data_directory)"
	mkdir -p "${data_directory}/1.0"
	printf 'installed updater\n' > "${data_directory}/1.0/marker"

	run single_command --harness codex --uninstall --delete-data

	[ "${status}" -eq 0 ]
	[ "$(grep -E '^(install-|uninstall-|cat-updater )' "${COMMAND_LOG}")" = "uninstall-codex.sh --delete-data" ]
	! grep -qF "cat-updater-" "${URL_LOG}" || false
	[ "$(cat "${data_directory}/1.0/marker")" = "installed updater" ]
}

@test "entry script stops before extracting or installing a plugin that does not match the release's digest" {
	archive="$(create_fake_release claude recording)"
	printf 'tampered\n' > "${FAKE_RELEASE_DIRECTORY}/${archive}"
	real_tar="$(command -v tar)"
	printf '#!/usr/bin/env sh\nprintf "tar %%s\\n" "$*" >> "${COMMAND_LOG}"\nexec "%s" "$@"\n' "${real_tar}" \
		> "${FAKE_BIN}/tar"
	chmod +x "${FAKE_BIN}/tar"
	export PATH="${FAKE_BIN}:${PATH}"

	run single_command --harness claude

	[ "${status}" -eq 1 ]
	[[ "${output}" == *"does not match the SHA-256 digest"* ]]
	! grep -q '^tar \|^install-\|^cat-updater ' "${COMMAND_LOG}" || false
	! grep -qF "cat-updater-" "${URL_LOG}" || false
	[ -z "$(ls -A "${TMPDIR}")" ]
}

@test "entry script stops before setup and keeps the installed updater when the updater does not match its digest" {
	create_fake_release claude recording > /dev/null
	printf 'tampered\n' > "${FAKE_RELEASE_DIRECTORY}/cat-updater-v1.0-$(current_platform).tar.gz"
	export PATH="${FAKE_BIN}:${PATH}"
	data_directory="$(updater_data_directory)"
	mkdir -p "${data_directory}/1.0/bin"
	printf 'installed updater\n' > "${data_directory}/1.0/marker"
	printf '1.0\n' > "${data_directory}/current"
	data_before="$(tree_digest "${data_directory}")"

	for mode in "" --updater-only; do
		# shellcheck disable=SC2086
		run single_command --harness claude ${mode}

		[ "${status}" -eq 1 ]
		[[ "${output}" == *"cat-updater-v1.0-$(current_platform).tar.gz does not match the SHA-256 digest"* ]]
		! grep -q '^cat-updater ' "${COMMAND_LOG}" || false
		[ "$(tree_digest "${data_directory}")" = "${data_before}" ]
	done
}

@test "entry script names the windows-x64 assets on 64-bit Git Bash and MSYS" {
	[ "$(current_platform)" != windows-x64 ] || skip "the other tests install the windows-x64 assets on this platform"
	create_fake_release claude recording > /dev/null
	export PATH="${FAKE_BIN}:${PATH}"
	for system in MINGW64_NT-10.0-26100 MSYS_NT-10.0-26100; do
		create_fake_uname "${system}"

		run single_command --harness claude

		# The fake release publishes no Windows assets, so the script stops at the first one it looks up
		[ "${status}" -eq 1 ]
		[[ "${output}" == *"publishes no asset named cat-claude-v1.0-windows-x64.tar.gz"* ]]
	done
}

@test "entry script installs for Claude Code in a Claude Code shell" {
	create_fake_release claude recording > /dev/null
	export PATH="${FAKE_BIN}:${PATH}" CLAUDECODE=1

	run single_command

	[ "${status}" -eq 0 ]
	grep -q '^install-claude.sh ' "${COMMAND_LOG}"
	grep -Fx "cat-updater setup --harness claude --home $(native_path "${CLAUDE_CONFIG_DIR}")" "${COMMAND_LOG}"
}

@test "entry script installs for Codex in a Codex shell" {
	create_fake_release codex recording > /dev/null
	export PATH="${FAKE_BIN}:${PATH}" CODEX_THREAD_ID=019a0000-0000-7000-8000-000000000000

	run single_command

	[ "${status}" -eq 0 ]
	grep -q '^install-codex.sh ' "${COMMAND_LOG}"
	grep -Fx "cat-updater setup --harness codex --home $(native_path "${CODEX_HOME}")" "${COMMAND_LOG}"
}

@test "entry script installs for the harness that --harness names, whatever the shell" {
	create_fake_release claude recording > /dev/null
	export PATH="${FAKE_BIN}:${PATH}" CLAUDECODE=1 CODEX_THREAD_ID=019a0000-0000-7000-8000-000000000000

	run single_command --harness claude

	[ "${status}" -eq 0 ]
	grep -q '^install-claude.sh ' "${COMMAND_LOG}"
}

@test "entry script refuses before downloading anything when it cannot tell the harness" {
	create_fake_release claude recording > /dev/null
	export PATH="${FAKE_BIN}:${PATH}"

	for environment in "" "CLAUDECODE=1 CODEX_THREAD_ID=019a0000-0000-7000-8000-000000000000"; do
		for operation in "" --uninstall --updater-only; do
			: > "${URL_LOG}"

			# shellcheck disable=SC2086
			run env ${environment} sh -c 'curl -fsSL "$1" | sh -s -- $2' sh "${SINGLE_COMMAND_URL}" "${operation}"

			[ "${status}" -eq 2 ]
			[[ "${output}" == *"--harness claude or --harness codex"* ]]
			[[ "${output}" == *"curl -fsSL ${SINGLE_COMMAND_URL} | sh -s -- --harness claude"* ]]
			[ "$(cat "${URL_LOG}")" = "${SINGLE_COMMAND_URL}" ]
		done
	done
	! grep -q '^install-\|^uninstall-\|^cat-updater ' "${COMMAND_LOG}" || false
}

@test "entry script refuses an unknown harness before downloading anything" {
	create_fake_release claude recording > /dev/null
	export PATH="${FAKE_BIN}:${PATH}" CLAUDECODE=1

	run single_command --harness cursor

	[ "${status}" -eq 2 ]
	[[ "${output}" == *"--harness claude or --harness codex"* ]]
	[ "$(cat "${URL_LOG}")" = "${SINGLE_COMMAND_URL}" ]
}

@test "generator refuses a release tag that is not v and two or three numbers, without writing the script" {
	printf '%064d  cat-harness-a-v1.0-linux-x64.tar.gz\n' 0 > "${TEST_ROOT}/SHA256SUMS.txt"

	for tag in v1 1.0 v1.0-rc1 v1.0.0.1 'v1.0/../HEAD' V1.0 "v1.0
v2.0" ''; do
		run sh "${COMMON_RELEASE_DIRECTORY}/generate-cat-sh.sh" "${COMMON_RELEASE_DIRECTORY}/cat.sh" "${tag}" \
			"${TEST_ROOT}/SHA256SUMS.txt" "${TEST_ROOT}/cat.sh"

		[ "${status}" -eq 1 ]
		[[ "${output}" == *"release tag must look like v1.0 or v1.0.1"* ]]
		[ ! -e "${TEST_ROOT}/cat.sh" ]
	done
}

@test "generator refuses a malformed SHA-256 line, without writing the script" {
	for line in "$(printf '%063d' 0)  cat.tar.gz" "$(printf '%064d' 0) cat.tar.gz" "$(printf 'A%063d' 0)  cat.tar.gz" \
		"$(printf '%064d' 0)  cat'.tar.gz" "$(printf '%064d' 0)  *cat.tar.gz" "$(printf '%064d' 0)  ../cat.tar.gz" \
		"$(printf '%064d' 0)  "; do
		printf '%064d  cat-harness-a-v1.0-linux-x64.tar.gz\n%s\n' 0 "${line}" > "${TEST_ROOT}/SHA256SUMS.txt"

		run sh "${COMMON_RELEASE_DIRECTORY}/generate-cat-sh.sh" "${COMMON_RELEASE_DIRECTORY}/cat.sh" v1.0 \
			"${TEST_ROOT}/SHA256SUMS.txt" "${TEST_ROOT}/cat.sh"

		[ "${status}" -eq 1 ]
		[[ "${output}" == *"malformed line"* ]]
		[ ! -e "${TEST_ROOT}/cat.sh" ]
	done
}

@test "generator fills the release's tag and every SHA-256 line into the entry script" {
	create_fake_release claude recording > /dev/null

	grep -Fx "CAT_RELEASE_TAG='v1.0'" "${FAKE_RELEASE_DIRECTORY}/cat.sh"
	! grep -F '@CAT_' "${FAKE_RELEASE_DIRECTORY}/cat.sh" || false
	while IFS= read -r line; do
		grep -Fq "${line}" "${FAKE_RELEASE_DIRECTORY}/cat.sh"
	done < "${TEST_ROOT}/SHA256SUMS.txt"
}

@test "generator writes the PowerShell entry with the pinned tag and every digest" {
	printf '%064d  cat-harness-a-v1.0-windows-x64.tar.gz\n%064d  cat-updater-v1.0-windows-x64.tar.gz\n' 1 2 \
		> "${TEST_ROOT}/SHA256SUMS.txt"

	run sh "${COMMON_RELEASE_DIRECTORY}/generate-cat-sh.sh" "${COMMON_RELEASE_DIRECTORY}/cat.ps1" v1.0 \
		"${TEST_ROOT}/SHA256SUMS.txt" "${TEST_ROOT}/cat.ps1"

	[ "${status}" -eq 0 ]
	grep -Fx "\$CatReleaseTag = 'v1.0'" "${TEST_ROOT}/cat.ps1"
	while IFS= read -r line; do
		grep -Fx "${line}" "${TEST_ROOT}/cat.ps1"
	done < "${TEST_ROOT}/SHA256SUMS.txt"
	! grep -E 'raw.githubusercontent.com|HEAD|api.github.com|@CAT_' "${TEST_ROOT}/cat.ps1" || false
}

@test "release notes give the release's entry commands, which download from that release" {
	repository="${BATS_TEST_DIRNAME}/../../../../../.."
	render="$(grep -F 'release-notes.md >' "${repository}/.github/workflows/release.yml" | sed 's/^ *//')"
	[ -n "${render}" ]
	create_fake_release claude recording > /dev/null
	notes_directory="${TEST_ROOT}/notes"
	mkdir -p "${notes_directory}"

	(cd "${repository}" && GITHUB_REF_NAME=v1.0 RUNNER_TEMP="${notes_directory}" CAT_KEY_FINGERPRINT=0123abcd \
		sh -c "${render}")

	notes="${notes_directory}/release-notes.md"
	! grep -F '${' "${notes}" || false
	grep -F "0123abcd" "${notes}"
	for operation in "" " --updater-only" " --uninstall"; do
		grep -Fx "curl -fsSL ${SINGLE_COMMAND_URL} | sh -s -- --harness claude${operation}" "${notes}"
		grep -Fx "curl -fsSL ${SINGLE_COMMAND_URL} | sh -s -- --harness codex${operation}" "${notes}"
	done
	grep -Fx "& ([scriptblock]::Create((irm ${RELEASE_DOWNLOAD_URL}/cat.ps1))) -Harness codex" "${notes}"
	grep -Fx "& ([scriptblock]::Create((irm ${RELEASE_DOWNLOAD_URL}/cat.ps1))) -Harness codex -Uninstall" "${notes}"
	grep -F "cannot write a required path" "${notes}"
	grep -F "outside the" "${notes}"
	export PATH="${FAKE_BIN}:${PATH}"

	run sh -c "$(grep -Fx "curl -fsSL ${SINGLE_COMMAND_URL} | sh -s -- --harness claude" "${notes}")"

	[ "${status}" -eq 0 ]
	grep -q '^install-claude.sh ' "${COMMAND_LOG}"
}
