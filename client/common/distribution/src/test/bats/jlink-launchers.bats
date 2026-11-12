#!/usr/bin/env bats

# Tests the template that every generated plugin launcher shares: a launcher runs its program from a plugin path that
# contains spaces, without Bubblewrap even where it cannot create a namespace, and Java can name a file that is not ASCII
# in the caller's C locale. A launcher finds Java only through the plugin's bin/runtime-binding: it passes its JVM
# options, the plugin's module path and its arguments to the bound java, discards the JVM options that the environment
# supplies, and refuses an unusable binding with one line naming it and exit status 3. A launcher starts Java from the
# plugin's startup cache bin/aot/session-start.aot when that file exists, and a cache that Java cannot use changes
# nothing that the program's caller sees.

bats_require_minimum_version 1.5.0

load bootstrap-helpers

@test "generated launchers run their programs from a path with spaces where bwrap fails and name non-ASCII files in the C locale" {
	runtime="${TEST_ROOT}/generated runtime"
	cp -R "${GENERATED_RUNTIME_DIRECTORY}/codex" "${runtime}"
	create_failing_bwrap
	temporary="${TEST_ROOT}/temp café"
	mkdir -p "${temporary}/run-part-1"
	file="${temporary}/run-part-1/output.txt"
	printf 'first\n' > "${file}"
	printf '0\n' > "${file}.exit-status"

	run env PATH="${FAKE_BIN}:${PATH}" LC_ALL=C LANG=C "${runtime}/bin/plugin-info"

	[ "${status}" -eq 0 ]
	[[ "${output}" == "harness=codex"$'\n'* ]]

	run env -u CODEX_THREAD_ID PATH="${FAKE_BIN}:${PATH}" TMPDIR="${temporary}" LC_ALL=C LANG=C "${runtime}/bin/read-part" \
		"${file}"

	[ "${status}" -eq 0 ]
	[ "${output}" = "$(printf 'first\n[End of %s. The file was deleted]' "${file}")" ]
}

# Copies the generated Codex plugin to a path with spaces and binds it, through a link whose path also holds spaces,
# to a runtime whose java prints the environment's JVM options and its arguments, one per line.
#
# Outputs:
#   PLUGIN - the copied plugin
#   BINDING - the plugin's runtime binding
create_echo_runtime_plugin()
{
	PLUGIN="${TEST_ROOT}/copied plugin"
	cp -R "${GENERATED_RUNTIME_DIRECTORY}/codex" "${PLUGIN}"
	BINDING="${PLUGIN}/bin/runtime-binding"
	local runtime="${TEST_ROOT}/echo runtime"
	mkdir -p "${runtime}/bin"
	cat > "${runtime}/bin/java" <<'SCRIPT'
#!/bin/sh
for name in LD_PRELOAD LD_LIBRARY_PATH LD_AUDIT BASH_ENV ENV DYLD_INSERT_LIBRARIES DYLD_LIBRARY_PATH; do
	eval "printf '%s=%s\\n' \"\${name}\" \"\${${name}-unset}\""
done
printf 'JAVA_TOOL_OPTIONS=%s\n' "${JAVA_TOOL_OPTIONS-unset}"
printf 'JDK_JAVA_OPTIONS=%s\n' "${JDK_JAVA_OPTIONS-unset}"
printf 'CLASSPATH=%s\n' "${CLASSPATH-unset}"
for argument in "$@"; do
	printf '[%s]\n' "${argument}"
done
SCRIPT
	chmod +x "${runtime}/bin/java"
	ln -s "${runtime}" "${TEST_ROOT}/linked runtime"
	printf '%s\n' "${TEST_ROOT}/linked runtime" > "${BINDING}"
}

# Asserts that the last run printed only one line, naming the binding, and exited with the status of an unusable
# binding. The line names the binding in the launcher's real folder, which differs from BINDING where the test's
# temporary folder lies under a link, as /var does on macOS.
#
# Arguments:
#   $1 - the text that the line holds after the binding's path
assert_unusable_binding()
{
	local binding
	binding="$(cd -P "${BINDING%/*}" && pwd -P)/${BINDING##*/}"
	[ "${status}" -eq 3 ]
	[ "${#lines[@]}" -eq 1 ]
	[[ "${output}" == "CAT cannot start: ${binding} $1; run the CAT entry command again to repair the installation." ]]
}

