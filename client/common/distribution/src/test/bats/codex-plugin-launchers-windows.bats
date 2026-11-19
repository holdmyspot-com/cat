#!/usr/bin/env bats

# Tests the batch launchers of the Windows Codex plugin that the build generated, which has no POSIX launchers:
# PowerShell, in which Codex runs commands on Windows, runs each launcher by the absolute path without an extension that
# CAT's skills name, such as <plugin>\bin\plugin-info, and the plugin's java.cmd wrapper passes a startup cache trained
# through it, which Java accepts.

bats_require_minimum_version 1.5.0

load bootstrap-helpers
load windows-helpers

@test "PowerShell runs each launcher of the Windows Codex plugin by its path without an extension" {
	bin="${TEST_ROOT}/codex plugin/bin"
	mkdir -p "${bin}/runtime/modules"
	for file in "${GENERATED_RUNTIME_DIRECTORY}/codex/bin/"*; do
		if [ -f "${file}" ]; then
			cp "${file}" "${bin}"
		fi
	done
	# With no modules, each program stops at once with Java's report that its module is missing, which shows that the
	# launcher ran Java. A path that PowerShell does not resolve to a batch launcher is reported instead of run, because
	# PowerShell hands a file that it cannot run to the program that Windows associates with the file.
	script='foreach ($launcher in Get-ChildItem -LiteralPath $env:BIN -Filter *.cmd) {
	if ($launcher.BaseName -eq "java") { continue }
	$path = Join-Path $env:BIN $launcher.BaseName
	$command = Get-Command -Name $path
	if ($command.Extension -in ".bat", ".cmd") {
		$output = (& $path 2>&1 | ForEach-Object { "$_" }) -join " "
		$launcher.BaseName + ": " + $output
	}
	else { "resolved " + $command.Source }
}'

	run --separate-stderr powershell_command "${script}" "BIN=$(cygpath -w "${bin}")" < /dev/null

	output="${output//$'\r'/}"
	[[ "${output}" != *"resolved "* ]]
	launchers=0
	for launcher in "${bin}/"*.cmd; do
		name="${launcher##*/}"
		name="${name%.cmd}"
		if [ "${name}" = java ]; then
			continue
		fi
		module=$(sed -n 's/.* -m \([^/]*\)\/.*/\1/p' "${launcher}")
		[ -n "${module}" ]
		printf '%s\n' "${output}" | grep -F "${name}: " | grep -F "Module ${module} not found"
		launchers=$((launchers + 1))
	done
	[ "${launchers}" -gt 0 ]
}

@test "the Windows Codex plugin's java.cmd passes a startup cache trained through it, which Java accepts" {
	plugin="${TEST_ROOT}/trained plugin"
	cp -R "${GENERATED_RUNTIME_DIRECTORY}/codex" "${plugin}"
	mkdir "${plugin}/bin/aot"
	environment=("BIN=$(cygpath -w "${plugin}/bin")" "CONFIGURATION=$(cygpath -w "${TEST_ROOT}/training.aotconf")")
	# PowerShell lines that name the wrapper and the arguments that start the plugin's plugin-info program
	prefix='$java = Join-Path $env:BIN java.cmd
$program = "--add-modules", "org.slf4j.nop", "-m",
	"com.holdmyspot.cat.codex.cli/com.holdmyspot.cat.codex.CodexPluginInfo"
'
	# Windows PowerShell fails a program whose standard error it redirects once the program writes there, so the
	# command redirects nothing and asks for --version, which Java writes to standard output
	flags="${prefix}"'& $java -XX:+PrintCommandLineFlags --version'

	run --separate-stderr powershell_command "${flags}" "${environment[@]}" < /dev/null

	[ "${status}" -eq 0 ]
	[[ "${output}" != *"AOTCache"* ]]

	# One run records Java's training configuration, and a second run writes the cache from it, as an installation does
	powershell_command "${prefix}"'& $java -XX:AOTMode=record "-XX:AOTConfiguration=$env:CONFIGURATION" @program
& $java -XX:AOTMode=create "-XX:AOTConfiguration=$env:CONFIGURATION" "-XX:AOTCache=$env:BIN\aot\session-start.aot" `
	@program' "${environment[@]}" < /dev/null > /dev/null 2>&1 || true
	[ -s "${plugin}/bin/aot/session-start.aot" ]

	run --separate-stderr powershell_command "${flags}" "${environment[@]}" < /dev/null

	[ "${status}" -eq 0 ]
	[[ "${output}" == *"-XX:AOTCache="*"\\aot\\session-start.aot"* ]]

	# With AOTMode=on, Java fails instead of starting without a cache that it cannot use
	run --separate-stderr powershell_command "${prefix}"'& $java -XX:AOTMode=on @program' "${environment[@]}" < /dev/null

	[ "${status}" -eq 0 ]
	[[ "${output//$'\r'/}" == "harness=codex"$'\n'* ]]
}
