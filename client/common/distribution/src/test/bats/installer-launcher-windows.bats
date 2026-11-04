#!/usr/bin/env bats
# Tests the installer's Windows launchers as Windows PowerShell runs them through -File from Git Bash: the versioned
# launchers' start of their installation's java.exe, from PowerShell and from Git Bash, and their exit-status
# pass-through, and the PowerShell installer launcher that the build writes into each Windows plugin: its argument
# forwarding, its removal of environment-supplied JVM options, the caller's PATH and working directory that the
# installer receives, its start of the plugin's own java.exe and its exit-status and output pass-through.
# Only the Windows build runs this file; the POSIX launchers' tests are in installer-launcher.bats.

bats_require_minimum_version 1.5.0

load windows-helpers
load installer-jvm-options

# Builds the fake java.exe that every test's runtimes copy, and finds the Windows directory.
# Side effects: Exports FAKE_JAVA, the fake's path, and WINDOWS_DIRECTORY, the Windows directory's Windows form.
setup_file()
{
	export FAKE_JAVA="${BATS_FILE_TMPDIR}/java.exe"
	build_fake_java "${FAKE_JAVA}"
	WINDOWS_DIRECTORY="$(windows_directory)"
	export WINDOWS_DIRECTORY
}

# Creates a test-owned installer data directory, mirroring the installed Windows layout, beneath the build output.
# Side effects: Creates TEST_ROOT, which teardown removes, holding the data directory DATA.
setup()
{
	mkdir -p "${BATS_TEST_DIRNAME}/../../../target"
	TEST_ROOT="$(mktemp -d "${BATS_TEST_DIRNAME}/../../../target/installer-launcher-windows.XXXXXX")"
	TEST_ROOT="$(cd -P "${TEST_ROOT}" && pwd -P)"
	DATA="${TEST_ROOT}/installer with spaces"
	RESOURCE_DIRECTORY="${BATS_TEST_DIRNAME}/../../../../installer/src/main/resources/com/holdmyspot/cat/common/installer/cli"
	create_data_directory "${DATA}"
}

# Removes only the directory created for this test, including after failed setup or assertions.
teardown()
{
	if [ -n "${TEST_ROOT:-}" ]; then
		rm -rf -- "${TEST_ROOT}"
	fi
}

# Creates an installer data directory as an installation lays it out on Windows: version 1.0's PowerShell and POSIX
# versioned launchers, and the installation's runtime.
# Arguments: the absolute path of the data directory to create.
# Side effects: Creates the directory with 1.0\bin\ and runtime\.
create_data_directory()
{
	mkdir -p "$1/1.0/bin"
	cp "${RESOURCE_DIRECTORY}/versioned-launcher.ps1" "$1/1.0/bin/cat-updater.ps1"
	cp "${RESOURCE_DIRECTORY}/versioned-launcher.sh" "$1/1.0/bin/cat-updater"
	write_runtime "$1/runtime" installed 0
}

# Writes a Java runtime whose java.exe is the fake, as an installation or a plugin holds it.
# Arguments: the absolute path of the runtime directory, its marker text, and the status of its java -version.
# Side effects: Creates the directory with bin\java.exe, version-status and a marker file that names the runtime.
write_runtime()
{
	mkdir -p "$1/bin"
	cp "${FAKE_JAVA}" "$1/bin/java.exe"
	printf '%s\n' "$2" > "$1/marker"
	printf '%s\n' "$3" > "$1/version-status"
}

# Prints the arguments that the fake java.exe of a data directory's runtime last received, one [argument] per line.
# Arguments: the data directory.
# Output: the arguments.
java_arguments()
{
	sed -n '/^\[/p' "$1/java-invocation.txt"
}

# Prints the value of an environment variable that the fake java.exe of a data directory's runtime last received.
# Arguments: the data directory and the variable's name in upper case.
# Output: the value, or nothing when the variable was not set.
java_variable()
{
	sed -n "s/^env:$2=//p" "$1/java-invocation.txt"
}

# Prints the Java options that every installer launcher passes, as java_arguments prints them, for a logging
# configuration file and an application module directory in the form that names them to Java.
# Arguments: the logging configuration file and the application module directory.
# Output: the options, one [option] per line.
bracketed_java_options()
{
	local option
	installer_java_options "$1" "$2" | while IFS= read -r option; do
		printf '[%s]\n' "${option}"
	done
}

# Prints the Java options that the Windows installer launchers pass to java.exe before the installer operation's own.
# Arguments: the data directory in the form that names it to Java.
# Output: the options, one [option] per line.
fixed_java_arguments()
{
	bracketed_java_options "$1\\runtime\\conf\\logging.properties" "$1\\1.0\\app"
}

