#!/usr/bin/env bats

# Tests how the release's PowerShell entry script cat.ps1 installs CAT on Windows when Windows PowerShell runs it as a
# script block, as the installation instructions do: which plugin archive it downloads, how it checks the archive's
# digest, how it chooses the harness, which install of the plugin's installer it runs and from where, how the
# installer's output and exit status reach the caller, that it deletes its temporary folder, and how it refuses retired
# switches. The tests publish a fake release whose installer only records how it was run. Only the Windows build runs
# this file; bootstrap-common.bats, bootstrap-claude.bats and bootstrap-codex.bats test the POSIX entry script cat.sh.

bats_require_minimum_version 1.5.0

load bootstrap-helpers
load windows-helpers

# Publishes a fake CAT release v1.0 for Windows: one plugin archive for each harness and the release's cat.ps1, which
# the release's generator fills with the archives' SHA-256 digests.
#
# Each plugin archive holds the plugin folder, whose bin\installer.ps1 stands for the plugin's installer and whose
# bin\runtime holds a marker for the plugin's Java runtime. That installer writes each argument it receives on its own
# line to INSTALL_ARGUMENTS, writes the path it ran from and the content of the runtime marker in its bin folder to
# INSTALL_LOCATION, prints an install result and exits with FAKE_INSTALL_STATUS, or zero when that is unset. It writes
# its files without the byte order mark that Set-Content writes in Windows PowerShell.
#
# Arguments:
#   $1 - "tampered" to replace each archive's content after its digest is pinned, so that no download matches it;
#     absent otherwise.
#
# Environment:
#   TEST_ROOT - The test's own folder, which holds the archives' staging folder and digest list.
#   FAKE_RELEASE_DIRECTORY - The folder to publish the release into.
#   COMMON_RELEASE_DIRECTORY - The folder of the release's entry script templates and their generator.
#
# Side effects:
#   Creates FAKE_RELEASE_DIRECTORY with the plugin archives and cat.ps1.
create_windows_release()
{
	local staging="${TEST_ROOT}/release staging"
	local harness plugin_directory
	mkdir -p "${FAKE_RELEASE_DIRECTORY}"
	: > "${TEST_ROOT}/SHA256SUMS.txt"
	for harness in claude codex; do
		plugin_directory="$(plugin_name "${harness}")"
		mkdir -p "${staging}/${plugin_directory}/bin/runtime"
		printf 'release runtime\n' > "${staging}/${plugin_directory}/bin/runtime/marker"
		cat > "${staging}/${plugin_directory}/bin/installer.ps1" <<'SCRIPT'
[IO.File]::WriteAllLines($env:INSTALL_ARGUMENTS, [string[]] $args)
$runtime = Get-Content -LiteralPath (Join-Path $PSScriptRoot 'runtime\marker')
[IO.File]::WriteAllLines($env:INSTALL_LOCATION, [string[]] @($PSCommandPath, $runtime))
Write-Output '{"result":"installed"}'
exit [int] $env:FAKE_INSTALL_STATUS
SCRIPT
		pack_archive "${staging}/${plugin_directory}" "${FAKE_RELEASE_DIRECTORY}/${plugin_directory}.tar.gz"
		sha256_line "${FAKE_RELEASE_DIRECTORY}/${plugin_directory}.tar.gz" >> "${TEST_ROOT}/SHA256SUMS.txt"
	done
	sh "${COMMON_RELEASE_DIRECTORY}/generate-cat-sh.sh" "${COMMON_RELEASE_DIRECTORY}/cat.ps1" v1.0 \
		"${TEST_ROOT}/SHA256SUMS.txt" "${FAKE_RELEASE_DIRECTORY}/cat.ps1"
	if [ "${1:-}" = tampered ]; then
		for harness in claude codex; do
			printf 'tampered' > "${FAKE_RELEASE_DIRECTORY}/$(plugin_name "${harness}").tar.gz"
		done
	fi
}

