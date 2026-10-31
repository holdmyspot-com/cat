#!/usr/bin/env bats

# Tests the installers that both harnesses share: the single command's harness detection and its release notes, the
# downloaded per-harness bootstraps, and the shared installer library.

load bootstrap-helpers

@test "shared installer usage distinguishes download and extracted-artifact inputs" {
	run sh -c '. "$1"; cat_print_install_usage "$2"' sh "${INSTALL_DIRECTORY}/common.sh" cat-install

	[ "${status}" -eq 2 ]
	[[ "${output}" == *"Download and install CAT v1.0."* ]]
	[[ "${output}" == *"EXTRACTED_ARTIFACT_DIRECTORY"* ]]
	[[ "${output}" == *"Install an already extracted CAT artifact directory."* ]]
}

@test "platform detection names 64-bit Git Bash and MSYS on Windows windows-x64" {
	for system in MINGW64_NT-10.0-26100 MSYS_NT-10.0-26100; do
		create_fake_uname "${system}"

		run env PATH="${FAKE_BIN}:${PATH}" sh -c '. "$1"; cat_detect_platform' sh "${INSTALL_DIRECTORY}/common.sh"

		[ "${status}" -eq 0 ]
		[ "${output}" = windows-x64 ]
	done
}

@test "downloaded Codex bootstrap installs supported platforms and directs Windows users to PowerShell" {
	create_fake_engine_command codex
	create_fake_release codex > /dev/null
	cp "${INSTALL_DIRECTORY}/bootstrap.sh" "${INSTALL_DIRECTORY}/common.sh" \
		"${INSTALL_DIRECTORY}/install-codex.sh" "${FAKE_RELEASE_DIRECTORY}/"
	standalone="${TEST_ROOT}/downloaded bootstrap"
	mkdir -p "${standalone}"
	cp "${INSTALL_DIRECTORY}/bootstrap-codex.sh" "${standalone}/"

	run env PATH="${FAKE_BIN}:${PATH}" COMMAND_LOG="${COMMAND_LOG}" \
		FAKE_RELEASE_DIRECTORY="${FAKE_RELEASE_DIRECTORY}" \
		sh "${standalone}/bootstrap-codex.sh" v1.0

	case "$(uname -s)" in
		MINGW*|MSYS*)
			[ "${status}" -eq 1 ]
			[[ "${output}" == *"install CAT for Codex from PowerShell with bootstrap-codex.ps1"* ]]
			[ ! -e "${CODEX_HOME}/plugins/cat-marketplace" ]
			;;
		*)
			[ "${status}" -eq 0 ]
			[ -f "${CODEX_HOME}/plugins/cat-marketplace/.agents/plugins/marketplace.json" ]
			grep -Fx "codex plugin add cat@cat" "${COMMAND_LOG}"
			;;
	esac
}

@test "downloaded Codex uninstall bootstrap removes the registered CAT marketplace" {
	create_fake_engine_command codex
	create_fake_release codex > /dev/null
	cp "${INSTALL_DIRECTORY}/bootstrap-uninstall.sh" "${INSTALL_DIRECTORY}/common.sh" \
		"${INSTALL_DIRECTORY}/uninstall-codex.sh" "${FAKE_RELEASE_DIRECTORY}/"
	standalone="${TEST_ROOT}/downloaded uninstall bootstrap"
	mkdir -p "${standalone}" "${CODEX_HOME}/plugins/cat-marketplace"
	cp "${INSTALL_DIRECTORY}/bootstrap-uninstall-codex.sh" "${standalone}/"

	run env PATH="${FAKE_BIN}:${PATH}" COMMAND_LOG="${COMMAND_LOG}" \
		FAKE_RELEASE_DIRECTORY="${FAKE_RELEASE_DIRECTORY}" \
		sh "${standalone}/bootstrap-uninstall-codex.sh"

	[ "${status}" -eq 0 ]
	[ ! -e "${CODEX_HOME}/plugins/cat-marketplace" ]
	grep -Fx "codex plugin remove cat@cat" "${COMMAND_LOG}"
}