# Copies the PowerShell installer launcher of the Claude plugin that the build generated into a plugin directory whose
# path holds spaces, beside a Java runtime whose java.exe is the fake. The fake's folder of files is the plugin's bin
# folder.
# Outputs:
#   PLUGIN - the plugin directory, holding bin\installer.ps1 and bin\runtime\bin\java.exe
create_plugin_installer()
{
	PLUGIN="${TEST_ROOT}/plugin with spaces"
	mkdir -p "${PLUGIN}/bin/runtime/modules"
	cp "${BATS_TEST_DIRNAME}/../../../target/runtime/claude/bin/installer.ps1" "${PLUGIN}/bin/installer.ps1"
	write_runtime "${PLUGIN}/bin/runtime" plugin 0
}

# Prints the Java options that the plugin's PowerShell installer launcher passes to java.exe before the installer
# operation's own.
# Arguments: the plugin directory in the form that names it to Java.
# Output: the options, one [option] per line.
plugin_java_arguments()
{
	bracketed_java_options "$1\\bin\\runtime\\conf\\logging.properties" "$1\\bin\\runtime\\modules"
}

@test "the Windows versioned launcher runs java.exe with the caller's PATH and directory and no injected JVM options" {
	mkdir "${TEST_ROOT}/work" "${TEST_ROOT}/caller bin"
	export PATH="${TEST_ROOT}/caller bin:${PATH}"
	export JAVA_TOOL_OPTIONS=hostile JDK_JAVA_OPTIONS=hostile _JAVA_OPTIONS=hostile CLASSPATH=hostile \
		JLINK_VM_OPTIONS=hostile
	cd "${TEST_ROOT}/work"

	run powershell_file "${DATA}/1.0/bin/cat-updater.ps1" setup --harness claude --home 'C:\a home' ''

	[ "${status}" -eq 0 ]
	expected="$(fixed_java_arguments "$(cygpath -w "${DATA}")"
		printf '[%s]\n' setup --harness claude --home 'C:\a home' '')"
	[ "$(java_arguments "${DATA}")" = "${expected}" ]
	[ "$(sed -n 's/^cwd=//p' "${DATA}/java-invocation.txt")" = "$(cygpath -w "${TEST_ROOT}/work")" ]
	[[ ";$(java_variable "${DATA}" PATH);" == *";$(cygpath -w "${TEST_ROOT}/caller bin");"* ]]
	run ! grep -E '^env:(JAVA_TOOL_OPTIONS|JDK_JAVA_OPTIONS|_JAVA_OPTIONS|CLASSPATH|JLINK_VM_OPTIONS)=' \
		"${DATA}/java-invocation.txt"
}

@test "the Windows versioned launcher passes the installer's status and output through unchanged" {
	printf '%s\n' '{"result":"refused"}' > "${DATA}/java-output"
	printf '5\n' > "${DATA}/java-status"

	run --separate-stderr powershell_file "${DATA}/1.0/bin/cat-updater.ps1" install --harness claude

	[ "${status}" -eq 5 ]
	[ "${output}" = '{"result":"refused"}' ]
	[ -z "${stderr}" ]
}

@test "the Windows versioned launcher reports a missing installation runtime with one diagnostic and status 3" {
	rm -rf -- "${DATA}/runtime"

	run --separate-stderr powershell_file "${DATA}/1.0/bin/cat-updater.ps1" status

	[ "${status}" -eq 3 ]
	[ -z "${output}" ]
	java="$(cygpath -w "${DATA}/runtime/bin/java.exe")"
	[ "${stderr}" = "CAT installer runtime is missing: ${java} is not a file; rerun the CAT installation command." ]
}

# The release's entry script cat.sh runs setup through the archive's POSIX versioned launcher under Git Bash, which
# names the runtime and application by their POSIX paths; java.exe can open them only in their Windows form.
@test "the POSIX versioned launcher that the entry script runs in Git Bash gives java.exe Windows paths" {
	run "${DATA}/1.0/bin/cat-updater" setup --harness claude --home 'C:\a home'

	[ "${status}" -eq 0 ]
	arguments="$(java_arguments "${DATA}")"
	logging="$(printf '%s\n' "${arguments}" | sed -n 's/^\[-Djava\.util\.logging\.config\.file=\(.*\)\]$/\1/p')"
	modules="$(printf '%s\n' "${arguments}" | sed -n '/^\[--module-path\]$/{n;s/^\[\(.*\)\]$/\1/p;}')"
	[[ "${logging}" == [A-Za-z]:* ]]
	[[ "${modules}" == [A-Za-z]:* ]]
	[ "$(cygpath -u "${logging}")" = "${DATA}/runtime/conf/logging.properties" ]
	[ "$(cygpath -u "${modules}")" = "${DATA}/1.0/app" ]
	expected="$(bracketed_java_options "${logging}" "${modules}"
		printf '[%s]\n' setup --harness claude --home 'C:\a home')"
	[ "${arguments}" = "${expected}" ]
}

