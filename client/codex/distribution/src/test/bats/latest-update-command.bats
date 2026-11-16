#!/usr/bin/env bats

# Runs the command written by the production serializer through the packaged run-part launcher and a recorder,
# proving that it passes the released --json-before-operation argument order and preserves output and exit status.

load ../../../../../common/distribution/src/test/bats/bootstrap-helpers
load ../../../../../common/distribution/src/test/bats/windows-helpers

# Removes only the runtime fixture this test successfully claimed.
teardown() {
	if [[ -n "${UPDATE_RUNTIME_OWNED:-}" ]]; then
		rm -rf -- "${UPDATE_RUNTIME_OWNED}"
		UPDATE_RUNTIME_OWNED=
	fi
}

@test "latest update runs through packaged run-part and preserves installer arguments, output, and exit status" {
	local generated_runtime="${BATS_TEST_DIRNAME}/../../../../../common/distribution/target/runtime/codex"
	local fixture="${BATS_TEST_DIRNAME}/../../../../installer/target/test-files/latest-update-runtime"
	local command_file="${BATS_TEST_DIRNAME}/../../../../installer/target/test-files/latest-update-command.txt"
	mkdir -- "${fixture}"
	UPDATE_RUNTIME_OWNED="${fixture}"
	cp -R "${generated_runtime}/." "${fixture}/"
	fixture=$(cd "${fixture}" && pwd -P)
	project="${TEST_ROOT}/project"
	mkdir -p "${project}/.cat"
	cd "${project}"
	launcher="${fixture}/bin/installer"
	script=$(cat "${command_file}")

	case "$(current_platform)" in
		windows-*)
			launcher="${fixture}/bin/installer.ps1"
			cat > "${launcher}" <<'SCRIPT'
[IO.File]::WriteAllLines($env:INSTALL_ARGUMENTS, $args)
[Console]::WriteLine('recorder stdout')
exit 17
SCRIPT
			run powershell_command "${script}" "CODEX_THREAD_ID=123e4567-e89b-12d3-a456-426614174000" \
				"CODEX_HOME=$(cygpath -w "${CODEX_HOME}")" "INSTALL_ARGUMENTS=$(cygpath -w "${INSTALL_ARGUMENTS}")"
			;;
		*)
			cat > "${launcher}" <<'SCRIPT'
#!/usr/bin/env sh
printf '%s\n' "$@" > "${INSTALL_ARGUMENTS}"
printf '%s\n' 'recorder stdout'
exit 17
SCRIPT
			chmod +x "${launcher}"
			run env CODEX_THREAD_ID=123e4567-e89b-12d3-a456-426614174000 CODEX_HOME="${CODEX_HOME}" \
				INSTALL_ARGUMENTS="${INSTALL_ARGUMENTS}" bash -c "${script}"
			;;
	esac

	[ "${status}" -eq 17 ]
	[[ "${output}" == *"recorder stdout"* ]]
	[ "$(cat "${INSTALL_ARGUMENTS}")" = "--json
install
--harness
codex" ]
}