@test "a launcher passes its JVM options, the plugin's module path and its arguments to the bound java" {
	create_echo_runtime_plugin

	# The loader variables are empty or name no file, so that the commands that run before the launcher removes them
	# load nothing
	run env -u TMPDIR JAVA_TOOL_OPTIONS=-Dinjected=1 JDK_JAVA_OPTIONS=-Dinjected=2 CLASSPATH=/injected LC_ALL=C.UTF-8 \
		LD_PRELOAD= LD_LIBRARY_PATH="${TEST_ROOT}/no libraries" LD_AUDIT= BASH_ENV="${TEST_ROOT}/no script" \
		ENV="${TEST_ROOT}/no script" DYLD_INSERT_LIBRARIES= DYLD_LIBRARY_PATH="${TEST_ROOT}/no libraries" \
		"${PLUGIN}/bin/plugin-info" "first argument" '' 'quote " and $dollar'

	[ "${status}" -eq 0 ]
	modules="$(cd -P "${PLUGIN}/bin/runtime/modules" && pwd -P)"
	expected="LD_PRELOAD=unset
LD_LIBRARY_PATH=unset
LD_AUDIT=unset
BASH_ENV=unset
ENV=unset
DYLD_INSERT_LIBRARIES=unset
DYLD_LIBRARY_PATH=unset
JAVA_TOOL_OPTIONS=unset
JDK_JAVA_OPTIONS=unset
CLASSPATH=unset
[-Xlog:all=off:stdout]
[-Xlog:all=warning:stderr]
[-Xlog:aot*=off:stderr]
[-Xlog:cds*=off:stderr]
[-XX:+PerfDisableSharedMem]
[-Dstdout.encoding=UTF-8]
[-Dstderr.encoding=UTF-8]
[-Djdk.http.auth.tunneling.disabledSchemes=]
[--enable-native-access=com.holdmyspot.cat.common.cli]
[--module-path]
[${modules}]
[--add-modules]
[org.slf4j.nop]
[-m]
[com.holdmyspot.cat.codex.cli/com.holdmyspot.cat.codex.CodexPluginInfo]
[first argument]
[]
[quote \" and \$dollar]"
	[ "${output}" = "${expected}" ]
}

@test "a launcher passes the plugin's startup cache to the bound java only when the cache exists" {
	create_echo_runtime_plugin
	bin="$(cd -P "${PLUGIN}/bin" && pwd -P)"

	run env -u TMPDIR "${PLUGIN}/bin/plugin-info"

	[ "${status}" -eq 0 ]
	[[ "${output}" != *"AOTCache"* ]]

	mkdir "${PLUGIN}/bin/aot"
	printf 'cache' > "${PLUGIN}/bin/aot/session-start.aot"

	run env -u TMPDIR "${PLUGIN}/bin/plugin-info"

	[ "${status}" -eq 0 ]
	[[ "${output}" == *$'\nCLASSPATH=unset\n[-XX:AOTCache='"${bin}"$'/aot/session-start.aot]\n[-Xlog:all=off:stdout]\n'* ]]

	run env TMPDIR="${TEST_ROOT}/session temp" "${PLUGIN}/bin/plugin-info"

	[ "${status}" -eq 0 ]
	[[ "${output}" == *$'\n[-XX:AOTCache='"${bin}"$'/aot/session-start.aot]\n[-Djava.io.tmpdir='"${TEST_ROOT}"$'/session temp]\n[-Xlog:all=off:stdout]\n'* ]]
}

# Trains a startup cache for a copy of the generated Codex plugin at another path, as an installation at that path
# would, so that the cache does not match a plugin anywhere else.
#
# Arguments:
#   $1 - the copy to train the cache in
#
# Outputs:
#   $1/bin/aot/session-start.aot - the cache
train_cache_elsewhere()
{
	cp -R "${GENERATED_RUNTIME_DIRECTORY}/codex" "$1"
	mkdir "$1/bin/aot"
	"$1/bin/java" -XX:AOTMode=record -XX:AOTConfiguration="${TEST_ROOT}/training.aotconf" \
		--add-modules org.slf4j.nop -m com.holdmyspot.cat.codex.cli/com.holdmyspot.cat.codex.CodexPluginInfo >/dev/null 2>&1
	"$1/bin/java" -XX:AOTMode=create -XX:AOTConfiguration="${TEST_ROOT}/training.aotconf" \
		-XX:AOTCache="$1/bin/aot/session-start.aot" \
		--add-modules org.slf4j.nop -m com.holdmyspot.cat.codex.cli/com.holdmyspot.cat.codex.CodexPluginInfo >/dev/null 2>&1
	[ -s "$1/bin/aot/session-start.aot" ]
}

@test "a launcher's output stays the same and its standard error empty with a foreign, truncated or invalid cache" {
	plugin="${TEST_ROOT}/plugin"
	cp -R "${GENERATED_RUNTIME_DIRECTORY}/codex" "${plugin}"

	run --separate-stderr "${plugin}/bin/plugin-info"

	[ "${status}" -eq 0 ]
	[ -z "${stderr}" ]
	expected="${output}"
	[[ "${expected}" == "harness=codex"$'\n'* ]]

	train_cache_elsewhere "${TEST_ROOT}/other plugin"
	foreign="${TEST_ROOT}/other plugin/bin/aot/session-start.aot"
	mkdir "${plugin}/bin/aot"
	cache="${plugin}/bin/aot/session-start.aot"
	size=$(wc -c < "${foreign}")
	for kind in foreign truncated invalid; do
		case "${kind}" in
			foreign) cp "${foreign}" "${cache}" ;;
			truncated) head -c "$((size / 2))" "${foreign}" > "${cache}" ;;
			invalid) printf 'not a cache\n' > "${cache}" ;;
		esac

		run --separate-stderr "${plugin}/bin/plugin-info"

		[ "${status}" -eq 0 ]
		[ -z "${stderr}" ]
		[ "${output}" = "${expected}" ]
	done
}

