#!/usr/bin/env bats
# Tests the trusted updater's POSIX launchers: the stable launcher's environment isolation, argument forwarding,
# current-version validation, exit-status pass-through, replacement of the Java runtime after an update or rollback
# and removal of the data directory after a complete uninstall, and the versioned launcher's selection of the shared
# runtime and its fixed JVM options.

bats_require_minimum_version 1.5.0

# Creates a test-owned updater data directory, mirroring the installed layout, beneath the build output.
# Side effects: Creates TEST_ROOT, which teardown removes, holding the data directory DATA with the stable launcher in
# bin/, current naming version 1.0, and a fake versioned launcher that reports its environment and arguments.
setup()
{
	mkdir -p "${BATS_TEST_DIRNAME}/../../../target"
	TEST_ROOT="$(mktemp -d "${BATS_TEST_DIRNAME}/../../../target/updater-launcher.XXXXXX")"
	TEST_ROOT="$(cd -P "${TEST_ROOT}" && pwd -P)"
	export HOME="${TEST_ROOT}/home"
	export XDG_DATA_HOME="${TEST_ROOT}/data"
	export XDG_CONFIG_HOME="${TEST_ROOT}/configuration"
	export LOCALAPPDATA="${TEST_ROOT}/local app data"
	mkdir -p "${HOME}" "${XDG_DATA_HOME}" "${XDG_CONFIG_HOME}" "${LOCALAPPDATA}"
	DATA="${TEST_ROOT}/updater with spaces"
	RESOURCE_DIRECTORY="${BATS_TEST_DIRNAME}/../../../../updater/src/main/resources/com/holdmyspot/cat/common/updater/cli"
	create_data_directory "${DATA}"
}

# Creates an updater data directory with the stable launcher, current version 1.0 and a fake versioned launcher.
# Arguments: the absolute path of the data directory to create.
# Side effects: Creates the directory and its bin/, 1.0/bin/ and state/ subdirectories.
create_data_directory()
{
	mkdir -p "$1/bin" "$1/1.0/bin" "$1/state"
	cp "${RESOURCE_DIRECTORY}/stable-launcher.sh" "$1/bin/cat-updater"
	chmod +x "$1/bin/cat-updater"
	printf '1.0\n' > "$1/current"
	cat > "$1/1.0/bin/cat-updater" <<'CHILD'
#!/bin/sh
if [ "${JAVA_TOOL_OPTIONS+x}${JDK_JAVA_OPTIONS+x}${_JAVA_OPTIONS+x}${CLASSPATH+x}${JLINK_VM_OPTIONS+x}${LD_PRELOAD+x}${CODEX_HOME+x}${CLAUDE_CONFIG_DIR+x}" != '' ]; then
	exit 99
fi
printf 'child'
printf '<%s>' "$@"
CHILD
	chmod +x "$1/1.0/bin/cat-updater"
}

# Replaces a data directory's fake versioned launcher with the real one and installs a fake shared runtime Java.
# Arguments: the absolute path of the data directory, then the body of the fake java script, which runs with the
#   variable data set to the data directory that holds it.
# Side effects: Writes 1.0/bin/cat-updater and runtime/bin/java beneath the data directory.
install_runtime()
{
	cp "${RESOURCE_DIRECTORY}/versioned-launcher.sh" "$1/1.0/bin/cat-updater"
	chmod +x "$1/1.0/bin/cat-updater"
	mkdir -p "$1/runtime/bin"
	{
		printf '%s\n' '#!/bin/sh' 'data=$(cd -P "${0%/*}/../.." && pwd -P)'
		printf '%s\n' "$2"
	} > "$1/runtime/bin/java"
	chmod +x "$1/runtime/bin/java"
}

