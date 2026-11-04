# Starts the installed updater selected by current on Windows, from the Windows directory with PATH set to its
# System32 directory and only the documented environment variables, and completes a complete uninstall by deleting the
# updater data directory after the updater's JVM has exited.
# Arguments: the updater operation and its arguments, forwarded to Java without interpretation.
# Output: the selected updater's JSON result; after a complete uninstall, the result that the updater saved, printed
#   only once the data directory no longer exists, or a not_removed result naming the directory.
# Exit status: the selected updater's status; 0 after a complete uninstall whose data directory was deleted; 7 with a
#   not_removed result, deleting nothing more, when that directory could not be deleted, when it or its parent is a
#   reparse point such as a symbolic link or junction, or when the updater saved no readable result, in which case the
#   directory's path is written to standard error; or 3 when current is malformed or the shared runtime has no java.exe.
Set-StrictMode -Version 3.0
$ErrorActionPreference = 'Stop'

# A fixed search path and working directory keep the caller's PATH and current directory from choosing a program that
# this launcher or the updater runs. The Windows directory comes from the operating system, not from SystemRoot,
# which the caller controls.
$windows = [Environment]::GetFolderPath([Environment+SpecialFolder]::Windows)
$fixedPath = Join-Path $windows 'System32'
$env:PATH = $fixedPath
Set-Location -LiteralPath $windows
[Environment]::CurrentDirectory = $windows

# Writes text to a standard stream as UTF-8 bytes, so that the console's code page cannot change it.
# Arguments: the stream, from [Console]::OpenStandardOutput or OpenStandardError, and the text.
function Write-Utf8([IO.Stream] $stream, [string] $text)
{
	$bytes = (New-Object Text.UTF8Encoding $false).GetBytes($text)
	$stream.Write($bytes, 0, $bytes.Length)
	$stream.Flush()
}

# Writes a diagnostic line to standard error and ends the launcher with status 3.
# Arguments: the diagnostic.
function Stop-Launcher([string] $message)
{
	Write-Utf8 ([Console]::OpenStandardError()) "$message`n"
	exit 3
}

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

# Indicates whether a file system entry exists and is a reparse point, such as a symbolic link or junction.
# Arguments: the absolute path of the entry.
function Test-ReparsePoint([string] $path)
{
	$item = Get-Item -LiteralPath $path -Force -ErrorAction SilentlyContinue
	return $null -ne $item -and ($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0
}

# Deletes a directory tree without following a reparse point inside it: a symbolic link or junction is removed as an
# entry, leaving what it points to.
# Arguments: the directory or file to delete.
# Errors: stops at the first entry that cannot be deleted.
function Remove-Tree([IO.FileSystemInfo] $entry)
{
	$isReparsePoint = ($entry.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0
	if ($entry -is [IO.DirectoryInfo])
	{
		if (-not $isReparsePoint)
		{
			foreach ($child in $entry.GetFileSystemInfos())
			{
				Remove-Tree $child
			}
			$entry.Attributes = [IO.FileAttributes]::Directory
		}
		# A non-recursive delete of a directory symbolic link or junction removes only the link.
		[IO.Directory]::Delete($entry.FullName, $false)
		return
	}
	if (-not $isReparsePoint)
	{
		$entry.Attributes = [IO.FileAttributes]::Normal
	}
	[IO.File]::Delete($entry.FullName)
}

$data = Split-Path -Parent (Split-Path -Parent $PSCommandPath)
$currentPath = Join-Path $data 'current'
try
{
	$current = [IO.File]::ReadAllText($currentPath)
}
catch
{
	Stop-Launcher "Invalid updater current file: $currentPath cannot be read."
}
if ($current -notmatch '\A([0-9]+\.[0-9]+(\.[0-9]+)?)\n?\z')
{
	Stop-Launcher 'Invalid updater current file: expected one line of two or three decimal numbers.'
}
$version = $Matches[1]
$versionDirectory = Join-Path $data $version
$runtime = Join-Path $data 'runtime'
$java = Join-Path $runtime 'bin\java.exe'
if (-not (Test-Path -LiteralPath $java -PathType Leaf))
{
	Stop-Launcher "CAT updater runtime is missing: $java is not a file; rerun the CAT installation command."
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
$start.WorkingDirectory = $windows
# Java inherits this launcher's standard streams, so its output reaches the caller unchanged.
$start.EnvironmentVariables.Clear()
foreach ($name in @('HOME', 'XDG_DATA_HOME', 'XDG_CONFIG_HOME', 'LOCALAPPDATA', 'USERPROFILE', 'SYSTEMROOT', 'LANG',
	'LC_ALL', 'HTTPS_PROXY', 'NO_PROXY'))
{
	$value = [Environment]::GetEnvironmentVariable($name, 'Process')
	if ($null -ne $value)
	{
		$start.EnvironmentVariables[$name] = $value
	}
}
$start.EnvironmentVariables['PATH'] = $fixedPath
$process = [Diagnostics.Process]::Start($start)
$process.WaitForExit()
$status = $process.ExitCode
$process.Dispose()

# Status 79 means that the updater finished a complete uninstall, saved its final result in the data directory, and
# left the deletion of that directory, including the runtime it ran on, to this launcher.
if ($status -ne 79)
{
	exit $status
}

# Reports that the data directory still exists after a complete uninstall and ends the launcher with status 7.
function Exit-NotRemoved
{
	$escaped = $data.Replace('\', '\\').Replace('"', '\"')
	Write-Utf8 ([Console]::OpenStandardOutput()) "{`"result`":`"not_removed`",`"message`":`"CAT could not delete $escaped`"}`n"
	Write-Utf8 ([Console]::OpenStandardError()) "$data`n"
	exit 7
}

$resultFile = Join-Path $data 'state\uninstall-result.json'
try
{
	$result = [IO.File]::ReadAllBytes($resultFile)
}
catch
{
	$result = $null
}
if ($null -eq $result -or $result.Length -eq 0)
{
	Write-Utf8 ([Console]::OpenStandardError()) "The updater saved no readable uninstall result in $resultFile; nothing was deleted.`n"
	Exit-NotRemoved
}
# Deleting through a reparse point would remove a directory other than the one the installation recorded.
if ((Test-ReparsePoint $data) -or (Test-ReparsePoint (Split-Path -Parent $data)))
{
	Write-Utf8 ([Console]::OpenStandardError()) "Refusing to delete $data through a symbolic link or junction.`n"
	Exit-NotRemoved
}
try
{
	Remove-Tree (Get-Item -LiteralPath $data -Force)
}
catch
{
}
if (Test-Path -LiteralPath $data)
{
	Exit-NotRemoved
}
$output = [Console]::OpenStandardOutput()
$output.Write($result, 0, $result.Length)
$output.Flush()
exit 0
