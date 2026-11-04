#!/usr/bin/env bats

# Tests what does not depend on the harness in the entry script cat.sh: which assets it downloads and verifies, the
# platform it names them for, the options it refuses, how it runs the install of the installer in the extracted
# plugin archive, the exit status and cleanup that follow, and the generator that fills the script and its PowerShell
# peer with the release's tag and SHA-256 digests, together with the release notes' entry commands.
#
# The tests publish a fake release whose installer only records how it was run, so that they decide what cat.sh
# downloads, verifies and runs.

load bootstrap-helpers

@test "entry script downloads only the harness's plugin archive from the release's download URL" {
	simulate_posix_platform
	export PATH="${FAKE_BIN}:${PATH}"
	create_fake_release

	for harness in claude codex; do
		: > "${URL_LOG}"

		run single_command --harness "${harness}"

		[ "${status}" -eq 0 ]
		[ "$(cat "${URL_LOG}")" = "${SINGLE_COMMAND_URL}
${RELEASE_DOWNLOAD_URL}/$(plugin_name "${harness}").tar.gz" ]
	done
}

@test "entry script ignores the variables that once chose the repository and the download URL" {
	create_fake_release
	export PATH="${FAKE_BIN}:${PATH}"

	run env CAT_BOOTSTRAP_REPOSITORY=someone-else/cat CAT_RELEASE_BASE_URL=https://example.com/cat/releases \
		sh -c 'curl -fsSL "$1" | sh -s -- --harness claude' sh "${SINGLE_COMMAND_URL}"

	[ "${status}" -eq 0 ]
	[ "$(cat "${URL_LOG}")" = "${SINGLE_COMMAND_URL}
${RELEASE_DOWNLOAD_URL}/$(plugin_name claude).tar.gz" ]
}

@test "entry script runs install from the extracted plugin archive and prints the installer's result" {
	create_fake_release
	export PATH="${FAKE_BIN}:${PATH}"

	run single_command --harness claude

	[ "${status}" -eq 0 ]
	[ "${output}" = '{"result":"installed"}' ]
	expect_install_ran_for claude "${CLAUDE_CONFIG_DIR}"
}

@test "entry script exits with the installer's exit status and still deletes its temporary directory" {
	create_fake_release
	export PATH="${FAKE_BIN}:${PATH}" FAKE_INSTALL_STATUS=5

	run single_command --harness codex

	[ "${status}" -eq 5 ]
	[ "${output}" = '{"result":"installed"}' ]
	expect_install_ran_for codex "${CODEX_HOME}"
}

@test "entry script refuses a version argument before downloading or running anything" {
	create_fake_release
	export PATH="${FAKE_BIN}:${PATH}"

	for arguments in "--harness claude v1.0" "v1.0" "--harness"; do
		: > "${URL_LOG}"

		# shellcheck disable=SC2086
		run single_command ${arguments}

		[ "${status}" -eq 2 ]
		[[ "${output}" == *"takes no version"* ]]
		[ "$(cat "${URL_LOG}")" = "${SINGLE_COMMAND_URL}" ]
	done
	[ ! -e "${INSTALL_ARGUMENTS}" ]
}

@test "entry script refuses the retired updater-only, uninstall and delete-data options and says how to uninstall" {
	create_fake_release
	export PATH="${FAKE_BIN}:${PATH}"

	for option in --updater-only --uninstall --delete-data; do
		: > "${URL_LOG}"

		run single_command --harness claude "${option}"

		[ "${status}" -eq 2 ]
		[[ "${output}" == *"${option} is no longer supported"* ]]
		[[ "${output}" == *"ask your agent to uninstall CAT"* ]]
		[[ "${output}" == *"bin/installer uninstall"* ]]
		[[ "${output}" != *"cat-updater"* ]]
		[ "$(cat "${URL_LOG}")" = "${SINGLE_COMMAND_URL}" ]
	done
	[ ! -e "${INSTALL_ARGUMENTS}" ]
}

@test "entry script stops before extracting or running a plugin that does not match the release's digest" {
	create_fake_release
	printf 'tampered\n' > "${FAKE_RELEASE_DIRECTORY}/$(plugin_name claude).tar.gz"
	real_tar="$(command -v tar)"
	printf '#!/usr/bin/env sh\nprintf "tar %%s\\n" "$*" >> "${COMMAND_LOG}"\nexec "%s" "$@"\n' "${real_tar}" \
		> "${FAKE_BIN}/tar"
	chmod +x "${FAKE_BIN}/tar"
	export PATH="${FAKE_BIN}:${PATH}"

	run single_command --harness claude

	[ "${status}" -eq 1 ]
	[[ "${output}" == *"$(plugin_name claude).tar.gz does not match the SHA-256 digest"* ]]
	[ ! -s "${COMMAND_LOG}" ]
	[ ! -e "${INSTALL_ARGUMENTS}" ]
	[ -z "$(ls -A "${TMPDIR}")" ]
}

@test "entry script stops and deletes its temporary directory when the plugin download fails" {
	create_fake_release
	rm "${FAKE_RELEASE_DIRECTORY}/$(plugin_name claude).tar.gz"
	export PATH="${FAKE_BIN}:${PATH}"

	run single_command --harness claude

	[ "${status}" -ne 0 ]
	[ ! -e "${INSTALL_ARGUMENTS}" ]
	[ -z "$(ls -A "${TMPDIR}")" ]
}

@test "entry script refuses a plugin archive that holds anything beside its one top directory" {
	create_fake_release
	archive_root="${TEST_ROOT}/extra"
	mkdir -p "${archive_root}/$(plugin_name claude)" "${archive_root}/other"
	tar -czf - -C "${archive_root}" "$(plugin_name claude)" other \
		> "${FAKE_RELEASE_DIRECTORY}/$(plugin_name claude).tar.gz"
	sha256_line "${FAKE_RELEASE_DIRECTORY}/$(plugin_name claude).tar.gz" > "${TEST_ROOT}/SHA256SUMS.txt"
	sh "${COMMON_RELEASE_DIRECTORY}/generate-cat-sh.sh" "${COMMON_RELEASE_DIRECTORY}/cat.sh" v1.0 \
		"${TEST_ROOT}/SHA256SUMS.txt" "${FAKE_RELEASE_DIRECTORY}/cat.sh"
	export PATH="${FAKE_BIN}:${PATH}"

	run single_command --harness claude

	[ "${status}" -eq 1 ]
	[[ "${output}" == *"does not hold exactly the directory $(plugin_name claude)"* ]]
	[ ! -e "${INSTALL_ARGUMENTS}" ]
	[ -z "$(ls -A "${TMPDIR}")" ]
}

@test "entry script names the plugin asset of each supported operating system and processor" {
	create_fake_release
	export PATH="${FAKE_BIN}:${PATH}"
	for case in "Linux x86_64 linux-x64" "Linux amd64 linux-x64" "Linux aarch64 linux-arm64" "Linux arm64 linux-arm64" \
		"Darwin x86_64 macos-x64" "Darwin arm64 macos-arm64" "Darwin aarch64 macos-arm64" \
		"MINGW64_NT-10.0-26100 x86_64 windows-x64" "MSYS_NT-10.0-26100 x86_64 windows-x64"; do
		read -r system processor platform <<< "${case}"
		[ "${platform}" != "$(current_platform)" ] || continue
		create_fake_uname "${system}" "${processor}"

		run single_command --harness claude

		# The fake release publishes only the current platform's assets, so the script stops at the asset it looks up
		[ "${status}" -eq 1 ]
		[[ "${output}" == *"publishes no asset named cat-claude-${platform}-v1.0.tar.gz"* ]]
	done
	[ -z "$(ls -A "${TMPDIR}")" ]
}

@test "entry script refuses an unsupported operating system or processor before downloading the plugin" {
	create_fake_release
	export PATH="${FAKE_BIN}:${PATH}"
	for case in "FreeBSD x86_64" "Linux riscv64" "MINGW32_NT-10.0 i686"; do
		read -r system processor <<< "${case}"
		create_fake_uname "${system}" "${processor}"
		: > "${URL_LOG}"

		run single_command --harness claude

		[ "${status}" -eq 1 ]
		[[ "${output}" == *"Unsupported platform: ${system} ${processor}"* ]]
		[ "$(cat "${URL_LOG}")" = "${SINGLE_COMMAND_URL}" ]
	done
}

@test "entry script uses the harness that --harness names, whatever the shell" {
	simulate_posix_platform
	export PATH="${FAKE_BIN}:${PATH}" CLAUDECODE=1 CODEX_THREAD_ID=019a0000-0000-7000-8000-000000000000
	create_fake_release

	run single_command --harness codex

	[ "${status}" -eq 0 ]
	expect_install_ran_for codex "${CODEX_HOME}"
}

@test "entry script refuses before downloading the plugin when it cannot tell the harness" {
	create_fake_release
	export PATH="${FAKE_BIN}:${PATH}"

	for environment in "" "CLAUDECODE=1 CODEX_THREAD_ID=019a0000-0000-7000-8000-000000000000"; do
		: > "${URL_LOG}"

		# shellcheck disable=SC2086
		run env ${environment} sh -c 'curl -fsSL "$1" | sh -s --' sh "${SINGLE_COMMAND_URL}"

		[ "${status}" -eq 2 ]
		[[ "${output}" == *"--harness claude or --harness codex"* ]]
		[[ "${output}" == *"curl -fsSL ${SINGLE_COMMAND_URL} | sh -s -- --harness claude"* ]]
		[ "$(cat "${URL_LOG}")" = "${SINGLE_COMMAND_URL}" ]
	done
	[ ! -e "${INSTALL_ARGUMENTS}" ]
}

@test "entry script refuses an unknown harness before downloading the plugin" {
	create_fake_release
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
	create_fake_release

	grep -Fx "CAT_RELEASE_TAG='v1.0'" "${FAKE_RELEASE_DIRECTORY}/cat.sh"
	! grep -F '@CAT_' "${FAKE_RELEASE_DIRECTORY}/cat.sh" || false
	while IFS= read -r line; do
		grep -Fq "${line}" "${FAKE_RELEASE_DIRECTORY}/cat.sh"
	done < "${TEST_ROOT}/SHA256SUMS.txt"
}

@test "generator writes the PowerShell entry with the pinned tag and every digest" {
	printf '%064d  cat-claude-windows-x64-v1.0.tar.gz\n%064d  cat-codex-windows-x64-v1.0.tar.gz\n' 1 2 \
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

@test "release notes give each harness's entry command, which runs install from that release" {
	repository="${BATS_TEST_DIRNAME}/../../../../../.."
	render="$(grep -F 'release-notes.md >' "${repository}/.github/workflows/release.yml" | sed 's/^ *//')"
	[ -n "${render}" ]
	create_fake_release
	notes_directory="${TEST_ROOT}/notes"
	mkdir -p "${notes_directory}"

	(cd "${repository}" && GITHUB_REF_NAME=v1.0 RUNNER_TEMP="${notes_directory}" CAT_KEY_FINGERPRINT=0123abcd \
		sh -c "${render}")

	notes="${notes_directory}/release-notes.md"
	! grep -F '${' "${notes}" || false
	grep -F "0123abcd" "${notes}"
	grep -Fx "curl -fsSL ${SINGLE_COMMAND_URL} | sh -s -- --harness claude" "${notes}"
	grep -Fx "curl -fsSL ${SINGLE_COMMAND_URL} | sh -s -- --harness codex" "${notes}"
	grep -Fx "& ([scriptblock]::Create((irm ${RELEASE_DOWNLOAD_URL}/cat.ps1))) -Harness codex" "${notes}"
	export PATH="${FAKE_BIN}:${PATH}"

	run sh -c "$(grep -Fx "curl -fsSL ${SINGLE_COMMAND_URL} | sh -s -- --harness claude" "${notes}")"

	[ "${status}" -eq 0 ]
	expect_install_ran_for claude "${CLAUDE_CONFIG_DIR}"
}