# Installs a fake shared runtime Java that stands in for the updater's complete uninstall: it saves the final JSON
# result in state/uninstall-result.json and exits with the status that asks the stable launcher to delete the data
# directory after the JVM exits.
# The saved result contains shell metacharacters and JSON escapes, so a launcher that reinterprets it alters it.
# Arguments: the absolute path of the data directory.
# Side effects: Writes TEST_ROOT/saved-result.json, which the fake copies, and 1.0/bin/cat-updater and runtime/bin/java
#   beneath the data directory.
install_uninstalling_runtime()
{
	printf '%s\n' '{"result":"uninstalled","message":"a & b <c> \"d\" $HOME `x` %PATH%"}' > "${TEST_ROOT}/saved-result.json"
	install_runtime "$1" "cat '${TEST_ROOT}/saved-result.json' > \"\$data/state/uninstall-result.json\"
exit 79"
}

# Writes a fake Java runtime beside the installed one, as an update stages it or a rollback keeps it.
# Arguments: the absolute path of the runtime directory, its marker text, and the status with which its java exits.
# Side effects: Creates the directory with bin/java and a marker file that names the runtime.
write_runtime()
{
	mkdir -p "$1/bin"
	printf '%s\n' "$2" > "$1/marker"
	printf '#!/bin/sh\nexit %s\n' "$3" > "$1/bin/java"
	chmod +x "$1/bin/java"
}

# Installs a fake shared runtime Java that stands in for an updater operation that staged a Java runtime: it saves
# its final JSON result in state/runtime-swap-result.json, writes the swap request, and exits with the status that
# asks the stable launcher to put the staged runtime in place after the JVM exits. Its java -version succeeds.
# The saved result contains shell metacharacters and JSON escapes, so a launcher that reinterprets it alters it.
# Arguments: the absolute path of the data directory, the operation, the version it replaces and the version it
#   selects.
# Side effects: Writes TEST_ROOT/saved-swap-result.json, which the fake copies, 1.0/bin/cat-updater and runtime/
#   beneath the data directory, whose marker file reads "installed".
install_swapping_runtime()
{
	printf '%s\n' '{"result":"updated","message":"a & b <c> \"d\" $HOME `x` %PATH%"}' > "${TEST_ROOT}/saved-swap-result.json"
	install_runtime "$1" "if [ \"\$1\" = -version ]; then exit 0; fi
cat '${TEST_ROOT}/saved-swap-result.json' > \"\$data/state/runtime-swap-result.json\"
printf '%s\\n' '$2' '$3' '$4' 0 - - > \"\$data/state/runtime-swap\"
exit 78"
	printf 'installed\n' > "$1/runtime/marker"
}

# Prints the not_swapped result that the stable launcher writes for a data directory.
# Arguments: the data directory as the launcher's path names it.
# Output: the JSON result.
not_swapped_result()
{
	printf '{"result":"not_swapped","message":"CAT could not switch the Java runtime in %s; the next updater operation restores the previous release."}' "$1"
}

# Removes only the directory created for this test, including after failed setup or assertions.
teardown()
{
	if [ -n "${TEST_ROOT:-}" ]; then
		chmod -R u+rwx -- "${TEST_ROOT}" 2>/dev/null || true
		rm -rf -- "${TEST_ROOT}"
	fi
}

@test "the stable launcher removes injected runtime and harness settings before running the selected updater" {
	run env JAVA_TOOL_OPTIONS=hostile JDK_JAVA_OPTIONS=hostile _JAVA_OPTIONS=hostile CLASSPATH=hostile \
		JLINK_VM_OPTIONS=hostile LD_PRELOAD= CODEX_HOME=hostile CLAUDE_CONFIG_DIR=hostile \
		"${DATA}/bin/cat-updater" status 'argument with spaces'

	[ "${status}" -eq 0 ]
	[ "${output}" = 'child<status><argument with spaces>' ]
}

@test "the stable launcher rejects traversal instead of running a versioned updater" {
	printf '../x\n' > "${DATA}/current"

	run "${DATA}/bin/cat-updater" status

	[ "${status}" -ne 0 ]
	[[ "${output}" != *'child'* ]]
}

@test "the stable launcher rejects an empty current version instead of running an updater" {
	: > "${DATA}/current"

	run "${DATA}/bin/cat-updater" status

	[ "${status}" -ne 0 ]
	[[ "${output}" != *'child'* ]]
}

