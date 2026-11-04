#!/usr/bin/env bats
# Tests the installer's POSIX launchers: the versioned launcher's selection of its installation's runtime, its fixed JVM
# options and its exit-status pass-through, and the installer launcher that the build writes into each plugin's bin
# folder: its removal of environment-supplied JVM options, the caller's PATH and working directory that the installer
# receives, its selection of the plugin's own runtime and modules, and its exit-status and output pass-through.

bats_require_minimum_version 1.5.0

load installer-jvm-options

# Creates a test-owned directory beneath the build output for an installer data directory and a plugin.
# Side effects: Creates TEST_ROOT, which teardown removes, and its home directory, and sets DATA to the data directory
#   that install_runtime fills, whose path holds spaces.
setup()
{
	mkdir -p "${BATS_TEST_DIRNAME}/../../../target"
	TEST_ROOT="$(mktemp -d "${BATS_TEST_DIRNAME}/../../../target/installer-launcher.XXXXXX")"
	TEST_ROOT="$(cd -P "${TEST_ROOT}" && pwd -P)"
	export HOME="${TEST_ROOT}/home"
	mkdir -p "${HOME}"
	DATA="${TEST_ROOT}/installer with spaces"
	RESOURCE_DIRECTORY="${BATS_TEST_DIRNAME}/../../../../installer/src/main/resources/com/holdmyspot/cat/common/installer/cli"
}

# Writes the versioned launcher of installer version 1.0 into a data directory, beside a fake installation runtime
# Java.
# Arguments: the absolute path of the data directory, then the body of the fake java script, which runs with the
#   variable data set to the data directory that holds it.
# Side effects: Writes 1.0/bin/cat-updater and runtime/bin/java beneath the data directory.
install_runtime()
{
	mkdir -p "$1/1.0/bin" "$1/runtime/bin"
	cp "${RESOURCE_DIRECTORY}/versioned-launcher.sh" "$1/1.0/bin/cat-updater"
	chmod +x "$1/1.0/bin/cat-updater"
	{
		printf '%s\n' '#!/bin/sh' 'data=$(cd -P "${0%/*}/../.." && pwd -P)'
		printf '%s\n' "$2"
	} > "$1/runtime/bin/java"
	chmod +x "$1/runtime/bin/java"
}

# Prints the Java options that the versioned launcher passes, as the fake java scripts print their arguments.
# Arguments: the data directory as the launcher's path names it, and the installer version it starts.
# Output: each option enclosed in angle brackets, on one line.
expected_java_options()
{
	local option
	installer_java_options "$1/runtime/conf/logging.properties" "$1/$2/app" | while IFS= read -r option; do
		printf '<%s>' "${option}"
	done
}

# Removes only the directory created for this test, including after failed setup or assertions.
teardown()
{
	if [ -n "${TEST_ROOT:-}" ]; then
		chmod -R u+rwx -- "${TEST_ROOT}" 2>/dev/null || true
		rm -rf -- "${TEST_ROOT}"
	fi
}

@test "the versioned launcher runs its installation's Java with the fixed options and its own application modules" {
	install_runtime "${DATA}" '
if [ "${JAVA_TOOL_OPTIONS+x}${JDK_JAVA_OPTIONS+x}${_JAVA_OPTIONS+x}${CLASSPATH+x}${JLINK_VM_OPTIONS+x}" != "" ]; then
	exit 99
fi
printf "<%s>" "$@"'

	run env JAVA_TOOL_OPTIONS=hostile JDK_JAVA_OPTIONS=hostile _JAVA_OPTIONS=hostile CLASSPATH=hostile \
		JLINK_VM_OPTIONS=hostile "${DATA}/1.0/bin/cat-updater" status 'argument with spaces'

	[ "${status}" -eq 0 ]
	[ "${output}" = "$(expected_java_options "${DATA}" 1.0)<status><argument with spaces>" ]
}

@test "the versioned launcher started through a symbolic link runs the runtime beside its real version directory" {
	install_runtime "${DATA}" 'printf "<%s>" "$@"'
	mkdir "${TEST_ROOT}/links"
	ln -s "${DATA}/1.0/bin/cat-updater" "${TEST_ROOT}/links/cat-updater"

	run "${TEST_ROOT}/links/cat-updater" status

	[ "${status}" -eq 0 ]
	[[ "${output}" == *"<--module-path><${DATA}/1.0/app>"*'<status>' ]]
}

@test "the versioned launcher reports a missing installation runtime with one diagnostic and status 3" {
	install_runtime "${DATA}" 'printf ran'
	rm "${DATA}/runtime/bin/java"

	run --separate-stderr "${DATA}/1.0/bin/cat-updater" status

	[ "${status}" -eq 3 ]
	[ "${output}" = '' ]
	[ "${#stderr_lines[@]}" -eq 1 ]
	[[ "${stderr}" == *"${DATA}/runtime/bin/java"* ]]
}

