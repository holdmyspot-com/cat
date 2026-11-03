#!/usr/bin/env bats

# Tests Codex plugin installation and removal, directly and through the release's entry script, and generated hook
# behavior.

load bootstrap-helpers

@test "Codex installer reports a missing shared library" {
	standalone_directory="${TEST_ROOT}/standalone Codex installer"
	mkdir -p "${standalone_directory}"
	cp "${INSTALL_DIRECTORY}/install-codex.sh" "${standalone_directory}/install-codex.sh"

	run sh "${standalone_directory}/install-codex.sh"

	[ "${status}" -eq 1 ]
	[[ "${output}" == *"CAT installer library not found"* ]]
}

@test "Codex installer on Windows names the PowerShell installer before changing anything" {
	create_fake_engine_command codex
	create_fake_uname MINGW64_NT-10.0-26100
	artifact="$(create_test_artifact codex)"

	run env PATH="${FAKE_BIN}:${PATH}" COMMAND_LOG="${COMMAND_LOG}" CODEX_HOME="${CODEX_HOME}" \
		sh "${INSTALL_DIRECTORY}/install-codex.sh" "${artifact}"

	[ "${status}" -eq 1 ]
	[[ "${output}" == *"bootstrap-codex.ps1"* ]]
	[ ! -e "${CODEX_HOME}/plugins" ]
	[ ! -s "${COMMAND_LOG}" ]
}

@test "Codex installs an extracted artifact through an artifact-local marketplace" {
	create_fake_engine_command codex
	artifact="$(create_test_artifact codex)"

	run env PATH="${FAKE_BIN}:${PATH}" COMMAND_LOG="${COMMAND_LOG}" CODEX_HOME="${CODEX_HOME}" \
		sh "${INSTALL_DIRECTORY}/install-codex.sh" "${artifact}"

	[ "${status}" -eq 0 ]
	[ -f "${CODEX_HOME}/plugins/cat-marketplace/.agents/plugins/marketplace.json" ]
	cmp "${INSTALL_DIRECTORY}/codex-marketplace.json" \
		"${CODEX_HOME}/plugins/cat-marketplace/.agents/plugins/marketplace.json"
	[ -f "${CODEX_HOME}/plugins/cat-marketplace/plugins/cat/.codex-plugin/plugin.json" ]
	[ -f "${CODEX_HOME}/agents/cat-reviewer.toml" ]
	grep -F "Review evidence without editing." "${CODEX_HOME}/agents/cat-reviewer.toml"
	[ "$(cat "${CODEX_HOME}/plugins/data/cat-cat/codex-agent-files")" = "cat-reviewer.toml" ]
	grep -F "codex plugin marketplace add ${CODEX_HOME}/plugins/cat-marketplace" "${COMMAND_LOG}"
	grep -F "codex plugin add cat@cat" "${COMMAND_LOG}"
	grep -F "plugin-info" "${COMMAND_LOG}"
	[ "$(cat "${CODEX_HOME}/plugins/data/cat-cat/migration-version")" = "1.0" ]
}

@test "Codex installation runs each pending data migration before replacing the marketplace" {
	create_fake_engine_command codex
	artifact="$(create_test_artifact codex)"
	data_directory="${TEST_ROOT}/codex data"
	mkdir -p "${data_directory}"
	printf '0.9\n' > "${data_directory}/migration-version"
	migration_record='printf "%s\\n" "${CAT_MIGRATION_FROM_VERSION}->${CAT_MIGRATION_TO_VERSION}" >> "${COMMAND_LOG}"'
	add_test_migration "${artifact}" "0.10" "${migration_record}"
	add_test_migration "${artifact}" "1.0" "${migration_record}"

	run env PATH="${FAKE_BIN}:${PATH}" COMMAND_LOG="${COMMAND_LOG}" CODEX_HOME="${CODEX_HOME}" \
		CAT_CODEX_DATA_DIR="${data_directory}" sh "${INSTALL_DIRECTORY}/install-codex.sh" "${artifact}"

	[ "${status}" -eq 0 ]
	[ "$(sed -n '1p' "${COMMAND_LOG}")" = "0.9->0.10" ]
	[ "$(sed -n '2p' "${COMMAND_LOG}")" = "0.9->1.0" ]
	grep -F "codex plugin marketplace add ${CODEX_HOME}/plugins/cat-marketplace" "${COMMAND_LOG}"
}