@test "downloaded Claude bootstrap installs and registers metadata from the release archive" {
	create_fake_engine_command claude
	create_fake_release claude > /dev/null
	cp "${INSTALL_DIRECTORY}/bootstrap.sh" "${INSTALL_DIRECTORY}/common.sh" \
		"${INSTALL_DIRECTORY}/install-claude.sh" "${FAKE_RELEASE_DIRECTORY}/"
	standalone="${TEST_ROOT}/downloaded bootstrap"
	mkdir -p "${standalone}"
	cp "${INSTALL_DIRECTORY}/bootstrap-claude.sh" "${standalone}/"

	run env PATH="${FAKE_BIN}:${PATH}" COMMAND_LOG="${COMMAND_LOG}" \
		FAKE_RELEASE_DIRECTORY="${FAKE_RELEASE_DIRECTORY}" \
		sh "${standalone}/bootstrap-claude.sh" v1.0

	[ "${status}" -eq 0 ]
	[ -f "${CLAUDE_CONFIG_DIR}/plugins/cat-marketplace/.claude-plugin/marketplace.json" ]
	grep -Fx "claude plugin install cat@cat" "${COMMAND_LOG}"
}

@test "downloaded Claude uninstall bootstrap removes the registered CAT marketplace" {
	create_fake_engine_command claude
	create_fake_release claude > /dev/null
	cp "${INSTALL_DIRECTORY}/bootstrap-uninstall.sh" "${INSTALL_DIRECTORY}/common.sh" \
		"${INSTALL_DIRECTORY}/uninstall-claude.sh" "${FAKE_RELEASE_DIRECTORY}/"
	standalone="${TEST_ROOT}/downloaded uninstall bootstrap"
	mkdir -p "${standalone}" "${CLAUDE_CONFIG_DIR}/plugins/cat-marketplace"
	cp "${INSTALL_DIRECTORY}/bootstrap-uninstall-claude.sh" "${standalone}/"

	run env PATH="${FAKE_BIN}:${PATH}" COMMAND_LOG="${COMMAND_LOG}" \
		FAKE_RELEASE_DIRECTORY="${FAKE_RELEASE_DIRECTORY}" \
		sh "${standalone}/bootstrap-uninstall-claude.sh"

	[ "${status}" -eq 0 ]
	[ ! -e "${CLAUDE_CONFIG_DIR}/plugins/cat-marketplace" ]
	grep -Fx "claude plugin uninstall cat@cat" "${COMMAND_LOG}"
}

@test "downloaded bootstraps report a failed shared-script download" {
	printf '#!/bin/sh\nexit 22\n' > "${FAKE_BIN}/curl"
	chmod +x "${FAKE_BIN}/curl"
	standalone="${TEST_ROOT}/failed downloaded bootstrap"
	mkdir -p "${standalone}"
	for script in bootstrap-codex.sh bootstrap-claude.sh bootstrap-uninstall-codex.sh bootstrap-uninstall-claude.sh; do
		cp "${INSTALL_DIRECTORY}/${script}" "${standalone}/"

		run env PATH="${FAKE_BIN}:${PATH}" sh "${standalone}/${script}"

		[ "${status}" -eq 22 ]
	done
}

@test "single command installs for Claude Code in a Claude Code shell" {
	create_fake_engine_command claude
	create_fake_engine_command codex
	create_fake_release claude > /dev/null
	publish_install_scripts
	export PATH="${FAKE_BIN}:${PATH}" CLAUDECODE=1

	run single_command v1.0

	[ "${status}" -eq 0 ]
	[ -f "${CLAUDE_CONFIG_DIR}/plugins/cat-marketplace/plugins/cat/.claude-plugin/plugin.json" ]
	grep -Fx "claude plugin install cat@cat" "${COMMAND_LOG}"
	! grep -q '^codex ' "${COMMAND_LOG}" || false
	[ ! -e "${CODEX_HOME}" ]
}

@test "single command installs for Codex in a Codex shell" {
	create_fake_engine_command claude
	create_fake_engine_command codex
	create_fake_release codex > /dev/null
	publish_install_scripts
	export PATH="${FAKE_BIN}:${PATH}" CODEX_THREAD_ID=019a0000-0000-7000-8000-000000000000

	run single_command v1.0

	case "$(uname -s)" in
		MINGW*|MSYS*)
			[ "${status}" -eq 1 ]
			[[ "${output}" == *"install CAT for Codex from PowerShell with bootstrap-codex.ps1"* ]]
			;;
		*)
			[ "${status}" -eq 0 ]
			[ -f "${CODEX_HOME}/plugins/cat-marketplace/plugins/cat/.codex-plugin/plugin.json" ]
			grep -Fx "codex plugin add cat@cat" "${COMMAND_LOG}"
			;;
	esac
	! grep -q '^claude \|install-claude.sh' "${COMMAND_LOG}" || false
	[ ! -e "${CLAUDE_CONFIG_DIR}" ]
}