@test "the stable launcher rejects a second version line instead of running an updater" {
	printf '1.0\n1.1\n' > "${DATA}/current"

	run "${DATA}/bin/cat-updater" status

	[ "${status}" -ne 0 ]
	[[ "${output}" != *'child'* ]]
}

@test "the stable launcher rejects an extra blank version line instead of running an updater" {
	printf '1.0\n\n' > "${DATA}/current"

	run "${DATA}/bin/cat-updater" status

	[ "${status}" -ne 0 ]
	[[ "${output}" != *'child'* ]]
}

@test "the versioned launcher runs the shared runtime's Java with the fixed options and its own application modules" {
	install_runtime "${DATA}" '
if [ "${JAVA_TOOL_OPTIONS+x}${JDK_JAVA_OPTIONS+x}${_JAVA_OPTIONS+x}${CLASSPATH+x}${JLINK_VM_OPTIONS+x}" != "" ]; then
	exit 99
fi
printf "<%s>" "$@"'

	run env JAVA_TOOL_OPTIONS=hostile JDK_JAVA_OPTIONS=hostile _JAVA_OPTIONS=hostile CLASSPATH=hostile \
		JLINK_VM_OPTIONS=hostile "${DATA}/1.0/bin/cat-updater" status 'argument with spaces'

	[ "${status}" -eq 0 ]
	[ "${output}" = "<-Xshare:auto><-Xlog:all=off:stdout><-Xlog:all=warning:stderr><-XX:+PerfDisableSharedMem>\
<-Dstdout.encoding=UTF-8><-Dstderr.encoding=UTF-8><-Djdk.http.auth.tunneling.disabledSchemes=>\
<--enable-native-access=com.holdmyspot.cat.common.cli>\
<-Djava.util.logging.config.file=${DATA}/runtime/conf/logging.properties>\
<--module-path><${DATA}/1.0/app><--add-modules><org.slf4j.nop>\
<-m><com.holdmyspot.cat.common.updater/com.holdmyspot.cat.common.updater.cli.UpdaterMain>\
<status><argument with spaces>" ]
}

@test "the versioned launcher started through a symbolic link runs the runtime beside its real version directory" {
	install_runtime "${DATA}" 'printf "<%s>" "$@"'
	mkdir "${TEST_ROOT}/links"
	ln -s "${DATA}/1.0/bin/cat-updater" "${TEST_ROOT}/links/cat-updater"

	run "${TEST_ROOT}/links/cat-updater" status

	[ "${status}" -eq 0 ]
	[[ "${output}" == *"<--module-path><${DATA}/1.0/app>"*'<status>' ]]
}

@test "the versioned launcher reports a missing shared runtime with one diagnostic and status 3" {
	install_runtime "${DATA}" 'printf ran'
	rm "${DATA}/runtime/bin/java"

	run --separate-stderr "${DATA}/1.0/bin/cat-updater" status

	[ "${status}" -eq 3 ]
	[ "${output}" = '' ]
	[ "${#stderr_lines[@]}" -eq 1 ]
	[[ "${stderr}" == *"${DATA}/runtime/bin/java"* ]]
}

@test "the stable launcher passes another updater status and its output through unchanged" {
	install_runtime "${DATA}" "printf '%s\n' '{\"result\":\"refused\"}'
printf '%s\n' 'diagnostic' >&2
exit 5"

	run --separate-stderr "${DATA}/bin/cat-updater" update --harness claude

	[ "${status}" -eq 5 ]
	[ "${output}" = '{"result":"refused"}' ]
	[ "${stderr}" = 'diagnostic' ]
	[ -d "${DATA}" ]
}

