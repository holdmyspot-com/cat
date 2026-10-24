#!/usr/bin/env bats

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

@test "downloaded Codex bootstrap installs and registers metadata from the release archive" {
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

	[ "${status}" -eq 0 ]
	[ -f "${CODEX_HOME}/plugins/cat-marketplace/.agents/plugins/marketplace.json" ]
	grep -Fx "codex plugin add cat@cat" "${COMMAND_LOG}"
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