@test "single command installs for the harness that --harness names, whatever the shell" {
	create_fake_engine_command claude
	create_fake_engine_command codex
	create_fake_release claude > /dev/null
	publish_install_scripts
	export PATH="${FAKE_BIN}:${PATH}" CLAUDECODE=1 CODEX_THREAD_ID=019a0000-0000-7000-8000-000000000000

	run single_command --harness claude v1.0

	[ "${status}" -eq 0 ]
	grep -Fx "claude plugin install cat@cat" "${COMMAND_LOG}"
	! grep -q '^codex ' "${COMMAND_LOG}" || false
}

@test "single command refuses before changing anything when it cannot tell the harness" {
	create_fake_engine_command claude
	create_fake_engine_command codex
	create_fake_release claude > /dev/null
	publish_install_scripts
	export PATH="${FAKE_BIN}:${PATH}"

	for environment in "" "CLAUDECODE=1 CODEX_THREAD_ID=019a0000-0000-7000-8000-000000000000"; do
		for operation in v1.0 --uninstall; do
			: > "${COMMAND_LOG}"

			# shellcheck disable=SC2086
			run env ${environment} sh -c 'curl -fsSL "$1" | sh -s -- "$2"' sh "${SINGLE_COMMAND_URL}" "${operation}"

			[ "${status}" -eq 2 ]
			[[ "${output}" == *"--harness claude"* ]]
			[[ "${output}" == *"--harness codex"* ]]
			[ "$(cat "${COMMAND_LOG}")" = "curl -fsSL ${SINGLE_COMMAND_URL}" ]
			[ ! -e "${CLAUDE_CONFIG_DIR}" ]
			[ ! -e "${CODEX_HOME}" ]
		done
	done
}

@test "single command refuses an unknown harness before changing anything" {
	create_fake_release claude > /dev/null
	publish_install_scripts
	export PATH="${FAKE_BIN}:${PATH}" CLAUDECODE=1

	run single_command --harness cursor

	[ "${status}" -eq 2 ]
	[[ "${output}" == *"--harness claude or --harness codex"* ]]
	[ "$(cat "${COMMAND_LOG}")" = "curl -fsSL ${SINGLE_COMMAND_URL}" ]
	[ ! -e "${CLAUDE_CONFIG_DIR}" ]
}

@test "release notes give the release's single commands, which install and uninstall that release" {
	repository="${BATS_TEST_DIRNAME}/../../../../../.."
	render="$(grep -F 'release-notes.md >' "${repository}/.github/workflows/release.yml" | sed 's/^ *//')"
	[ -n "${render}" ]
	create_fake_engine_command claude
	create_fake_release claude > /dev/null
	publish_install_scripts
	notes_directory="${TEST_ROOT}/notes"
	mkdir -p "${notes_directory}"

	(cd "${repository}" && GITHUB_REF_NAME=v1.0 RUNNER_TEMP="${notes_directory}" sh -c "${render}")

	notes="${notes_directory}/release-notes.md"
	! grep -F '${' "${notes}" || false
	install_command="$(grep '^curl .*/cat\.sh | sh -s -- v1\.0$' "${notes}")"
	uninstall_command="$(grep '^curl .*/cat\.sh | sh -s -- --uninstall$' "${notes}")"
	[[ "${install_command}" == "curl -fsSL https://raw.githubusercontent.com/holdmyspot-com/cat/v1.0/"* ]]
	[[ "${uninstall_command}" == "curl -fsSL https://raw.githubusercontent.com/holdmyspot-com/cat/v1.0/"* ]]
	grep -Fx "& ([scriptblock]::Create((irm https://raw.githubusercontent.com/holdmyspot-com/cat/v1.0/client/codex/\
lifecycle/src/main/resources/install/bootstrap-codex.ps1))) -Version v1.0" "${notes}"
	grep -Fx "& ([scriptblock]::Create((irm https://raw.githubusercontent.com/holdmyspot-com/cat/v1.0/client/codex/\
lifecycle/src/main/resources/install/bootstrap-codex.ps1))) -Uninstall" "${notes}"
	grep -F "cannot write a path" "${notes}"
	grep -F "outside the" "${notes}"
	export PATH="${FAKE_BIN}:${PATH}" CLAUDECODE=1

	run sh -c "${install_command}"

	[ "${status}" -eq 0 ]
	grep -Fx "curl -fsSL https://raw.githubusercontent.com/holdmyspot-com/cat/v1.0/client/common/lifecycle/src/main/\
resources/install/cat.sh" "${COMMAND_LOG}"
	grep -Fx "claude plugin install cat@cat" "${COMMAND_LOG}"

	run sh -c "${uninstall_command}"

	[ "${status}" -eq 0 ]
	grep -Fx "claude plugin uninstall cat@cat" "${COMMAND_LOG}"
	[ ! -e "${CLAUDE_CONFIG_DIR}/plugins/cat-marketplace" ]
}
