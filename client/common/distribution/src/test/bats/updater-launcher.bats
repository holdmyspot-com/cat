#!/usr/bin/env bats
# Tests the trusted updater launchers' environment isolation, argument forwarding and current-version validation.

# Creates a test-owned updater installation and fake child executable beneath the build output.
# Side effects: Creates TEST_ROOT, which teardown removes.
setup()
{
	mkdir -p "${BATS_TEST_DIRNAME}/../../../target"
	TEST_ROOT="$(mktemp -d "${BATS_TEST_DIRNAME}/../../../target/updater-launcher.XXXXXX")"
	export HOME="${TEST_ROOT}/home"
	export XDG_DATA_HOME="${TEST_ROOT}/data"
	export XDG_CONFIG_HOME="${TEST_ROOT}/configuration"
	export LOCALAPPDATA="${TEST_ROOT}/local app data"
	mkdir -p "${HOME}" "${XDG_DATA_HOME}" "${XDG_CONFIG_HOME}" "${LOCALAPPDATA}"
	DATA="${TEST_ROOT}/updater with spaces"
	mkdir -p "${DATA}/bin" "${DATA}/1.0/bin"
	RESOURCE_DIRECTORY="${BATS_TEST_DIRNAME}/../../../../updater/src/main/resources/com/holdmyspot/cat/common/updater/cli"
	cp "${RESOURCE_DIRECTORY}/stable-launcher.sh" "${DATA}/bin/cat-updater"
	chmod +x "${DATA}/bin/cat-updater"
	printf '1.0\n' > "${DATA}/current"
	cat > "${DATA}/1.0/bin/cat-updater" <<'CHILD'
#!/bin/sh
if [ "${JAVA_TOOL_OPTIONS+x}${JDK_JAVA_OPTIONS+x}${_JAVA_OPTIONS+x}${CLASSPATH+x}${JLINK_VM_OPTIONS+x}${LD_PRELOAD+x}${CODEX_HOME+x}${CLAUDE_CONFIG_DIR+x}" != '' ]; then
	exit 99
fi
printf 'child'
printf '<%s>' "$@"
CHILD
	chmod +x "${DATA}/1.0/bin/cat-updater"
}

# Removes only the directory created for this test, including after failed setup or assertions.
teardown()
{
	if [ -n "${TEST_ROOT:-}" ]; then
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

@test "the direct versioned launcher removes injected JVM settings and supplies the runtime's fixed options" {
	cp "${RESOURCE_DIRECTORY}/versioned-launcher.sh" "${DATA}/1.0/bin/cat-updater"
	cat > "${DATA}/1.0/bin/java" <<'JAVA'
#!/bin/sh
if [ "${JAVA_TOOL_OPTIONS+x}${JDK_JAVA_OPTIONS+x}${_JAVA_OPTIONS+x}${CLASSPATH+x}${JLINK_VM_OPTIONS+x}" != '' ]; then
	exit 99
fi
printf '<%s>' "$@"
JAVA
	chmod +x "${DATA}/1.0/bin/java"

	run env JAVA_TOOL_OPTIONS=hostile JDK_JAVA_OPTIONS=hostile _JAVA_OPTIONS=hostile CLASSPATH=hostile \
		JLINK_VM_OPTIONS=hostile "${DATA}/1.0/bin/cat-updater" status 'argument with spaces'

	[ "${status}" -eq 0 ]
	[[ "${output}" == *'<-Xshare:auto>'* ]]
	[[ "${output}" == *'<-Xlog:all=off:stdout><-Xlog:all=warning:stderr>'* ]]
	[[ "${output}" == *'<-XX:+PerfDisableSharedMem>'* ]]
	[[ "${output}" == *'<-Dstdout.encoding=UTF-8><-Dstderr.encoding=UTF-8>'* ]]
	[[ "${output}" == *'<-Djdk.http.auth.tunneling.disabledSchemes=>'* ]]
	[[ "${output}" == *'<-m><com.holdmyspot.cat.common.updater/com.holdmyspot.cat.common.updater.cli.UpdaterMain><status><argument with spaces>' ]]
}