# Runs the fake release's cat.ps1 as the installation instructions do, as a script block in Windows PowerShell, with a
# function in place of the cmdlet that downloads release assets: it appends each URL to URL_LOG and copies the asset of
# that name from FAKE_RELEASE_DIRECTORY. An error that cat.ps1 throws is printed after "error: ", and ends PowerShell
# with $LASTEXITCODE when a program set a nonzero one, and with status 1 otherwise.
#
# Arguments:
#   $1 - The parameters to pass to cat.ps1, such as -Harness claude.
#   $2... - NAME=VALUE assignments of the environment that the agent's shell adds, such as CLAUDECODE=1.
#
# Environment:
#   FAKE_RELEASE_DIRECTORY - The fake release, from create_windows_release.
#   CLAUDE_CONFIG_DIR, CODEX_HOME - The harness homes.
#   TMPDIR - The folder that cat.ps1 receives as TEMP, for its temporary folder.
#   INSTALL_ARGUMENTS, INSTALL_LOCATION - The files that the fake installer writes.
#   URL_LOG - The file that the fake download appends each URL to.
#
# Output:
#   Writes what PowerShell writes; standard input is empty.
#
# Exit status:
#   Returns PowerShell's exit status.
run_entry()
{
	local parameters="$1"
	shift
	: > "${URL_LOG}"
	local script='$ErrorActionPreference = "Stop"
function Invoke-WebRequest
{
	param([string] $Uri, [string] $OutFile, [switch] $UseBasicParsing)
	[IO.File]::AppendAllText($env:URL_LOG, "$Uri`n")
	$name = $Uri.Substring($Uri.LastIndexOf("/") + 1)
	Copy-Item -LiteralPath (Join-Path $env:FAKE_RELEASE_DIRECTORY $name) -Destination $OutFile
}
$global:LASTEXITCODE = 0
try
{
	& ([scriptblock]::Create([IO.File]::ReadAllText((Join-Path $env:FAKE_RELEASE_DIRECTORY "cat.ps1")))) '"${parameters}"'
}
catch
{
	[Console]::Out.WriteLine("error: " + $_.Exception.Message)
	if ($LASTEXITCODE) { exit $LASTEXITCODE }
	exit 1
}'
	(
		# PowerShell 7's module paths, which Git Bash may inherit, make Windows PowerShell load incompatible utility
		# modules that hide Get-FileHash, so Windows PowerShell builds its own default module path
		unset PSModulePath
		powershell_command "${script}" \
			"FAKE_RELEASE_DIRECTORY=$(cygpath -w "${FAKE_RELEASE_DIRECTORY}")" \
			"CLAUDE_CONFIG_DIR=$(cygpath -w "${CLAUDE_CONFIG_DIR}")" \
			"CODEX_HOME=$(cygpath -w "${CODEX_HOME}")" \
			"TEMP=$(cygpath -w "${TMPDIR}")" \
			"INSTALL_ARGUMENTS=$(cygpath -w "${INSTALL_ARGUMENTS}")" \
			"INSTALL_LOCATION=$(cygpath -w "${INSTALL_LOCATION}")" \
			"URL_LOG=$(cygpath -w "${URL_LOG}")" \
			"$@" < /dev/null
	)
}

