# Runs this updater version on Windows on its installation's runtime beside its version directory, with fixed JVM
# options; environment-provided JVM options are discarded. The updater receives the caller's PATH and working directory,
# because setup finds the harness program on that PATH and refuses data locations inside that directory.
# Arguments: the updater operation and its arguments, forwarded to Java without interpretation.
# Output: one updater JSON result.
# Exit status: the updater operation's status, or 3 when the installation's runtime has no java.exe.
Set-StrictMode -Version 3.0
$ErrorActionPreference = 'Stop'

# Quotes one argument so that a program built with the Microsoft C runtime, such as java.exe, reads it back unchanged.
# Arguments: the argument.
# Output: the quoted argument.
function ConvertTo-CommandLineArgument([string] $argument)
{
	if ($argument.Length -gt 0 -and $argument -notmatch '[\s"]')
	{
		return $argument
	}
	# Backslashes are plain characters unless they precede a quotation mark, where each one must be doubled.
	$escaped = [regex]::Replace($argument, '(\\*)"', { param($match) $match.Groups[1].Value * 2 + '\"' })
	$escaped = [regex]::Replace($escaped, '(\\+)$', { param($match) $match.Groups[1].Value * 2 })
	return '"' + $escaped + '"'
}

$versionDirectory = Split-Path -Parent $PSScriptRoot
$runtime = Join-Path (Split-Path -Parent $versionDirectory) 'runtime'
$java = Join-Path $runtime 'bin\java.exe'
if (-not (Test-Path -LiteralPath $java -PathType Leaf))
{
	$message = "CAT updater runtime is missing: $java is not a file; rerun the CAT installation command.`n"
	$bytes = (New-Object Text.UTF8Encoding $false).GetBytes($message)
	$stream = [Console]::OpenStandardError()
	$stream.Write($bytes, 0, $bytes.Length)
	$stream.Flush()
	exit 3
}

$javaArguments = @('-Xshare:auto', '-Xlog:all=off:stdout', '-Xlog:all=warning:stderr', '-XX:+PerfDisableSharedMem',
	'-Dstdout.encoding=UTF-8', '-Dstderr.encoding=UTF-8', '-Djdk.http.auth.tunneling.disabledSchemes=',
	'--enable-native-access=com.holdmyspot.cat.common.cli',
	"-Djava.util.logging.config.file=$(Join-Path $runtime 'conf\logging.properties')",
	'--module-path', (Join-Path $versionDirectory 'app'), '--add-modules', 'org.slf4j.nop',
	'-m', 'com.holdmyspot.cat.common.updater/com.holdmyspot.cat.common.updater.cli.UpdaterMain') + $args
$start = New-Object Diagnostics.ProcessStartInfo
$start.FileName = $java
$start.Arguments = ($javaArguments | ForEach-Object { ConvertTo-CommandLineArgument $_ }) -join ' '
$start.UseShellExecute = $false
# PowerShell's location can differ from the process's working directory, so the caller's location is passed on.
$start.WorkingDirectory = (Get-Location -PSProvider FileSystem).ProviderPath
# Java inherits this launcher's standard streams, so its output reaches the caller unchanged.
foreach ($name in @('JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS', '_JAVA_OPTIONS', 'CLASSPATH', 'JLINK_VM_OPTIONS'))
{
	$start.EnvironmentVariables.Remove($name)
}
$process = [Diagnostics.Process]::Start($start)
$process.WaitForExit()
exit $process.ExitCode
