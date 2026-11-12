#!/usr/bin/env bats
# Tests the PowerShell installer launcher that the build writes into each Windows plugin, as Windows PowerShell runs it
# through -File from Git Bash: its argument forwarding, its removal of environment-supplied JVM options, the caller's
# PATH and working directory that the installer receives, its start of the plugin's own java.exe and its exit-status and
# output pass-through. Also tests that the installer, run from the version folder of the plugin that the build
# assembled, has the folder deleted once it exits, through the System32 Windows PowerShell process that waits for it.
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

# Creates a test-owned directory beneath the build output.
# Side effects: Creates TEST_ROOT, which teardown removes.
setup()
{
	mkdir -p "${BATS_TEST_DIRNAME}/../../../target"
	TEST_ROOT="$(mktemp -d "${BATS_TEST_DIRNAME}/../../../target/installer-launcher-windows.XXXXXX")"
	TEST_ROOT="$(cd -P "${TEST_ROOT}" && pwd -P)"
}

# Releases a file that a test still holds open, then removes only the directory created for this test, including after
# failed setup or assertions.
teardown()
{
	if [ -n "${RELEASE:-}" ]; then
		: > "${RELEASE}"
		wait
	fi
	if [ -n "${TEST_ROOT:-}" ]; then
		rm -rf -- "${TEST_ROOT}"
	fi
}

# Writes a Java runtime whose java.exe is the fake, as a plugin holds it.
# Arguments: the absolute path of the runtime directory, its marker text, and the status of its java -version.
# Side effects: Creates the directory with bin\java.exe, version-status and a marker file that names the runtime.
write_runtime()
{
	mkdir -p "$1/bin"
	cp "${FAKE_JAVA}" "$1/bin/java.exe"
	printf '%s\n' "$2" > "$1/marker"
	printf '%s\n' "$3" > "$1/version-status"
}

# Prints the arguments that the fake java.exe of a folder's runtime last received, one [argument] per line.
# Arguments: the folder that holds the fake's java-invocation.txt.
# Output: the arguments.
java_arguments()
{
	sed -n '/^\[/p' "$1/java-invocation.txt"
}

# Prints the value of an environment variable that the fake java.exe of a folder's runtime last received.
# Arguments: the folder that holds the fake's java-invocation.txt, and the variable's name in upper case.
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

# Prints a path in the form that JSON names it to the Windows installer: its Windows form, with each backslash doubled.
# Arguments: the path.
# Output: the JSON string's content.
json_windows_path()
{
	cygpath -w "$1" | sed 's/\\/\\\\/g'
}

# Copies the Claude plugin that the build assembled into a Claude Code home, as the version folder of an installation
# whose policy install wrote. The policy names a Claude Code program that no longer exists, which uninstall needs only
# to unregister CAT, so uninstall skips that step.
# Outputs:
#   CLAUDE_HOME - the Claude Code home, whose path holds spaces
#   VERSION_FOLDER - the plugin's version folder in that home
create_installed_plugin()
{
	local plugin="${BATS_TEST_DIRNAME}/../../../target/runtime/claude"
	local version data home program
	version="$(tr -d '\r\n' < "${plugin}/bin/VERSION")"
	CLAUDE_HOME="${TEST_ROOT}/claude home"
	VERSION_FOLDER="${CLAUDE_HOME}/plugins/cat-marketplace/plugins/cat/${version}"
	mkdir -p "${VERSION_FOLDER%/*}"
	cp -R "${plugin}" "${VERSION_FOLDER}"
	data="${CLAUDE_HOME}/plugins/data/cat-cat"
	mkdir -p "${data}"
	home="$(json_windows_path "${CLAUDE_HOME}")"
	program="$(json_windows_path "${TEST_ROOT}/missing/claude.exe")"
	printf '%s\n' "{\"format\":2,\"keys\":[],\"release_base_url\":\"https://github.com/holdmyspot-com/cat/releases/download\",\
\"allowed_versions\":{\"minimum\":\"1.0\",\"maximum_major\":1},\"harnesses\":{\"claude\":{\"home\":\"${home}\",\
\"command\":[\"${program}\"],\"environment\":{}}}}" > "${data}/policy.json"
}

# The installer cannot delete its own version folder while it runs, because Windows keeps the files of a running
# program open, so it starts a System32 Windows PowerShell process that waits for it to exit and then retries the
# deletion until no file in the folder is open any more.
@test "the installer that uninstalls CAT from its own version folder has the folder deleted once it exits" {
	create_installed_plugin
	local held="${VERSION_FOLDER}/held file"
	local ready="${TEST_ROOT}/held"
	RELEASE="${TEST_ROOT}/release"
	# Holds a file of the version folder open, without letting another process delete it, until two seconds after
	# the installer exits
	powershell_command '$file = [System.IO.File]::Open($env:HELD, "OpenOrCreate", "ReadWrite", "None")
$null = New-Item -ItemType File -Path $env:READY
while (-not (Test-Path -LiteralPath $env:RELEASE)) { Start-Sleep -Milliseconds 100 }
Start-Sleep -Seconds 2
$file.Close()' "HELD=$(cygpath -w "${held}")" "READY=$(cygpath -w "${ready}")" "RELEASE=$(cygpath -w "${RELEASE}")" \
		< /dev/null > /dev/null 2>&1 3>&- &
	local waited=0
	while [ ! -e "${ready}" ] && [ "${waited}" -lt 300 ]; do
		sleep 0.1
		waited=$((waited + 1))
	done
	[ -e "${ready}" ]

	mkdir "${TEST_ROOT}/user"
	USERPROFILE="$(cygpath -w "${TEST_ROOT}/user")"
	export USERPROFILE HOME="${TEST_ROOT}/user"
	run --separate-stderr powershell_file "${VERSION_FOLDER}/bin/installer.ps1" uninstall --json
	: > "${RELEASE}"

	[ "${status}" -eq 0 ]
	[[ "${output}" == *'"result":"uninstalled"'* ]]
	# The held file keeps the folder until the holder closes it
	[ -e "${held}" ]
	waited=0
	while [ -e "${VERSION_FOLDER}" ] && [ "${waited}" -lt 60 ]; do
		sleep 1
		waited=$((waited + 1))
	done
	[ ! -e "${VERSION_FOLDER}" ]
	[ -d "${CLAUDE_HOME}/plugins/data/cat-cat" ]
}