# The reader that receives the launcher's output checks whether the data directory still exists when the first line
# arrives, so a launcher that printed the saved result before deleting the directory fails this test.
@test "after a complete uninstall the stable launcher deletes the data directory and only then prints the saved result" {
	install_uninstalling_runtime "${DATA}"

	run sh -c '
		{ "$1" uninstall; printf "%s\n" "$?" > "$3"; } | {
			IFS= read -r line
			if [ -e "$2" ]; then
				printf "printed before deletion: %s" "${line}"
			else
				printf "%s" "${line}"
			fi
		}' sh "${DATA}/bin/cat-updater" "${DATA}" "${TEST_ROOT}/launcher-status"

	[ "${output}" = "$(cat "${TEST_ROOT}/saved-result.json")" ]
	[ "$(cat "${TEST_ROOT}/launcher-status")" = 0 ]
	[ ! -e "${DATA}" ]
}

@test "a complete uninstall deletes the previous bundle and an unfinished runtime replacement with the data directory" {
	install_uninstalling_runtime "${DATA}"
	write_runtime "${DATA}/runtime.next" staged 0
	write_runtime "${DATA}/runtime.previous" kept 0
	mkdir -p "${DATA}/0.9/bin"
	printf 'previous\n' > "${DATA}/0.9/bin/cat-updater"
	printf '%s\n' rollback 1.0 0.9 0 - - > "${DATA}/state/runtime-swap"

	run "${DATA}/bin/cat-updater" uninstall

	[ "${status}" -eq 0 ]
	[ "${output}" = "$(cat "${TEST_ROOT}/saved-result.json")" ]
	[ ! -e "${DATA}" ]
}

@test "after a complete uninstall the stable launcher also removes the installation root that it leaves empty" {
	base="${TEST_ROOT}/cat-updater"
	create_data_directory "${base}/harness-a"
	install_uninstalling_runtime "${base}/harness-a"

	run "${base}/harness-a/bin/cat-updater" uninstall

	[ "${status}" -eq 0 ]
	[ "${output}" = "$(cat "${TEST_ROOT}/saved-result.json")" ]
	[ ! -e "${base}" ]
}

@test "after a complete uninstall the stable launcher keeps an installation root that holds another installation" {
	base="${TEST_ROOT}/cat-updater"
	create_data_directory "${base}/harness-a"
	install_uninstalling_runtime "${base}/harness-a"
	create_data_directory "${base}/harness-b"

	run "${base}/harness-a/bin/cat-updater" uninstall

	[ "${status}" -eq 0 ]
	[ "${output}" = "$(cat "${TEST_ROOT}/saved-result.json")" ]
	[ ! -e "${base}/harness-a" ]
	[ "$(cat "${base}/harness-b/current")" = 1.0 ]
}

@test "the stable launcher started through a symbolic link deletes its real data directory after a complete uninstall" {
	install_uninstalling_runtime "${DATA}"
	mkdir "${TEST_ROOT}/links"
	printf 'unrelated\n' > "${TEST_ROOT}/links/unrelated"
	ln -s "${DATA}/bin/cat-updater" "${TEST_ROOT}/links/cat-updater"

	run "${TEST_ROOT}/links/cat-updater" uninstall

	[ "${status}" -eq 0 ]
	[ "${output}" = "$(cat "${TEST_ROOT}/saved-result.json")" ]
	[ ! -e "${DATA}" ]
	[ "$(cat "${TEST_ROOT}/links/unrelated")" = unrelated ]
}

@test "the stable launcher reports a data directory that it could not delete with the not_removed result and status 7" {
	data="${TEST_ROOT}/updater \"quoted\" \\ data"
	create_data_directory "${data}"
	install_uninstalling_runtime "${data}"
	mkdir "${data}/state/locked"
	printf 'kept\n' > "${data}/state/locked/file"
	chmod a-w "${data}/state/locked"

	run --separate-stderr "${data}/bin/cat-updater" uninstall

	[ "${status}" -eq 7 ]
	[ "${output}" = '{"result":"not_removed","message":"CAT could not delete '"${TEST_ROOT}"'/updater \"quoted\" \\ data"}' ]
	[ "${stderr}" = "${data}" ]
	[ -e "${data}/state/locked/file" ]
}

