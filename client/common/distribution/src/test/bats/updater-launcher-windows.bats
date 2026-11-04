#!/usr/bin/env bats
# Tests the trusted updater's Windows launchers as Windows PowerShell runs them through -File from Git Bash: the stable
# launcher's argument forwarding, environment isolation, current-version validation and exit-status pass-through, its
# replacement of the Java runtime after an update or rollback and removal of the data directory after a complete
# uninstall, and the versioned launchers' start of the shared runtime's java.exe, from PowerShell and from Git Bash.
# Only the Windows build runs this file; the POSIX launchers' tests are in updater-launcher.bats.

bats_require_minimum_version 1.5.0

load windows-helpers

# Builds the fake java.exe that every test's runtimes copy, and finds the Windows directory.
# Side effects: Exports FAKE_JAVA, the fake's path, and WINDOWS_DIRECTORY, the Windows directory's Windows form.
setup_file()
{
	export FAKE_JAVA="${BATS_FILE_TMPDIR}/java.exe"
	build_fake_java "${FAKE_JAVA}"
	WINDOWS_DIRECTORY="$(windows_directory)"
	export WINDOWS_DIRECTORY
}

# Creates a test-owned updater data directory, mirroring the installed Windows layout, beneath the build output.
# Side effects: Creates TEST_ROOT, which teardown removes, holding the data directory DATA.
setup()
{
	mkdir -p "${BATS_TEST_DIRNAME}/../../../target"
	TEST_ROOT="$(mktemp -d "${BATS_TEST_DIRNAME}/../../../target/updater-launcher-windows.XXXXXX")"
	TEST_ROOT="$(cd -P "${TEST_ROOT}" && pwd -P)"
	DATA="${TEST_ROOT}/updater with spaces"
	RESOURCE_DIRECTORY="${BATS_TEST_DIRNAME}/../../../../updater/src/main/resources/com/holdmyspot/cat/common/updater/cli"
	SAVED_RESULT='{"result":"done","message":"a & b <c> \"d\" $HOME `x` %PATH%"}'
	create_data_directory "${DATA}"
}

# Removes only the directory created for this test, including after failed setup or assertions.
teardown()
{
	if [ -n "${TEST_ROOT:-}" ]; then
		rm -rf -- "${TEST_ROOT}"
	fi
}

# Creates an updater data directory as an installation lays it out on Windows: the stable launcher in bin\, current
# naming version 1.0, that version's PowerShell and POSIX launchers, and the shared runtime.
# Arguments: the absolute path of the data directory to create.
# Side effects: Creates the directory with bin\, 1.0\bin\, state\ and runtime\, whose marker file reads "installed".
create_data_directory()
{
	mkdir -p "$1/bin" "$1/1.0/bin" "$1/state"
	cp "${RESOURCE_DIRECTORY}/stable-launcher.ps1" "$1/bin/cat-updater.ps1"
	cp "${RESOURCE_DIRECTORY}/versioned-launcher.ps1" "$1/1.0/bin/cat-updater.ps1"
	cp "${RESOURCE_DIRECTORY}/versioned-launcher.sh" "$1/1.0/bin/cat-updater"
	printf '1.0\n' > "$1/current"
	write_runtime "$1/runtime" installed 0
}

# Writes a Java runtime whose java.exe is the fake, as an installation holds it, an update stages it or a rollback
# keeps it.
# Arguments: the absolute path of the runtime directory, its marker text, and the status of its java -version.
# Side effects: Creates the directory with bin\java.exe, version-status and a marker file that names the runtime.
write_runtime()
{
	mkdir -p "$1/bin"
	cp "${FAKE_JAVA}" "$1/bin/java.exe"
	printf '%s\n' "$2" > "$1/marker"
	printf '%s\n' "$3" > "$1/version-status"
}

