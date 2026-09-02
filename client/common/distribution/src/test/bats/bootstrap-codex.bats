#!/usr/bin/env bats

# Tests Codex plugin installation, removal, release bootstrapping, and generated hook behavior.

load bootstrap-helpers

@test "Codex installer reports a missing shared library" {
	standalone_directory="${TEST_ROOT}/standalone Codex installer"
	mkdir -p "${standalone_directory}"
	cp "${INSTALL_DIRECTORY}/install-codex.sh" "${standalone_directory}/install-codex.sh"

	run sh "${standalone_directory}/install-codex.sh"

	[ "${status}" -eq 1 ]
	[[ "${output}" == *"CAT installer library not found"* ]]
}

@test "generated Codex plugin-info launcher reports its native identity" {
	expected_platform="$(expected_native_platform)"

	run "${GENERATED_RUNTIME_DIRECTORY}/codex/client/bin/plugin-info"

	[ "${status}" -eq 0 ]
	[ "${output}" = "harness=codex
version=1.0
platform=${expected_platform}" ]
}

@test "installer tells users how to correct an invalid marketplace directory" {
	artifact="$(create_test_artifact codex)"

	run env CAT_CODEX_MARKETPLACE_ROOT="${TEST_ROOT}/not-cat" sh "${INSTALL_DIRECTORY}/install-codex.sh" "${artifact}"

	[ "${status}" -eq 1 ]
	[[ "${output}" == *"must end in /cat-marketplace"* ]]
	[[ "${output}" == *"CAT_CODEX_MARKETPLACE_ROOT"* ]]
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

@test "Codex installs self-contained custom agents from an assembled artifact" {
	create_fake_engine_command codex
	artifact="$(create_test_artifact codex)"
	cat > "${artifact}/agents/codex/reviewer.toml" <<'SCRIPT'
name = "cat-reviewer"
developer_instructions = '''
Review evidence without editing.
'''
SCRIPT
	rm -rf "${artifact}/agents/common"

	run env PATH="${FAKE_BIN}:${PATH}" COMMAND_LOG="${COMMAND_LOG}" CODEX_HOME="${CODEX_HOME}" \
		sh "${INSTALL_DIRECTORY}/install-codex.sh" "${artifact}"

	[ "${status}" -eq 0 ]
	grep -F "Review evidence without editing." "${CODEX_HOME}/agents/cat-reviewer.toml"
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
	[ "$(cat "${data_directory}/migration-version")" = "1.0" ]
	grep -F "codex plugin marketplace add ${CODEX_HOME}/plugins/cat-marketplace" "${COMMAND_LOG}"
}

@test "Codex uninstall removes only CAT-owned user agents before native plugin removal" {
	create_fake_engine_command codex
	mkdir -p "${CODEX_HOME}/plugins/cat-marketplace" "${CODEX_HOME}/plugins/data/cat-cat" "${CODEX_HOME}/agents"
	printf 'cat agent\n' > "${CODEX_HOME}/agents/cat-reviewer.toml"
	printf 'user agent\n' > "${CODEX_HOME}/agents/reviewer.toml"
	printf 'cat-reviewer.toml\n' > "${CODEX_HOME}/plugins/data/cat-cat/codex-agent-files"

	run env PATH="${FAKE_BIN}:${PATH}" COMMAND_LOG="${COMMAND_LOG}" CODEX_HOME="${CODEX_HOME}" \
		sh "${INSTALL_DIRECTORY}/uninstall-codex.sh"

	[ "${status}" -eq 0 ]
	[ "$(sed -n '1p' "${COMMAND_LOG}")" = "codex plugin remove cat@cat" ]
	[ ! -e "${CODEX_HOME}/agents/cat-reviewer.toml" ]
	[ -f "${CODEX_HOME}/agents/reviewer.toml" ]
	[ ! -e "${CODEX_HOME}/plugins/data/cat-cat" ]
	[ ! -e "${CODEX_HOME}/plugins/cat-marketplace" ]
}

@test "Codex upgrade replaces recorded agents without overwriting unrelated agents" {
	create_fake_engine_command codex
	artifact="$(create_test_artifact codex)"
	mkdir -p "${CODEX_HOME}/agents"
	printf 'user agent\n' > "${CODEX_HOME}/agents/reviewer.toml"

	run env PATH="${FAKE_BIN}:${PATH}" COMMAND_LOG="${COMMAND_LOG}" CODEX_HOME="${CODEX_HOME}" \
		sh "${INSTALL_DIRECTORY}/install-codex.sh" "${artifact}"
	[ "${status}" -eq 0 ]
	printf 'old CAT agent\n' > "${CODEX_HOME}/agents/cat-reviewer.toml"

	run env PATH="${FAKE_BIN}:${PATH}" COMMAND_LOG="${COMMAND_LOG}" CODEX_HOME="${CODEX_HOME}" \
		sh "${INSTALL_DIRECTORY}/install-codex.sh" "${artifact}"

	[ "${status}" -eq 0 ]
	grep -F "Review evidence without editing." "${CODEX_HOME}/agents/cat-reviewer.toml"
	[ -f "${CODEX_HOME}/agents/reviewer.toml" ]
}

@test "Git bootstrap installs the exact Codex release target" {
	create_fake_engine_command codex
	bootstrap="$(cd "${BATS_TEST_DIRNAME}/../../../../lifecycle/src/main/resources/install" && pwd)/bootstrap.sh"
	cat > "${FAKE_BIN}/git" <<'SCRIPT'
#!/usr/bin/env sh
printf 'git %s\n' "$*" >> "${COMMAND_LOG}"
if [ "${1:-}" = "-C" ]; then
	if [ "${3:-}" = "describe" ]; then
		printf '1.0\n'
		exit 0
	fi
	exit 1
fi
destination=''
for argument in "$@"; do destination="${argument}"; done
target="${destination}/codex/linux-x86_64"
mkdir -p "${target}/.codex-plugin" "${target}/client/bin" "${target}/install/migrations" \
	"${target}/agents/codex" "${target}/agents/common"
printf '{}\n' > "${target}/.codex-plugin/plugin.json"
printf 'name = "cat-reviewer"\n<!-- cat:include ../common/reviewer.md -->\n' > "${target}/agents/codex/reviewer.toml"
printf 'review\n' > "${target}/agents/common/reviewer.md"
printf '1.0\n' > "${target}/client/VERSION"
printf '# target-version<TAB>script-relative-to-this-directory\n' > "${target}/install/migrations/registry.tsv"
cp "${INSTALL_DIRECTORY}/common.sh" "${target}/install/common.sh"
cp "${INSTALL_DIRECTORY}/install-codex.sh" "${target}/install/install-codex.sh"
cp "${INSTALL_DIRECTORY}/codex-marketplace.json" "${target}/install/codex-marketplace.json"
cat > "${target}/client/bin/plugin-info" <<'INNER'
#!/usr/bin/env sh
exit 0
INNER
chmod +x "${target}/client/bin/plugin-info"
{
	printf '{\n  "files" : [\n'
	first=true
	find "${target}" -type f -print | LC_ALL=C sort | while IFS= read -r file; do
		path="${file#"${destination}"/}"
		hash="$(sha256sum "${file}" | awk '{ print $1 }')"
		size="$(wc -c < "${file}" | tr -d '[:space:]')"
		if [ "${first}" = true ]; then first=false; else printf ',\n'; fi
		printf '    { "path" : "%s", "sha256" : "%s", "size" : %s }' "${path}" "${hash}" "${size}"
	done
	printf '\n  ]\n}\n'
} > "${destination}/release-manifest.json"
if [ "${CAT_BOOTSTRAP_BAD_FILE:-}" = true ]; then
	printf 'tampered\n' >> "${target}/client/VERSION"
fi
SCRIPT
	chmod +x "${FAKE_BIN}/git"

	run env PATH="${FAKE_BIN}:${PATH}" COMMAND_LOG="${COMMAND_LOG}" INSTALL_DIRECTORY="${INSTALL_DIRECTORY}" \
		CODEX_HOME="${CODEX_HOME}" CAT_BOOTSTRAP_GIT=git sh "${bootstrap}" codex 1.0

	[ "${status}" -eq 0 ]
	grep -F "git clone --depth 1 --branch 1.0 https://github.com/catsforbots/cat.git" "${COMMAND_LOG}"
	grep -F "codex plugin add cat@cat" "${COMMAND_LOG}"

	run env PATH="${FAKE_BIN}:${PATH}" COMMAND_LOG="${COMMAND_LOG}" INSTALL_DIRECTORY="${INSTALL_DIRECTORY}" \
		CODEX_HOME="${CODEX_HOME}" CAT_BOOTSTRAP_REPOSITORY="https://example.invalid/cat.git" \
		CAT_BOOTSTRAP_GIT=git CAT_BOOTSTRAP_BAD_FILE=true sh "${bootstrap}" codex 1.0

	[ "${status}" -eq 1 ]
	[[ "${output}" == *"CAT release manifest size mismatch"* ]]
}
@test "generated launchers work when the runtime path contains spaces" {
	runtime="${TEST_ROOT}/generated runtime"
	cp -R "${GENERATED_RUNTIME_DIRECTORY}/codex" "${runtime}"
	create_fake_bwrap

	run env PATH="${FAKE_BIN}:${PATH}" "${runtime}/client/bin/plugin-info"

	[ "${status}" -eq 0 ]
	[[ "${output}" == "harness=codex"$'\n'* ]]
}

@test "generated Codex SessionStart hook disables additional-context spilling" {
	runtime="${GENERATED_RUNTIME_DIRECTORY}/codex"

	grep -F '"additionalContextLimit": 0' "${runtime}/hooks/hooks.json"
}

@test "generated Codex SessionStart launcher injects bundled routing and project rules" {
	project="${TEST_ROOT}/generated session project"
	mkdir -p "${project}/.cat/rules/common"
	printf 'project session rule\n' > "${project}/.cat/rules/common/session.md"
	runtime="${GENERATED_RUNTIME_DIRECTORY}/codex"
	create_fake_bwrap

	run env PATH="${FAKE_BIN}:${PATH}" PLUGIN_ROOT="${runtime}" sh -c 'printf "%s\\n" "$1" | "$2"' sh \
		"{\"cwd\":\"${project}\"}" "${runtime}/hooks/session-start.sh"

	[ "${status}" -eq 0 ]
	[[ "${output}" == *"Default Rule Routing"* ]]
	[[ "${output}" == *"When handling Java exceptions, lazy load"*"/rules/include/java/exceptions.md"* ]]
	[[ "${output}" == *"project session rule"* ]]
}

@test "generated Codex SessionStart launcher does not require hook PATH commands" {
	project="${TEST_ROOT}/restricted path session project"
	mkdir -p "${project}"
	runtime="${GENERATED_RUNTIME_DIRECTORY}/codex"

	run env -i HOME="${HOME}" PATH="/nonexistent" PLUGIN_ROOT="${runtime}" /bin/sh -c \
		'printf "%s\\n" "$1" | "$2"' sh "{\"cwd\":\"${project}\"}" "${runtime}/hooks/session-start.sh"

	[ "${status}" -eq 0 ]
	[[ "${output}" == *'"hookEventName":"SessionStart"'* ]]
}

@test "generated Codex SubagentStart launcher injects only addressed project rules" {
	project="${TEST_ROOT}/generated subagent project"
	mkdir -p "${project}/.cat/rules/codex"
	printf '%s\n' '---' 'agents: ["main"]' '---' 'main rule' > "${project}/.cat/rules/codex/main.md"
	printf '%s\n' '---' 'agents: ["cat:reviewer"]' '---' 'reviewer rule' > "${project}/.cat/rules/codex/reviewer.md"
	runtime="${GENERATED_RUNTIME_DIRECTORY}/codex"
	create_fake_bwrap

	run env PATH="${FAKE_BIN}:${PATH}" PLUGIN_ROOT="${runtime}" sh -c 'printf "%s\\n" "$1" | "$2"' sh \
		"{\"cwd\":\"${project}\",\"agent_type\":\"cat:reviewer\"}" "${runtime}/hooks/subagent-start.sh"

	[ "${status}" -eq 0 ]
	[[ "${output}" == *'"hookEventName":"SubagentStart"'* ]]
	[[ "${output}" == *"reviewer rule"* ]]
	[[ "${output}" != *"main rule"* ]]
}