@test "the stable launcher deletes nothing and reports not_removed when a complete uninstall saved no result" {
	install_runtime "${DATA}" 'exit 79'

	run --separate-stderr "${DATA}/bin/cat-updater" uninstall

	[ "${status}" -eq 7 ]
	[ "${output}" = '{"result":"not_removed","message":"CAT could not delete '"${DATA}"'"}' ]
	[ -n "${stderr}" ]
	[ -x "${DATA}/runtime/bin/java" ]
	[ -f "${DATA}/current" ]
}

@test "the stable launcher deletes nothing and reports not_removed when the saved uninstall result is unreadable" {
	install_runtime "${DATA}" 'printf "{}\n" > "$data/state/uninstall-result.json"
chmod a-r "$data/state/uninstall-result.json"
exit 79'

	run --separate-stderr "${DATA}/bin/cat-updater" uninstall

	[ "${status}" -eq 7 ]
	[ "${output}" = '{"result":"not_removed","message":"CAT could not delete '"${DATA}"'"}' ]
	[ -n "${stderr}" ]
	[ -f "${DATA}/state/uninstall-result.json" ]
	[ -f "${DATA}/current" ]
}

@test "the stable launcher runs the updater from the root directory with a fixed PATH whatever the caller's are" {
	install_runtime "${DATA}" 'printf "<%s><%s>" "${PATH}" "$(pwd -P)"'
	mkdir "${TEST_ROOT}/caller bin"

	run sh -c 'cd "$1" && PATH="$2:${PATH}" "$3" status' sh "${HOME}" "${TEST_ROOT}/caller bin" "${DATA}/bin/cat-updater"

	[ "${status}" -eq 0 ]
	[ "${output}" = '</usr/bin:/bin></>' ]
}

# Each program that a caller could plant on PATH records that it ran and does nothing else, so a launcher that found
# rm, rmdir, cat, readlink or env on the caller's PATH leaves the data directory in place or creates the marker.
@test "programs on the caller's PATH cannot stand in for those that complete an uninstall" {
	install_uninstalling_runtime "${DATA}"
	hostile="${TEST_ROOT}/hostile bin"
	mkdir "${hostile}" "${TEST_ROOT}/links"
	for program in rm rmdir cat readlink env sed ls; do
		printf '#!/bin/sh\n: > "%s/planted program ran"\n' "${TEST_ROOT}" > "${hostile}/${program}"
		chmod +x "${hostile}/${program}"
	done
	ln -s "${DATA}/bin/cat-updater" "${TEST_ROOT}/links/cat-updater"

	run /usr/bin/env PATH="${hostile}:${PATH}" "${TEST_ROOT}/links/cat-updater" uninstall

	[ "${status}" -eq 0 ]
	[ "${output}" = "$(cat "${TEST_ROOT}/saved-result.json")" ]
	[ ! -e "${DATA}" ]
	[ ! -e "${TEST_ROOT}/planted program ran" ]
}

@test "the stable launcher refuses to delete a data directory that its path reaches through a symbolic link" {
	install_uninstalling_runtime "${DATA}"
	ln -s "${DATA}" "${TEST_ROOT}/linked data"

	run --separate-stderr "${TEST_ROOT}/linked data/bin/cat-updater" uninstall

	[ "${status}" -eq 7 ]
	[ "${output}" = '{"result":"not_removed","message":"CAT could not delete '"${TEST_ROOT}"'/linked data"}' ]
	[[ "${stderr}" == *"${TEST_ROOT}/linked data" ]]
	[ -f "${DATA}/current" ]
	[ -L "${TEST_ROOT}/linked data" ]
}

@test "the stable launcher refuses to delete a data directory whose parent is a symbolic link" {
	mkdir "${TEST_ROOT}/real parent"
	create_data_directory "${TEST_ROOT}/real parent/data"
	install_uninstalling_runtime "${TEST_ROOT}/real parent/data"
	ln -s "${TEST_ROOT}/real parent" "${TEST_ROOT}/linked parent"

	run --separate-stderr "${TEST_ROOT}/linked parent/data/bin/cat-updater" uninstall

	[ "${status}" -eq 7 ]
	[ "${output}" = '{"result":"not_removed","message":"CAT could not delete '"${TEST_ROOT}"'/linked parent/data"}' ]
	[ -f "${TEST_ROOT}/real parent/data/current" ]
}

