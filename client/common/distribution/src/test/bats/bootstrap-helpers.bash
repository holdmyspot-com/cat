# Sets up one isolated filesystem and harness homes for a bootstrap test file.
setup()
{
	export TEST_ROOT="${BATS_TEST_TMPDIR}/root with spaces"
	export FAKE_BIN="${TEST_ROOT}/bin"
	export COMMAND_LOG="${TEST_ROOT}/commands.log"
	export HOME="${TEST_ROOT}/home"
	export CODEX_HOME="${HOME}/.codex"
	export CLAUDE_CONFIG_DIR="${HOME}/.claude"
	export COMMON_INSTALL_DIRECTORY="${BATS_TEST_DIRNAME}/../../../../lifecycle/src/main/resources/install"
	export CLAUDE_INSTALL_DIRECTORY="${BATS_TEST_DIRNAME}/../../../../../claude/lifecycle/src/main/resources/install"
	export CODEX_INSTALL_DIRECTORY="${BATS_TEST_DIRNAME}/../../../../../codex/lifecycle/src/main/resources/install"
	export INSTALL_DIRECTORY="${TEST_ROOT}/install"
	export GENERATED_RUNTIME_DIRECTORY="${BATS_TEST_DIRNAME}/../../../target/runtime"
	export FAKE_RELEASE_DIRECTORY="${TEST_ROOT}/release"
	mkdir -p "${FAKE_BIN}" "${HOME}" "${INSTALL_DIRECTORY}"
	cp -R "${COMMON_INSTALL_DIRECTORY}/." "${INSTALL_DIRECTORY}/"
	cp -R "${CLAUDE_INSTALL_DIRECTORY}/." "${INSTALL_DIRECTORY}/"
	cp -R "${CODEX_INSTALL_DIRECTORY}/." "${INSTALL_DIRECTORY}/"
	: > "${COMMAND_LOG}"
}

# Creates a fake harness command that appends its invocation to the test command log.
create_fake_engine_command()
{
	harness="$1"
	cat > "${FAKE_BIN}/${harness}" <<'SCRIPT'
#!/usr/bin/env sh
printf '%s %s\n' "$(basename "$0")" "$*" >> "${COMMAND_LOG}"
SCRIPT
	chmod +x "${FAKE_BIN}/${harness}"
}

# Creates a bubblewrap replacement that executes the packaged launcher without sandboxing test-owned temporary paths.
create_fake_bwrap()
{
	cat > "${FAKE_BIN}/bwrap" <<'SCRIPT'
#!/usr/bin/env sh
while [ "$1" != "--" ]; do
		shift
done
shift
exec "$@"
SCRIPT
	chmod +x "${FAKE_BIN}/bwrap"
}

# Creates a minimal extracted CAT artifact for the supplied harness and writes its directory to standard output.
create_test_artifact()
{
	harness="$1"
	artifact="${TEST_ROOT}/${harness} artifact"
	mkdir -p "${artifact}/.${harness}-plugin" "${artifact}/client/bin" "${artifact}/install/migrations"
	if [ "${harness}" = codex ]; then
		mkdir -p "${artifact}/agents/codex" "${artifact}/agents/common"
		cat > "${artifact}/agents/codex/reviewer.toml" <<'SCRIPT'
name = "cat-reviewer"
developer_instructions = '''
<!-- cat:include ../common/reviewer.md -->
'''
SCRIPT
		printf 'Review evidence without editing.\n' > "${artifact}/agents/common/reviewer.md"
	fi
	printf '{}\n' > "${artifact}/.${harness}-plugin/plugin.json"
	printf '1.0\n' > "${artifact}/client/VERSION"
	printf '# target-version<TAB>script-relative-to-this-directory\n' > "${artifact}/install/migrations/registry.tsv"
	cat > "${artifact}/client/bin/plugin-info" <<'SCRIPT'
#!/usr/bin/env sh
printf 'plugin-info\n' >> "${COMMAND_LOG}"
SCRIPT
	chmod +x "${artifact}/client/bin/plugin-info"
	printf '%s\n' "${artifact}"
}

# Adds a versioned migration script to a fake extracted artifact.
add_test_migration()
{
	artifact="$1"
	target_version="$2"
	script_body="$3"
	script_name="${target_version}.sh"
	printf '%s\t%s\n' "${target_version}" "${script_name}" >> "${artifact}/install/migrations/registry.tsv"
	printf '%s\n' "${script_body}" > "${artifact}/install/migrations/${script_name}"
}

# Writes the CAT platform identifier of the current operating system and architecture.
current_platform()
{
	case "$(uname -s):$(uname -m)" in
		Linux:x86_64|Linux:amd64) printf '%s\n' linux-x64 ;;
		Linux:aarch64|Linux:arm64) printf '%s\n' linux-arm64 ;;
		Darwin:x86_64|Darwin:amd64) printf '%s\n' macos-x64 ;;
		Darwin:aarch64|Darwin:arm64) printf '%s\n' macos-arm64 ;;
		*) return 1 ;;
	esac
}

# Publishes a release archive of a minimal CAT artifact for the current platform with its checksum list, and creates a
# fake curl that serves the release's files by name and appends each invocation to the test command log.
#
# Arguments:
#   $1 - Harness identifier, such as claude or codex.
#
# Output:
#   Writes the archive's file name to standard output.
#
# Side effects:
#   Creates FAKE_RELEASE_DIRECTORY and FAKE_BIN/curl.
create_fake_release()
{
	harness="$1"
	archive_directory="cat-${harness}-v1.0-$(current_platform)"
	mkdir -p "${FAKE_RELEASE_DIRECTORY}/staging"
	cp -R "$(create_test_artifact "${harness}")" "${FAKE_RELEASE_DIRECTORY}/staging/${archive_directory}"
	tar -czf "${FAKE_RELEASE_DIRECTORY}/${archive_directory}.tar.gz" -C "${FAKE_RELEASE_DIRECTORY}/staging" \
		"${archive_directory}"
	if command -v sha256sum >/dev/null 2>&1; then
		(cd "${FAKE_RELEASE_DIRECTORY}" && sha256sum "${archive_directory}.tar.gz") > \
			"${FAKE_RELEASE_DIRECTORY}/SHA256SUMS.txt"
	else
		(cd "${FAKE_RELEASE_DIRECTORY}" && shasum -a 256 "${archive_directory}.tar.gz") > \
			"${FAKE_RELEASE_DIRECTORY}/SHA256SUMS.txt"
	fi
	cat > "${FAKE_BIN}/curl" <<'SCRIPT'
#!/usr/bin/env sh
printf 'curl %s\n' "$*" >> "${COMMAND_LOG}"
output=''
url=''
while [ "$#" -gt 0 ]; do
	case "$1" in
		-o) shift; output="$1" ;;
		-*) ;;
		*) url="$1" ;;
	esac
	shift
done
source="${FAKE_RELEASE_DIRECTORY}/${url##*/}"
[ -f "${source}" ] || exit 22
cp "${source}" "${output}"
SCRIPT
	chmod +x "${FAKE_BIN}/curl"
	printf '%s\n' "${archive_directory}.tar.gz"
}