# Makes the installed runtime's fake stand in for an updater operation that staged a runtime: the operation saved its
# final result and its replacement request, and its JVM exits with the status that asks the stable launcher to put the
# staged runtime in place.
# Arguments: the absolute path of the data directory, the operation, the version it replaces and the version it selects.
# Side effects: Writes state\runtime-swap, state\runtime-swap-result.json, which holds SAVED_RESULT, and java-status.
request_swap()
{
	printf '%s\n' "${SAVED_RESULT}" > "$1/state/runtime-swap-result.json"
	printf '%s\n' "$2" "$3" "$4" 0 - - > "$1/state/runtime-swap"
	printf '78\n' > "$1/java-status"
}

# Makes the installed runtime's fake stand in for the updater's complete uninstall: the operation saved its final
# result, and its JVM exits with the status that asks the stable launcher to delete the data directory.
# Arguments: the absolute path of the data directory.
# Side effects: Writes state\uninstall-result.json, which holds SAVED_RESULT, and java-status.
request_removal()
{
	printf '%s\n' "${SAVED_RESULT}" > "$1/state/uninstall-result.json"
	printf '79\n' > "$1/java-status"
}

# Writes text with each backslash and quotation mark escaped for a JSON string.
# Arguments: the text.
# Output: the escaped text.
json_escape()
{
	printf '%s' "$1" | sed 's/\\/\\\\/g; s/"/\\"/g'
}

# Prints the not_swapped result that the Windows stable launcher writes for a data directory.
# Arguments: the data directory.
# Output: the JSON result.
not_swapped_result()
{
	printf '{"result":"not_swapped","message":"CAT could not switch the Java runtime in %s; %s"}' \
		"$(json_escape "$(cygpath -w "$1")")" 'the next updater operation restores the previous release.'
}