@test "the plugin's PowerShell installer launcher runs java.exe of the plugin's own runtime with the plugin's modules" {
	create_plugin_installer

	run powershell_file "${PLUGIN}/bin/installer.ps1" status --harness claude ''

	[ "${status}" -eq 0 ]
	expected="$(plugin_java_arguments "$(cygpath -w "${PLUGIN}")"
		printf '[%s]\n' status --harness claude '')"
	[ "$(java_arguments "${PLUGIN}/bin")" = "${expected}" ]
}

# Windows PowerShell parses the command line that Git Bash builds for -File itself, so an argument that holds a space,
# a quotation mark or nothing reaches the launcher only if that parsing keeps it, and reaches java.exe only if the
# launcher quotes it again for the Microsoft C runtime's rules.
@test "the plugin's PowerShell installer launcher passes spaces, quotation marks and an empty argument through to Java" {
	create_plugin_installer

	run powershell_file "${PLUGIN}/bin/installer.ps1" status 'first argument' '' 'quote " here' 'back\slash\' \
		'percent %PATH% and $dollar'

	[ "${status}" -eq 0 ]
	expected="$(plugin_java_arguments "$(cygpath -w "${PLUGIN}")"
		printf '[%s]\n' status 'first argument' '' 'quote " here' 'back\slash\' 'percent %PATH% and $dollar')"
	[ "$(java_arguments "${PLUGIN}/bin")" = "${expected}" ]
}

@test "the plugin's PowerShell installer launcher gives java.exe the caller's PATH and directory and no injected JVM options" {
	create_plugin_installer
	mkdir "${TEST_ROOT}/work" "${TEST_ROOT}/caller bin"
	export PATH="${TEST_ROOT}/caller bin:${PATH}"
	export JAVA_TOOL_OPTIONS=hostile JDK_JAVA_OPTIONS=hostile _JAVA_OPTIONS=hostile CLASSPATH=hostile \
		JLINK_VM_OPTIONS=hostile
	cd "${TEST_ROOT}/work"

	run powershell_file "${PLUGIN}/bin/installer.ps1" status

	[ "${status}" -eq 0 ]
	[ "$(sed -n 's/^cwd=//p' "${PLUGIN}/bin/java-invocation.txt")" = "$(cygpath -w "${TEST_ROOT}/work")" ]
	[[ ";$(java_variable "${PLUGIN}/bin" PATH);" == *";$(cygpath -w "${TEST_ROOT}/caller bin");"* ]]
	run ! grep -E '^env:(JAVA_TOOL_OPTIONS|JDK_JAVA_OPTIONS|_JAVA_OPTIONS|CLASSPATH|JLINK_VM_OPTIONS)=' \
		"${PLUGIN}/bin/java-invocation.txt"
}

@test "the plugin's PowerShell installer launcher passes each installer status and its output through unchanged" {
	local code
	create_plugin_installer
	for code in 0 1 2 3 4 5 6 75; do
		printf '%s\n' "{\"result\":\"status ${code}\"}" > "${PLUGIN}/bin/java-output"
		printf '%s\n' "${code}" > "${PLUGIN}/bin/java-status"

		run --separate-stderr powershell_file "${PLUGIN}/bin/installer.ps1" install --harness claude

		[ "${status}" -eq "${code}" ]
		[ "${output}" = "{\"result\":\"status ${code}\"}" ]
		[ -z "${stderr}" ]
	done
}

@test "the plugin's PowerShell installer launcher reports a missing plugin runtime with one diagnostic and status 3" {
	create_plugin_installer
	rm -rf -- "${PLUGIN}/bin/runtime"

	run --separate-stderr powershell_file "${PLUGIN}/bin/installer.ps1" status

	[ "${status}" -eq 3 ]
	[ -z "${output}" ]
	java="$(cygpath -w "${PLUGIN}/bin/runtime/bin/java.exe")"
	[ "${stderr}" = "CAT installer runtime is missing: ${java} is not a file; reinstall CAT." ]
}