@test "deleting the data directory removes symbolic links inside it without deleting what they point to" {
	install_uninstalling_runtime "${DATA}"
	mkdir "${TEST_ROOT}/outside"
	printf 'kept\n' > "${TEST_ROOT}/outside/file"
	ln -s "${TEST_ROOT}/outside" "${DATA}/state/link to outside"
	ln -s "${TEST_ROOT}/outside/file" "${DATA}/state/link to outside file"

	run "${DATA}/bin/cat-updater" uninstall

	[ "${status}" -eq 0 ]
	[ ! -e "${DATA}" ]
	[ "$(cat "${TEST_ROOT}/outside/file")" = kept ]
}

# The reader that receives the launcher's output checks which version current selects when the first line arrives, so a
# launcher that printed the saved result before it selected the new updater fails this test.
@test "after an update hands over the runtime the stable launcher puts the staged runtime in place and only then prints the result" {
	install_swapping_runtime "${DATA}" update 1.0 1.1
	write_runtime "${DATA}/runtime.next" staged 0
	mkdir -p "${DATA}/1.1/bin"

	run sh -c '
		{ "$1" update; printf "%s\n" "$?" > "$3"; } | {
			IFS= read -r line
			printf "%s<%s>" "${line}" "$(cat "$2/current")"
		}' sh "${DATA}/bin/cat-updater" "${DATA}" "${TEST_ROOT}/launcher-status"

	[ "${output}" = "$(cat "${TEST_ROOT}/saved-swap-result.json")<1.1>" ]
	[ "$(cat "${TEST_ROOT}/launcher-status")" = 0 ]
	[ "$(cat "${DATA}/runtime/marker")" = staged ]
	[ "$(cat "${DATA}/runtime.previous/marker")" = installed ]
	[ ! -e "${DATA}/runtime.next" ]
	[ ! -e "${DATA}/state/runtime-swap" ]
	[ ! -e "${DATA}/state/runtime-swap-result.json" ]
	[ -d "${DATA}/1.0" ]
}

@test "after a rollback hands over the runtime the stable launcher restores the kept runtime and deletes the replaced bundle" {
	install_swapping_runtime "${DATA}" rollback 1.0 0.9
	write_runtime "${DATA}/runtime.previous" kept 0
	mkdir -p "${DATA}/0.9/bin"

	run "${DATA}/bin/cat-updater" rollback --harness claude

	[ "${status}" -eq 0 ]
	[ "${output}" = "$(cat "${TEST_ROOT}/saved-swap-result.json")" ]
	[ "$(cat "${DATA}/current")" = 0.9 ]
	[ "$(cat "${DATA}/runtime/marker")" = kept ]
	[ ! -e "${DATA}/runtime.previous" ]
	[ ! -e "${DATA}/runtime.next" ]
	[ ! -e "${DATA}/1.0" ]
	[ -d "${DATA}/0.9" ]
	[ ! -e "${DATA}/state/runtime-swap" ]
}

@test "the stable launcher restores the installed runtime and reports not_swapped with status 8 when the staged runtime does not run" {
	data="${TEST_ROOT}/updater \"quoted\" \\ data"
	create_data_directory "${data}"
	install_swapping_runtime "${data}" update 1.0 1.1
	write_runtime "${data}/runtime.next" staged 1
	mkdir -p "${data}/1.1/bin"

	run --separate-stderr "${data}/bin/cat-updater" update

	[ "${status}" -eq 8 ]
	[ "${output}" = "$(not_swapped_result "${TEST_ROOT}"'/updater \"quoted\" \\ data')" ]
	[ -n "${stderr}" ]
	[ "$(cat "${data}/current")" = 1.0 ]
	[ "$(cat "${data}/runtime/marker")" = installed ]
	[ "$(cat "${data}/runtime.next/marker")" = staged ]
	[ ! -e "${data}/runtime.previous" ]
	[ -f "${data}/state/runtime-swap" ]
	[ ! -e "${data}/state/runtime-swap-result.json" ]
}