@test "a launcher run through a link and the java wrapper find the plugin's bin folder" {
	create_echo_runtime_plugin
	ln -s "${PLUGIN}/bin/session-start" "${TEST_ROOT}/linked launcher"

	run "${TEST_ROOT}/linked launcher"

	[ "${status}" -eq 0 ]
	[[ "${output}" == *$'\n[com.holdmyspot.cat.codex.cli/com.holdmyspot.cat.codex.CodexSessionStartHook]'* ]]

	run "${PLUGIN}/bin/java" -version

	[ "${status}" -eq 0 ]
	modules="$(cd -P "${PLUGIN}/bin/runtime/modules" && pwd -P)"
	[[ "${output}" == *$'\n[--module-path]\n['"${modules}"$']\n[-version]' ]]
}

@test "a launcher refuses a missing binding" {
	create_echo_runtime_plugin
	rm "${BINDING}"

	run "${PLUGIN}/bin/plugin-info"

	assert_unusable_binding 'is missing'
}

@test "a launcher refuses a binding that names a relative path" {
	create_echo_runtime_plugin
	printf 'linked runtime\n' > "${BINDING}"

	run "${PLUGIN}/bin/plugin-info"

	assert_unusable_binding 'names a relative path'
}

@test "a launcher refuses a binding that holds more than one line or lacks its line ending" {
	create_echo_runtime_plugin
	runtime="$(cat "${BINDING}")"
	printf '%s\n%s\n' "${runtime}" "${runtime}" > "${BINDING}"

	run "${PLUGIN}/bin/plugin-info"

	assert_unusable_binding 'must hold exactly one line, the absolute path of a Java runtime'

	printf '%s' "${runtime}" > "${BINDING}"

	run "${PLUGIN}/bin/plugin-info"

	assert_unusable_binding 'must hold exactly one line, the absolute path of a Java runtime'

	: > "${BINDING}"

	run "${PLUGIN}/bin/plugin-info"

	assert_unusable_binding 'must hold exactly one line, the absolute path of a Java runtime'
}

@test "a launcher refuses a binding that names a path that is not normalized" {
	create_echo_runtime_plugin
	for runtime in "${TEST_ROOT}/linked runtime/" "${TEST_ROOT}//linked runtime" "${TEST_ROOT}/./linked runtime" \
		"${TEST_ROOT}/../${TEST_ROOT##*/}/linked runtime" "${TEST_ROOT}/linked runtime/."; do
		printf '%s\n' "${runtime}" > "${BINDING}"

		run "${PLUGIN}/bin/plugin-info"

		assert_unusable_binding 'names a path that is not normalized'
	done
}

@test "a launcher refuses a binding that names a runtime without an executable java" {
	create_echo_runtime_plugin
	mkdir "${TEST_ROOT}/empty runtime"
	printf '%s\n' "${TEST_ROOT}/empty runtime" > "${BINDING}"

	run "${PLUGIN}/bin/plugin-info"

	assert_unusable_binding 'names a runtime without an executable bin/java'

	mkdir "${TEST_ROOT}/empty runtime/bin"
	printf '#!/bin/sh\n' > "${TEST_ROOT}/empty runtime/bin/java"

	run "${PLUGIN}/bin/plugin-info"

	assert_unusable_binding 'names a runtime without an executable bin/java'
}