# Checks that the last run of cat.ps1 ran the install of the installer in its extracted plugin archive for a harness,
# beside that plugin's runtime, with exactly the arguments that install accepts.
#
# Arguments:
#   $1 - The harness, claude or codex.
#   $2 - The harness home that install must receive.
#
# Environment:
#   INSTALL_ARGUMENTS, INSTALL_LOCATION - What the fake installer recorded.
#   TMPDIR - The folder that cat.ps1 received as TEMP.
expect_windows_install_ran_for()
{
	[ "$(tr -d '\r' < "${INSTALL_ARGUMENTS}")" = "--home
$(cygpath -w "$2")
install
--harness
$1" ]
	local launcher
	launcher="$(sed -n '1p' "${INSTALL_LOCATION}" | tr -d '\r')"
	# Windows PowerShell gives the installer its path with each short 8.3 folder name, such as RUNNER~1, in its long
	# form
	[[ "${launcher}" == "$(cygpath -w -l "${TMPDIR}")\\"*"\\$(plugin_name "$1")\\bin\\installer.ps1" ]]
	[ "$(sed -n '2p' "${INSTALL_LOCATION}" | tr -d '\r')" = "release runtime" ]
}

@test "cat.ps1 downloads only the named harness's plugin and runs its installer's install from the extracted plugin" {
	create_windows_release

	run --separate-stderr run_entry '-Harness claude'

	[ "${status}" -eq 0 ]
	[[ "${output}" == *'{"result":"installed"}'* ]]
	[ "$(tr -d '\r' < "${URL_LOG}")" = "${RELEASE_DOWNLOAD_URL}/$(plugin_name claude).tar.gz" ]
	expect_windows_install_ran_for claude "${CLAUDE_CONFIG_DIR}"
	[ -z "$(ls -A "${TMPDIR}")" ]
}

@test "cat.ps1 passes the installer's nonzero exit status to its caller and still deletes its temporary folder" {
	create_windows_release

	run --separate-stderr run_entry '-Harness codex' FAKE_INSTALL_STATUS=5

	[ "${status}" -eq 5 ]
	[[ "${output}" == *"error: CAT's installer install exited with status 5."* ]]
	expect_windows_install_ran_for codex "${CODEX_HOME}"
	[ -z "$(ls -A "${TMPDIR}")" ]
}

@test "cat.ps1 without -Harness installs for Claude Code in a Claude Code shell" {
	create_windows_release

	run --separate-stderr run_entry '' CLAUDECODE=1

	[ "${status}" -eq 0 ]
	expect_windows_install_ran_for claude "${CLAUDE_CONFIG_DIR}"
}

# Checks that cat.ps1, run without -Harness in a shell whose harness it cannot tell, asks for -Harness and changes
# nothing.
#
# Arguments:
#   $@ - NAME=VALUE assignments of the shell's harness variables.
#
# Environment:
#   URL_LOG, INSTALL_ARGUMENTS - What the fake download and the fake installer recorded.
expect_harness_refused()
{
	run --separate-stderr run_entry '' "$@"

	[ "${status}" -eq 1 ]
	[[ "${output}" == *"-Harness claude or -Harness codex"* ]]
	[ ! -s "${URL_LOG}" ]
	[ ! -e "${INSTALL_ARGUMENTS}" ]
}

@test "cat.ps1 without -Harness refuses a shell of both harnesses or of neither before downloading anything" {
	create_windows_release
	# A shell that no harness started sets neither harness's variable, so it names no harness
	neither=()
	# A shell whose environment holds both harnesses' variables names two harnesses
	both=(CLAUDECODE=1 CODEX_THREAD_ID=thread)

	expect_harness_refused "${neither[@]}"
	expect_harness_refused "${both[@]}"
}

@test "cat.ps1 refuses a plugin archive whose digest differs from the pinned one without running its installer" {
	create_windows_release tampered

	run --separate-stderr run_entry '-Harness codex'

	[ "${status}" -eq 1 ]
	[[ "${output}" == *"does not match the SHA-256 digest"* ]]
	[ ! -e "${INSTALL_ARGUMENTS}" ]
	[ -z "$(ls -A "${TMPDIR}")" ]
}

@test "cat.ps1 refuses each retired switch before downloading anything and says how to uninstall CAT" {
	create_windows_release

	for retired in -UpdaterOnly -Uninstall -DeleteData; do
		run --separate-stderr run_entry "-Harness codex ${retired}"

		[ "${status}" -eq 1 ]
		[[ "${output}" == *"${retired} is no longer supported"* ]]
		[[ "${output}" == *"ask your agent to uninstall CAT"* ]]
		[ ! -s "${URL_LOG}" ]
		[ ! -e "${INSTALL_ARGUMENTS}" ]
	done
}