@test "the stable launcher changes nothing and reports not_swapped when no staged runtime exists" {
	install_swapping_runtime "${DATA}" update 1.0 1.1

	run --separate-stderr "${DATA}/bin/cat-updater" update

	[ "${status}" -eq 8 ]
	[ "${output}" = "$(not_swapped_result "${DATA}")" ]
	[ "$(cat "${DATA}/current")" = 1.0 ]
	[ "$(cat "${DATA}/runtime/marker")" = installed ]
	[ ! -e "${DATA}/runtime.previous" ]
}

@test "the stable launcher changes nothing and reports not_swapped when the request replaces another version" {
	install_swapping_runtime "${DATA}" update 0.9 1.1
	write_runtime "${DATA}/runtime.next" staged 0

	run --separate-stderr "${DATA}/bin/cat-updater" update

	[ "${status}" -eq 8 ]
	[ "${output}" = "$(not_swapped_result "${DATA}")" ]
	[ "$(cat "${DATA}/current")" = 1.0 ]
	[ "$(cat "${DATA}/runtime/marker")" = installed ]
	[ "$(cat "${DATA}/runtime.next/marker")" = staged ]
}

@test "the stable launcher changes nothing and reports not_swapped when the updater saved no result" {
	install_runtime "${DATA}" 'if [ "$1" = -version ]; then exit 0; fi
printf "%s\n" update 1.0 1.1 0 - - > "$data/state/runtime-swap"
exit 78'
	printf 'installed\n' > "${DATA}/runtime/marker"
	write_runtime "${DATA}/runtime.next" staged 0

	run --separate-stderr "${DATA}/bin/cat-updater" update

	[ "${status}" -eq 8 ]
	[ "${output}" = "$(not_swapped_result "${DATA}")" ]
	[ "$(cat "${DATA}/current")" = 1.0 ]
	[ "$(cat "${DATA}/runtime/marker")" = installed ]
	[ "$(cat "${DATA}/runtime.next/marker")" = staged ]
}

@test "the versioned launcher passes the runtime hand-over status through without replacing the runtime" {
	install_swapping_runtime "${DATA}" update 1.0 1.1
	write_runtime "${DATA}/runtime.next" staged 0

	run "${DATA}/1.0/bin/cat-updater" update

	[ "${status}" -eq 78 ]
	[ "$(cat "${DATA}/runtime/marker")" = installed ]
	[ "$(cat "${DATA}/runtime.next/marker")" = staged ]
}

@test "the stable launcher puts back a runtime that a stopped launcher had moved aside before it runs the updater" {
	write_runtime "${DATA}/runtime.previous" installed 0
	write_runtime "${DATA}/runtime.next" staged 0
	printf '%s\n' update 1.0 1.1 9999999 - - > "${DATA}/state/runtime-swap"

	run "${DATA}/bin/cat-updater" status

	[ "${status}" -eq 0 ]
	[ "${output}" = 'child<status>' ]
	[ "$(cat "${DATA}/runtime/marker")" = installed ]
	[ ! -e "${DATA}/runtime.previous" ]
	[ "$(cat "${DATA}/runtime.next/marker")" = staged ]
}

@test "the stable launcher reports locked with status 75 while another launcher is replacing the runtime" {
	write_runtime "${DATA}/runtime.previous" installed 0
	write_runtime "${DATA}/runtime.next" staged 0
	printf '%s\n' update 1.0 1.1 "$$" - - > "${DATA}/state/runtime-swap"

	run "${DATA}/bin/cat-updater" status

	[ "${status}" -eq 75 ]
	[[ "${output}" == '{"result":"locked",'* ]]
	[[ "${output}" != *'child'* ]]
	[ ! -e "${DATA}/runtime" ]
	[ "$(cat "${DATA}/runtime.previous/marker")" = installed ]
}