@test "the versioned launcher passes the installer's status and output through unchanged" {
	install_runtime "${DATA}" "printf '%s\n' '{\"result\":\"refused\"}'
printf '%s\n' 'diagnostic' >&2
exit 5"

	run --separate-stderr "${DATA}/1.0/bin/cat-updater" install --harness claude

	[ "${status}" -eq 5 ]
	[ "${output}" = '{"result":"refused"}' ]
	[ "${stderr}" = 'diagnostic' ]
}

# Copies the installer launcher of the Claude plugin that the build generated into a plugin directory whose path holds
# spaces, beside a fake Java runtime in its bin/runtime folder.
# Arguments: the body of the fake java script.
# Outputs:
#   PLUGIN - the plugin directory, holding bin/installer and bin/runtime/bin/java
create_plugin_installer()
{
	PLUGIN="${TEST_ROOT}/plugin with spaces"
	mkdir -p "${PLUGIN}/bin/runtime/bin" "${PLUGIN}/bin/runtime/modules"
	cp "${BATS_TEST_DIRNAME}/../../../target/runtime/claude/bin/installer" "${PLUGIN}/bin/installer"
	printf '%s\n' '#!/bin/sh' "$1" > "${PLUGIN}/bin/runtime/bin/java"
	chmod +x "${PLUGIN}/bin/runtime/bin/java"
}

# Prints the Java options that the plugin's installer launcher passes, as the fake java scripts print their arguments.
# Arguments: the plugin directory as the launcher's path names it.
# Output: each option enclosed in angle brackets, on one line.
expected_plugin_java_options()
{
	local option
	installer_java_options "$1/bin/runtime/conf/logging.properties" "$1/bin/runtime/modules" |
		while IFS= read -r option; do
			printf '<%s>' "${option}"
		done
}

@test "the plugin's installer launcher runs the plugin's own runtime with the fixed options and the plugin's modules" {
	create_plugin_installer '
if [ "${JAVA_TOOL_OPTIONS+x}${JDK_JAVA_OPTIONS+x}${_JAVA_OPTIONS+x}${CLASSPATH+x}${JLINK_VM_OPTIONS+x}" != "" ]; then
	exit 99
fi
printf "<%s>" "$@"'

	run env JAVA_TOOL_OPTIONS=hostile JDK_JAVA_OPTIONS=hostile _JAVA_OPTIONS=hostile CLASSPATH=hostile \
		JLINK_VM_OPTIONS=hostile "${PLUGIN}/bin/installer" status 'argument with spaces'

	[ "${status}" -eq 0 ]
	[ "${output}" = "$(expected_plugin_java_options "${PLUGIN}")<status><argument with spaces>" ]
}

@test "the plugin's installer launcher started through a symbolic link runs the runtime of its real plugin" {
	create_plugin_installer 'printf "<%s>" "$@"'
	mkdir "${TEST_ROOT}/links"
	ln -s "${PLUGIN}/bin/installer" "${TEST_ROOT}/links/installer"

	run "${TEST_ROOT}/links/installer" status

	[ "${status}" -eq 0 ]
	[ "${output}" = "$(expected_plugin_java_options "${PLUGIN}")<status>" ]
}

@test "the plugin's installer launcher reports a missing plugin runtime with one diagnostic and status 3" {
	create_plugin_installer 'printf ran'
	rm "${PLUGIN}/bin/runtime/bin/java"

	run --separate-stderr "${PLUGIN}/bin/installer" status

	[ "${status}" -eq 3 ]
	[ "${output}" = '' ]
	[ "${#stderr_lines[@]}" -eq 1 ]
	[ "${stderr}" = "CAT installer runtime is missing: ${PLUGIN}/bin/runtime/bin/java is not an executable file; reinstall CAT." ]
}

@test "the plugin's installer launcher gives the installer the caller's PATH and working directory" {
	create_plugin_installer 'printf "<%s><%s>" "${PATH}" "$(pwd -P)"'
	mkdir "${TEST_ROOT}/caller bin"

	run sh -c 'cd "$1" && PATH="$2:/usr/bin:/bin" "$3" status' sh "${HOME}" "${TEST_ROOT}/caller bin" \
		"${PLUGIN}/bin/installer"

	[ "${status}" -eq 0 ]
	[ "${output}" = "<${TEST_ROOT}/caller bin:/usr/bin:/bin><${HOME}>" ]
}

@test "the plugin's installer launcher passes each installer status and its output through unchanged" {
	local code
	for code in 0 1 2 3 4 5 6 75; do
		create_plugin_installer "printf '%s\n' '{\"result\":\"status ${code}\"}'
printf '%s\n' 'diagnostic ${code}' >&2
exit ${code}"

		run --separate-stderr "${PLUGIN}/bin/installer" install --harness claude

		[ "${status}" -eq "${code}" ]
		[ "${output}" = "{\"result\":\"status ${code}\"}" ]
		[ "${stderr}" = "diagnostic ${code}" ]
	done
}