@test "Codex installation preserves the recorded version when a data migration fails" {
	create_fake_engine_command codex
	artifact="$(create_test_artifact codex)"
	data_directory="${TEST_ROOT}/codex data"
	mkdir -p "${data_directory}"
	printf '0.9\n' > "${data_directory}/migration-version"
	add_test_migration "${artifact}" "1.0" 'exit 7'

	run env PATH="${FAKE_BIN}:${PATH}" COMMAND_LOG="${COMMAND_LOG}" CODEX_HOME="${CODEX_HOME}" \
		CAT_CODEX_DATA_DIR="${data_directory}" sh "${INSTALL_DIRECTORY}/install-codex.sh" "${artifact}"

	[ "${status}" -eq 7 ]
	[ ! -e "${CODEX_HOME}/plugins/cat-marketplace" ]
	[ ! -s "${COMMAND_LOG}" ]
}

@test "Codex uninstall removes CAT's agents, marketplace and downloaded bundles through codex plugin remove and keeps CAT's data" {
	create_fake_engine_command codex
	mkdir -p "${CODEX_HOME}/plugins/cat-marketplace" "${CODEX_HOME}/plugins/data/cat-cat" "${CODEX_HOME}/agents" \
		"${HOME}/.cat/bundles/cat-claude-v1.0-linux-x64/bin"
	printf 'cat agent\n' > "${CODEX_HOME}/agents/cat-reviewer.toml"
	printf 'cat-reviewer.toml\n' > "${CODEX_HOME}/plugins/data/cat-cat/codex-agent-files"
	printf '0.9\n' > "${CODEX_HOME}/plugins/data/cat-cat/migration-version"

	run env PATH="${FAKE_BIN}:${PATH}" COMMAND_LOG="${COMMAND_LOG}" CODEX_HOME="${CODEX_HOME}" \
		sh "${INSTALL_DIRECTORY}/uninstall-codex.sh"

	[ "${status}" -eq 0 ]
	[ "$(sed -n '1p' "${COMMAND_LOG}")" = "codex plugin remove cat@cat" ]
	[ ! -e "${CODEX_HOME}/agents/cat-reviewer.toml" ]
	[ "$(cat "${CODEX_HOME}/plugins/data/cat-cat/migration-version")" = "0.9" ]
	[ ! -e "${CODEX_HOME}/plugins/cat-marketplace" ]
	[ ! -e "${HOME}/.cat/bundles" ]
}

@test "Codex uninstall deletes CAT's data only when asked to" {
	create_fake_engine_command codex
	mkdir -p "${CODEX_HOME}/plugins/cat-marketplace" "${CODEX_HOME}/plugins/data/cat-cat"
	printf '0.9\n' > "${CODEX_HOME}/plugins/data/cat-cat/migration-version"

	run env PATH="${FAKE_BIN}:${PATH}" COMMAND_LOG="${COMMAND_LOG}" CODEX_HOME="${CODEX_HOME}" \
		sh "${INSTALL_DIRECTORY}/uninstall-codex.sh" --delete-data

	[ "${status}" -eq 0 ]
	[ ! -e "${CODEX_HOME}/plugins/data/cat-cat" ]
	[ ! -e "${CODEX_HOME}/plugins/cat-marketplace" ]
}

@test "Codex uninstall rejects an unknown argument before removing anything" {
	create_fake_engine_command codex
	mkdir -p "${CODEX_HOME}/plugins/cat-marketplace" "${CODEX_HOME}/plugins/data/cat-cat"

	run env PATH="${FAKE_BIN}:${PATH}" COMMAND_LOG="${COMMAND_LOG}" CODEX_HOME="${CODEX_HOME}" \
		sh "${INSTALL_DIRECTORY}/uninstall-codex.sh" --delete

	[ "${status}" -eq 2 ]
	[[ "${output}" == *"Usage:"*"[--delete-data]"* ]]
	[ -d "${CODEX_HOME}/plugins/data/cat-cat" ]
	[ -d "${CODEX_HOME}/plugins/cat-marketplace" ]
	[ ! -s "${COMMAND_LOG}" ]
}