# Prints the not_removed result that the Windows stable launcher writes for a data directory.
# Arguments: the data directory.
# Output: the JSON result.
not_removed_result()
{
	printf '{"result":"not_removed","message":"CAT could not delete %s"}' "$(json_escape "$(cygpath -w "$1")")"
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

# Prints the arguments that the updater's launchers pass to java.exe before the updater operation's own, one
# [argument] per line.
# Arguments: the data directory in the form that names it to Java.
# Output: the arguments.
fixed_java_arguments()
{
	printf '[%s]\n' -Xshare:auto -Xlog:all=off:stdout -Xlog:all=warning:stderr -XX:+PerfDisableSharedMem \
		-Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 -Djdk.http.auth.tunneling.disabledSchemes= \
		--enable-native-access=com.holdmyspot.cat.common.cli \
		"-Djava.util.logging.config.file=$1\\runtime\\conf\\logging.properties" --module-path "$1\\1.0\\app" \
		--add-modules org.slf4j.nop -m com.holdmyspot.cat.common.updater/com.holdmyspot.cat.common.updater.cli.UpdaterMain
}

# Windows PowerShell parses the command line that Git Bash builds for -File itself, so an argument that holds a space,
# a quotation mark or nothing reaches the launcher only if that parsing keeps it, and reaches java.exe only if the
# launcher quotes it again for the Microsoft C runtime's rules.
@test "the Windows stable launcher passes spaces, quotation marks and an empty argument through -File to Java" {
	run powershell_file "${DATA}/bin/cat-updater.ps1" status 'first argument' '' 'quote " here' 'back\slash\' \
		'percent %PATH% and $dollar'

	[ "${status}" -eq 0 ]
	expected="$(fixed_java_arguments "$(cygpath -w "${DATA}")"
		printf '[%s]\n' status 'first argument' '' 'quote " here' 'back\slash\' 'percent %PATH% and $dollar')"
	[ "$(java_arguments "${DATA}")" = "${expected}" ]
}

@test "the Windows stable launcher runs Java in the Windows directory with only the listed environment variables" {
	HOME="$(cygpath -w "${TEST_ROOT}/home")"
	LOCALAPPDATA="$(cygpath -w "${TEST_ROOT}/local app data")"
	USERPROFILE="$(cygpath -w "${TEST_ROOT}/profile")"
	export HOME LOCALAPPDATA USERPROFILE XDG_DATA_HOME='C:\xdg data' XDG_CONFIG_HOME='C:\xdg configuration'
	export LANG=en_US.UTF-8 LC_ALL=en_US.UTF-8 HTTPS_PROXY=http://proxy.invalid:3128 NO_PROXY=localhost
	export JAVA_TOOL_OPTIONS=hostile JDK_JAVA_OPTIONS=hostile _JAVA_OPTIONS=hostile CLASSPATH=hostile \
		JLINK_VM_OPTIONS=hostile CODEX_HOME=hostile CLAUDE_CONFIG_DIR=hostile
	unset https_proxy no_proxy

	run powershell_file "${DATA}/bin/cat-updater.ps1" status

	[ "${status}" -eq 0 ]
	[ "$(sed -n 's/^env:\([^=]*\)=.*/\1/p' "${DATA}/java-invocation.txt")" = "HOME
HTTPS_PROXY
LANG
LC_ALL
LOCALAPPDATA
NO_PROXY
PATH
SYSTEMROOT
USERPROFILE
XDG_CONFIG_HOME
XDG_DATA_HOME" ]
	[ "$(java_variable "${DATA}" PATH)" = "${WINDOWS_DIRECTORY}\\System32" ]
	[ "$(java_variable "${DATA}" HOME)" = "${HOME}" ]
	[ "$(java_variable "${DATA}" XDG_DATA_HOME)" = 'C:\xdg data' ]
	[ "$(java_variable "${DATA}" LOCALAPPDATA)" = "${LOCALAPPDATA}" ]
	[ "$(java_variable "${DATA}" HTTPS_PROXY)" = http://proxy.invalid:3128 ]
	[ "$(sed -n 's/^cwd=//p' "${DATA}/java-invocation.txt")" = "${WINDOWS_DIRECTORY}" ]
}

@test "the Windows stable launcher passes another updater status and its output through unchanged" {
	printf '%s\n' '{"result":"refused"}' > "${DATA}/java-output"
	printf '5\n' > "${DATA}/java-status"

	run --separate-stderr powershell_file "${DATA}/bin/cat-updater.ps1" update --harness claude

	[ "${status}" -eq 5 ]
	[ "${output}" = '{"result":"refused"}' ]
	[ -z "${stderr}" ]
	[ "$(cat "${DATA}/current")" = 1.0 ]
}

@test "the Windows stable launcher rejects a second version line with status 3 instead of running an updater" {
	printf '1.0\n1.1\n' > "${DATA}/current"

	run --separate-stderr powershell_file "${DATA}/bin/cat-updater.ps1" status

	[ "${status}" -eq 3 ]
	[ -z "${output}" ]
	[ "${stderr}" = 'Invalid updater current file: expected one line of two or three decimal numbers.' ]
	[ ! -e "${DATA}/java-invocation.txt" ]
}

# The reader that receives the launcher's output checks which version current selects when the first line arrives, so a
# launcher that printed the saved result before it selected the new updater fails this test.
@test "after an update the Windows stable launcher puts the staged runtime in place before it prints the result" {
	request_swap "${DATA}" update 1.0 1.1
	write_runtime "${DATA}/runtime.next" staged 0
	mkdir -p "${DATA}/1.1/bin"

	output=$({
		launcher_status=0
		powershell_file "${DATA}/bin/cat-updater.ps1" update || launcher_status=$?
		printf '%s\n' "${launcher_status}" > "${TEST_ROOT}/launcher-status"
	} | {
		IFS= read -r line
		printf '%s<%s>' "${line}" "$(cat "${DATA}/current")"
	})

	[ "${output}" = "${SAVED_RESULT}<1.1>" ]
	[ "$(cat "${TEST_ROOT}/launcher-status")" = 0 ]
	[ "$(cat "${DATA}/runtime/marker")" = staged ]
	[ "$(cat "${DATA}/runtime.previous/marker")" = installed ]
	[ ! -e "${DATA}/runtime.next" ]
	[ ! -e "${DATA}/state/runtime-swap" ]
	[ ! -e "${DATA}/state/runtime-swap-result.json" ]
	[ -d "${DATA}/1.0" ]
}

# The updater installs the selected release's stable launcher by renaming a new file over the running one, which
# Windows refuses while a process holds the file open. A launcher that read on from the new file would run the
# replacement's lines instead of finishing the runtime replacement.
@test "the Windows stable launcher finishes replacing the runtime after a new stable launcher replaces its file" {
	for _ in $(seq 400); do printf "Write-Output 'replacement ran'; exit 99\n"; done > "${TEST_ROOT}/new-launcher"
	cp "${TEST_ROOT}/new-launcher" "${DATA}/bin/.cat-updater-new.tmp"
	request_swap "${DATA}" update 1.0 1.1
	write_runtime "${DATA}/runtime.next" staged 0
	mkdir -p "${DATA}/1.1/bin"

	run powershell_file "${DATA}/bin/cat-updater.ps1" update

	[ "${status}" -eq 0 ]
	[ "${output}" = "${SAVED_RESULT}" ]
	[ "$(cat "${DATA}/current")" = 1.1 ]
	[ "$(cat "${DATA}/runtime/marker")" = staged ]
	[ ! -e "${DATA}/state/runtime-swap" ]
	cmp -s "${TEST_ROOT}/new-launcher" "${DATA}/bin/cat-updater.ps1"

	run powershell_file "${DATA}/bin/cat-updater.ps1" status

	[ "${status}" -eq 99 ]
	[ "${output}" = 'replacement ran' ]
}

@test "after a rollback the Windows stable launcher restores the kept runtime and deletes the replaced version" {
	request_swap "${DATA}" rollback 1.0 0.9
	write_runtime "${DATA}/runtime.previous" kept 0
	mkdir -p "${DATA}/0.9/bin"

	run powershell_file "${DATA}/bin/cat-updater.ps1" rollback --harness claude

	[ "${status}" -eq 0 ]
	[ "${output}" = "${SAVED_RESULT}" ]
	[ "$(cat "${DATA}/current")" = 0.9 ]
	[ "$(cat "${DATA}/runtime/marker")" = kept ]
	[ ! -e "${DATA}/runtime.previous" ]
	[ ! -e "${DATA}/runtime.next" ]
	[ ! -e "${DATA}/1.0" ]
	[ -d "${DATA}/0.9" ]
	[ ! -e "${DATA}/state/runtime-swap" ]
}

@test "the Windows stable launcher restores the runtime and reports not_swapped when the staged runtime fails" {
	request_swap "${DATA}" update 1.0 1.1
	write_runtime "${DATA}/runtime.next" staged 1
	mkdir -p "${DATA}/1.1/bin"

	run --separate-stderr powershell_file "${DATA}/bin/cat-updater.ps1" update

	[ "${status}" -eq 8 ]
	[ "${output}" = "$(not_swapped_result "${DATA}")" ]
	[ "${stderr}" = "The new runtime's java -version failed; the previous runtime was restored." ]
	[ "$(cat "${DATA}/current")" = 1.0 ]
	[ "$(cat "${DATA}/runtime/marker")" = installed ]
	[ "$(cat "${DATA}/runtime.next/marker")" = staged ]
	[ ! -e "${DATA}/runtime.previous" ]
	[ -f "${DATA}/state/runtime-swap" ]
	[ ! -e "${DATA}/state/runtime-swap-result.json" ]
}

@test "the Windows stable launcher changes nothing and reports not_swapped when no staged runtime exists" {
	request_swap "${DATA}" update 1.0 1.1

	run --separate-stderr powershell_file "${DATA}/bin/cat-updater.ps1" update

	[ "${status}" -eq 8 ]
	[ "${output}" = "$(not_swapped_result "${DATA}")" ]
	[ "$(cat "${DATA}/current")" = 1.0 ]
	[ "$(cat "${DATA}/runtime/marker")" = installed ]
	[ ! -e "${DATA}/runtime.previous" ]
}

# A launcher that stopped between its two renames left the request and the moved-aside runtime behind; the next start
# completes the launcher's part of the replacement, so that the updater it runs can undo or finish the rest.
@test "the Windows stable launcher puts back a runtime that a stopped launcher moved aside, then runs the updater" {
	rm -rf -- "${DATA}/runtime"
	write_runtime "${DATA}/runtime.previous" installed 0
	write_runtime "${DATA}/runtime.next" staged 0
	printf '%s\n' update 1.0 1.1 9999999 - - > "${DATA}/state/runtime-swap"

	run powershell_file "${DATA}/bin/cat-updater.ps1" status

	[ "${status}" -eq 0 ]
	[[ "$(java_arguments "${DATA}")" == *$'\n[status]' ]]
	[ "$(cat "${DATA}/runtime/marker")" = installed ]
	[ ! -e "${DATA}/runtime.previous" ]
	[ "$(cat "${DATA}/runtime.next/marker")" = staged ]
}

@test "the Windows stable launcher reports locked with status 75 while another launcher is replacing the runtime" {
	rm -rf -- "${DATA}/runtime"
	write_runtime "${DATA}/runtime.previous" installed 0
	# The Windows process identifier of the shell that runs this test, which runs until the test ends
	printf '%s\n' update 1.0 1.1 "$(cat "/proc/$$/winpid")" - - > "${DATA}/state/runtime-swap"

	run powershell_file "${DATA}/bin/cat-updater.ps1" status

	[ "${status}" -eq 75 ]
	[[ "${output}" == '{"result":"locked",'* ]]
	[ ! -e "${DATA}/java-invocation.txt" ]
	[ ! -e "${DATA}/runtime" ]
	[ "$(cat "${DATA}/runtime.previous/marker")" = installed ]
}

# The reader that receives the launcher's output checks whether the data directory still exists when the first line
# arrives, so a launcher that printed the saved result before deleting the directory fails this test.
@test "after an uninstall the Windows stable launcher deletes the data directory and its empty parent, then prints" {
	base="${TEST_ROOT}/cat-updater"
	create_data_directory "${base}/claude"
	request_removal "${base}/claude"

	output=$({
		launcher_status=0
		powershell_file "${base}/claude/bin/cat-updater.ps1" uninstall || launcher_status=$?
		printf '%s\n' "${launcher_status}" > "${TEST_ROOT}/launcher-status"
	} | {
		IFS= read -r line
		if [ -e "${base}/claude" ]; then
			printf 'printed before deletion: %s' "${line}"
		else
			printf '%s' "${line}"
		fi
	})

	[ "${output}" = "${SAVED_RESULT}" ]
	[ "$(cat "${TEST_ROOT}/launcher-status")" = 0 ]
	[ ! -e "${base}" ]
}

@test "after an uninstall the Windows stable launcher keeps a parent directory that holds another installation" {
	base="${TEST_ROOT}/cat-updater"
	create_data_directory "${base}/claude"
	request_removal "${base}/claude"
	create_data_directory "${base}/codex"

	run powershell_file "${base}/claude/bin/cat-updater.ps1" uninstall

	[ "${status}" -eq 0 ]
	[ "${output}" = "${SAVED_RESULT}" ]
	[ ! -e "${base}/claude" ]
	[ "$(cat "${base}/codex/current")" = 1.0 ]
}

@test "deleting the data directory on Windows removes a junction inside it without deleting what it points to" {
	request_removal "${DATA}"
	mkdir "${TEST_ROOT}/outside"
	printf 'kept\n' > "${TEST_ROOT}/outside/file"
	create_junction "${DATA}/state/junction to outside" "${TEST_ROOT}/outside"

	run powershell_file "${DATA}/bin/cat-updater.ps1" uninstall

	[ "${status}" -eq 0 ]
	[ ! -e "${DATA}" ]
	[ "$(cat "${TEST_ROOT}/outside/file")" = kept ]
}

@test "the Windows stable launcher refuses to delete a data directory whose parent is a junction" {
	mkdir "${TEST_ROOT}/real base"
	create_data_directory "${TEST_ROOT}/real base/claude"
	request_removal "${TEST_ROOT}/real base/claude"
	create_junction "${TEST_ROOT}/linked base" "${TEST_ROOT}/real base"

	run --separate-stderr powershell_file "${TEST_ROOT}/linked base/claude/bin/cat-updater.ps1" uninstall

	[ "${status}" -eq 7 ]
	[ "${output}" = "$(not_removed_result "${TEST_ROOT}/linked base/claude")" ]
	[[ "${stderr}" == 'Refusing to delete '* ]]
	[ "$(cat "${TEST_ROOT}/real base/claude/current")" = 1.0 ]
}

@test "the Windows stable launcher deletes nothing and reports not_removed when a complete uninstall saved no result" {
	printf '79\n' > "${DATA}/java-status"

	run --separate-stderr powershell_file "${DATA}/bin/cat-updater.ps1" uninstall

	[ "${status}" -eq 7 ]
	[ "${output}" = "$(not_removed_result "${DATA}")" ]
	[ "$(cat "${DATA}/current")" = 1.0 ]
	[ -f "${DATA}/runtime/bin/java.exe" ]
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

@test "the Windows versioned launcher passes the runtime hand-over status through without replacing the runtime" {
	request_swap "${DATA}" update 1.0 1.1
	write_runtime "${DATA}/runtime.next" staged 0

	run powershell_file "${DATA}/1.0/bin/cat-updater.ps1" update

	[ "${status}" -eq 78 ]
	[ "$(cat "${DATA}/runtime/marker")" = installed ]
	[ "$(cat "${DATA}/runtime.next/marker")" = staged ]
}

@test "the Windows versioned launcher reports a missing shared runtime with one diagnostic and status 3" {
	rm -rf -- "${DATA}/runtime"

	run --separate-stderr powershell_file "${DATA}/1.0/bin/cat-updater.ps1" status

	[ "${status}" -eq 3 ]
	[ -z "${output}" ]
	java="$(cygpath -w "${DATA}/runtime/bin/java.exe")"
	[ "${stderr}" = "CAT updater runtime is missing: ${java} is not a file; rerun the CAT installation command." ]
}

# The release's entry script cat.sh runs setup through the archive's POSIX versioned launcher under Git Bash, which
# names the runtime and application by their POSIX paths; java.exe can open them only in their Windows form.
@test "the POSIX versioned launcher that the entry script runs in Git Bash gives java.exe Windows paths" {
	run "${DATA}/1.0/bin/cat-updater" setup --harness claude --home 'C:\a home'

	[ "${status}" -eq 0 ]
	arguments="$(java_arguments "${DATA}")"
	[ "$(printf '%s\n' "${arguments}" | sed -n '$!d; =')" -eq 20 ]
	[ "$(printf '%s\n' "${arguments}" | tail -n 5)" = "$(printf '[%s]\n' setup --harness claude --home 'C:\a home')" ]
	logging="$(printf '%s\n' "${arguments}" | sed -n 's/^\[-Djava\.util\.logging\.config\.file=\(.*\)\]$/\1/p')"
	modules="$(printf '%s\n' "${arguments}" | sed -n '/^\[--module-path\]$/{n;s/^\[\(.*\)\]$/\1/p;}')"
	[[ "${logging}" == [A-Za-z]:* ]]
	[[ "${modules}" == [A-Za-z]:* ]]
	[ "$(cygpath -u "${logging}")" = "${DATA}/runtime/conf/logging.properties" ]
	[ "$(cygpath -u "${modules}")" = "${DATA}/1.0/app" ]
}
