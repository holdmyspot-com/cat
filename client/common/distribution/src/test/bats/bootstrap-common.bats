#!/usr/bin/env bats

# Tests the installers that both harnesses share: the single command's harness detection and its release notes, the
# downloaded per-harness bootstraps, and the functions of the shared installer library, which the tests call with
# set -eu, as the installers run them.

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