@test "entry script passes --delete-data to the release's Codex uninstaller" {
	create_fake_engine_command codex
	create_fake_release codex shipped > /dev/null
	mkdir -p "${CODEX_HOME}/plugins/cat-marketplace" "${CODEX_HOME}/plugins/data/cat-cat"
	printf '0.9\n' > "${CODEX_HOME}/plugins/data/cat-cat/migration-version"
	export PATH="${FAKE_BIN}:${PATH}"

	run single_command --harness codex --uninstall --delete-data

	[ "${status}" -eq 0 ]
	grep -Fx "codex plugin remove cat@cat" "${COMMAND_LOG}"
	[ ! -e "${CODEX_HOME}/plugins/data/cat-cat" ]
	[ ! -e "${CODEX_HOME}/plugins/cat-marketplace" ]
}

@test "entry script registers Codex using marketplace metadata from the verified archive" {
	case "$(uname -s)" in
		MINGW*|MSYS*) skip "CAT installs for Codex on Windows from PowerShell" ;;
	esac
	create_fake_engine_command codex
	create_fake_release codex shipped > /dev/null
	export PATH="${FAKE_BIN}:${PATH}"

	run single_command --harness codex

	[ "${status}" -eq 0 ]
	cmp "${CODEX_INSTALL_DIRECTORY}/codex-marketplace.json" \
		"${CODEX_HOME}/plugins/cat-marketplace/.agents/plugins/marketplace.json"
	grep -Fx "codex plugin marketplace add ${CODEX_HOME}/plugins/cat-marketplace" "${COMMAND_LOG}"
	grep -Fx "codex plugin add cat@cat" "${COMMAND_LOG}"
	[ -f "${CODEX_HOME}/plugins/cat-marketplace/plugins/cat/.codex-plugin/plugin.json" ]
	grep -Fx "cat-updater setup --harness codex --home ${CODEX_HOME}" "${COMMAND_LOG}"
}

@test "generated Codex SessionStart launcher injects the packaged routing and the project's rules" {
	project="${TEST_ROOT}/generated session project"
	mkdir -p "${project}/.cat/rules/common"
	printf 'project session rule\n' > "${project}/.cat/rules/common/session.md"
	runtime="${GENERATED_RUNTIME_DIRECTORY}/codex"

	run env PATH="${FAKE_BIN}:${PATH}" PLUGIN_ROOT="${runtime}" sh -c 'printf "%s\\n" "$1" | "$2"' sh \
		"{\"cwd\":\"${project}\",\"session_id\":\"123e4567-e89b-12d3-a456-426614174000\"}" \
		"${runtime}/hooks/session-start.sh"

	[ "${status}" -eq 0 ]
	[[ "${output}" == *'"hookSpecificOutput"'* ]]
	context_file=$(printf '%s\n' "${output}" | sed -n 's/.*rules for this session are in `\([^`]*\)`.*/\1/p')
	grep -F "CAT Rule Routing" "${context_file}"
	grep -F "project session rule" "${context_file}"
}

@test "generated Codex SubagentStart launcher injects the project rule addressed to the subagent" {
	project="${TEST_ROOT}/generated subagent project"
	mkdir -p "${project}/.cat/rules/codex"
	printf '%s\n' '---' 'agents: ["cat:reviewer"]' '---' 'reviewer rule' > "${project}/.cat/rules/codex/reviewer.md"
	runtime="${GENERATED_RUNTIME_DIRECTORY}/codex"

	run env PATH="${FAKE_BIN}:${PATH}" PLUGIN_ROOT="${runtime}" sh -c 'printf "%s\\n" "$1" | "$2"' sh \
		"{\"cwd\":\"${project}\",\"agent_type\":\"cat:reviewer\",\"session_id\":\"123e4567-e89b-12d3-a456-426614174000\"}" \
		"${runtime}/hooks/subagent-start.sh"

	[ "${status}" -eq 0 ]
	[[ "${output}" == *'"hookEventName":"SubagentStart"'* ]]
	[[ "${output}" == *"reviewer rule"* ]]
}

@test "single command changes nothing and gives the command to run outside the sandbox when config.toml is read-only" {
	create_fake_engine_command codex
	create_fake_release codex shipped > /dev/null
	export PATH="${FAKE_BIN}:${PATH}" CODEX_THREAD_ID=019a0000-0000-7000-8000-000000000000
	mkdir -p "${CODEX_HOME}"
	printf '[features]\nplugins = true\n' > "${CODEX_HOME}/config.toml"
	make_read_only "${CODEX_HOME}/config.toml"
	install_command="curl -fsSL ${SINGLE_COMMAND_URL} | CODEX_HOME='${CODEX_HOME}' sh -s -- --harness codex"
	uninstall_command="curl -fsSL ${SINGLE_COMMAND_URL} | CODEX_HOME='${CODEX_HOME}' sh -s -- --harness codex"

	expect_unchanged_by_single_command codex "${CODEX_HOME}/config.toml" "${install_command}"

	chmod u+w "${CODEX_HOME}/config.toml"
	run single_command
	[ "${status}" -eq 0 ]
	[ -f "${CODEX_HOME}/agents/cat-reviewer.toml" ]
	make_read_only "${CODEX_HOME}/config.toml"

	expect_unchanged_by_single_command codex "${CODEX_HOME}/config.toml" "${install_command}"
	expect_unchanged_by_single_command codex "${CODEX_HOME}/config.toml" "${uninstall_command} --uninstall --delete-data" \
		--uninstall --delete-data
}

@test "single command changes nothing when the directory that holds Codex's marketplace is read-only" {
	create_fake_engine_command codex
	create_fake_release codex shipped > /dev/null
	export PATH="${FAKE_BIN}:${PATH}" CODEX_THREAD_ID=019a0000-0000-7000-8000-000000000000
	install_command="curl -fsSL ${SINGLE_COMMAND_URL} | CODEX_HOME='${CODEX_HOME}' sh -s -- --harness codex"
	uninstall_command="curl -fsSL ${SINGLE_COMMAND_URL} | CODEX_HOME='${CODEX_HOME}' sh -s -- --harness codex"
	mkdir -p "${CODEX_HOME}/plugins"
	make_read_only "${CODEX_HOME}/plugins"

	expect_unchanged_by_single_command codex "${CODEX_HOME}/plugins" "${install_command}"

	chmod u+w "${CODEX_HOME}/plugins"
	run single_command
	[ "${status}" -eq 0 ]
	[ -d "${CODEX_HOME}/plugins/cat-marketplace" ]
	make_read_only "${CODEX_HOME}/plugins"

	expect_unchanged_by_single_command codex "${CODEX_HOME}/plugins" "${install_command}"
	expect_unchanged_by_single_command codex "${CODEX_HOME}/plugins" "${uninstall_command} --uninstall" --uninstall
}

# Codex writes a helper program into CODEX_HOME/tmp whenever it installs or removes a plugin
@test "single command changes nothing when Codex's tmp directory is read-only" {
	create_fake_engine_command codex
	create_fake_release codex shipped > /dev/null
	export PATH="${FAKE_BIN}:${PATH}" CODEX_THREAD_ID=019a0000-0000-7000-8000-000000000000
	install_command="curl -fsSL ${SINGLE_COMMAND_URL} | CODEX_HOME='${CODEX_HOME}' sh -s -- --harness codex"
	uninstall_command="curl -fsSL ${SINGLE_COMMAND_URL} | CODEX_HOME='${CODEX_HOME}' sh -s -- --harness codex"
	mkdir -p "${CODEX_HOME}/tmp"
	make_read_only "${CODEX_HOME}/tmp"

	expect_unchanged_by_single_command codex "${CODEX_HOME}/tmp" "${install_command}"
	expect_unchanged_by_single_command codex "${CODEX_HOME}/tmp" "${uninstall_command} --uninstall" --uninstall
}

@test "single command that stops partway tells an agent to hand the command to the user without the prompt prefix" {
	create_fake_engine_command codex
	printf '%s\n' '#!/usr/bin/env sh' 'exit 1' > "${FAKE_BIN}/codex"
	create_fake_release codex shipped > /dev/null
	export PATH="${FAKE_BIN}:${PATH}" CODEX_THREAD_ID=019a0000-0000-7000-8000-000000000000

	run single_command

	[ "${status}" -ne 0 ]
	[[ "${output}" == *"stopped partway"* ]]
	expect_agent_told_to_hand_command_to_user codex
}
