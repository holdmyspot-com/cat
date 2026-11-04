# Starts the installed updater selected by current on Windows, from the Windows directory with PATH set to its
# System32 directory and only the documented environment variables. After the updater's JVM has exited, it puts the
# Java runtime that an update staged in place and selects that update's updater version, or completes a complete
# uninstall by deleting the updater data directory, then the directory that holds it when that leaves it empty. Before
# it starts the updater, it puts back a runtime that a launcher which stopped during a runtime replacement had moved
# aside.
# An update renames the stable launcher of the release that it selects over this file while this launcher runs, and the
# launcher that is running, not the new one, finishes the runtime replacement; after a replacement that was undone, a
# launcher can also run an earlier release's updater. Every release therefore keeps the replacement request's first four
# lines (operation, the version that current selects, the version to select, the process identifier of the finishing
# launcher) and the meaning of exit status 78, so that a launcher of any release finishes the request of another; a
# release may add only lines after the fourth.
# Arguments: the updater operation and its arguments, forwarded to Java without interpretation.
# Output: the selected updater's JSON result; after a runtime replacement, the result that the updater saved, printed
#   only once the new runtime runs and current selects the new version, or a not_swapped result; after a complete
#   uninstall, the result that the updater saved, printed only once the data directory no longer exists, or a
#   not_removed result naming the directory; or a locked result while another launcher replaces the runtime. The
#   not_swapped, not_removed and locked results, which this launcher prints itself, hold only the members result and
#   message.
# Exit status: the selected updater's status; 0 after a runtime replacement or a complete uninstall that succeeded; 8
#   with a not_swapped result, after restoring the runtime that was in place, when the replacement request is invalid,
#   the updater saved no readable result, a runtime cannot be renamed, the new runtime's java -version fails or current
#   cannot be replaced, in which case a diagnostic is written to standard error; 7 with a not_removed result, deleting
#   nothing more, when the data directory could not be deleted, when it or its parent is a reparse point such as a
#   symbolic link or junction, or when the updater saved no readable result, in which case the directory's path is
#   written to standard error; 75 with a locked result when the runtime is missing while another launcher replaces it;
#   or 3 when current is malformed or the installation's runtime has no java.exe.
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
$swapRequest = Join-Path $data 'state\runtime-swap'
$swapResult = Join-Path $data 'state\runtime-swap-result.json'

# Reads the pending runtime replacement request that the updater wrote before it exited with status 78. The request
# holds one value per line: the operation, the version that current selects, the version to select, and the process
# identifier of the launcher that finishes the replacement; later lines are the updater's own, and the fifth is that
# launcher's start instant, or - when the updater could not tell it.
# Output: the request's Operation, From, To and Launcher, with Staged and Kept, the runtime that the replacement puts
#   in place and the location to which it moves the replaced runtime, and Started, the launcher's start instant in
#   milliseconds since 1970 or $null when the request does not hold a readable one; or $null when the request is
#   missing or invalid or does not replace the version that current selects.
function Read-Swap
{
	if (-not (Test-Path -LiteralPath $swapRequest -PathType Leaf) -or (Test-ReparsePoint $swapRequest))
	{
		return $null
	}
	try
	{
		$text = [IO.File]::ReadAllText($swapRequest)
	}
	catch
	{
		return $null
	}
	$versionPattern = '([0-9]+\.[0-9]+(?:\.[0-9]+)?)'
	# Matched case-sensitively, as the POSIX launcher matches it, so that both launchers accept the same requests
	if ($text -cnotmatch "\A(update)\n$versionPattern\n$versionPattern\n([0-9]+)\n")
	{
		return $null
	}
	$launcher = [long] 0
	if (-not [long]::TryParse($Matches[4], [ref] $launcher))
	{
		$launcher = [long] -1
	}
	$swap = @{ Operation = $Matches[1]; From = $Matches[2]; To = $Matches[3]; Launcher = $launcher; Started = $null }
	$lines = $text -split "\n"
	$started = [DateTimeOffset]::MinValue
	if ($lines.Count -gt 4 -and [DateTimeOffset]::TryParse($lines[4], [Globalization.CultureInfo]::InvariantCulture,
		[Globalization.DateTimeStyles]::AssumeUniversal, [ref] $started))
	{
		$swap.Started = $started.ToUnixTimeMilliseconds()
	}
	if ($swap.From -ne $version -or $swap.To -eq $swap.From)
	{
		return $null
	}
	$swap.Staged = Join-Path $data 'runtime.next'
	$swap.Kept = Join-Path $data 'runtime.previous'
	return $swap
}

# Indicates whether a process runs. A process that reuses the identifier is told apart by its start instant, which
# Java records with millisecond precision on Windows; when the start instant is unknown or the process's start time
# cannot be read, as for another user's process, the identifier alone decides.
# Arguments: the process identifier, and its start instant in milliseconds since 1970 or $null.
function Test-ProcessRunning([long] $identifier, $started)
{
	if ($identifier -le 0 -or $identifier -gt [int]::MaxValue)
	{
		return $false
	}
	try
	{
		$running = [Diagnostics.Process]::GetProcessById([int] $identifier)
	}
	catch
	{
		return $false
	}
	try
	{
		if ($null -eq $started)
		{
			return $true
		}
		try
		{
			$actual = ([DateTimeOffset] $running.StartTime.ToUniversalTime()).ToUnixTimeMilliseconds()
		}
		catch
		{
			return $true
		}
		return $actual -eq $started
	}
	finally
	{
		$running.Dispose()
	}
}

# A launcher that stopped between its two renames left no runtime in place. While that launcher still runs, it is
# finishing the replacement; otherwise the runtime that it moved aside is put back, and the updater then undoes or
# completes the rest of the replacement.
if (-not (Test-Path -LiteralPath $runtime))
{
	$pending = Read-Swap
	if ($null -ne $pending)
	{
		if (Test-ProcessRunning $pending.Launcher $pending.Started)
		{
			$escaped = $data.Replace('\', '\\').Replace('"', '\"')
			Write-Utf8 ([Console]::OpenStandardOutput()) "{`"result`":`"locked`",`"message`":`"Another CAT launcher is replacing the Java runtime in $escaped; retry once it has finished.`"}`n"
			exit 75
		}
		if ((Test-Path -LiteralPath $pending.Kept -PathType Container) -and -not (Test-ReparsePoint $pending.Kept))
		{
			try
			{
				[IO.Directory]::Move($pending.Kept, $runtime)
			}
			catch
			{
			}
		}
	}
}
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

# Reports that the runtime replacement failed and ends the launcher with status 8. The request stays, so that the next
# updater operation undoes the rest of the operation; the saved result is deleted, because it reports an outcome that
# did not happen.
# Arguments: the diagnostic, which is written to standard error.
function Exit-NotSwapped([string] $message)
{
	try
	{
		[IO.File]::Delete($swapResult)
	}
	catch
	{
	}
	$escaped = $data.Replace('\', '\\').Replace('"', '\"')
	$text = "CAT could not switch the Java runtime in $escaped; the operation did not finish. Run install --harness " +
		'<h> with this launcher at once: it undoes the operation and puts back the previous release, so that ' +
		"CAT's hooks work again. Then tell the user that the operation could not complete, quoting this message, " +
		"and quote the install's message too if it fails."
	Write-Utf8 ([Console]::OpenStandardOutput()) "{`"result`":`"not_swapped`",`"message`":`"$text`"}`n"
	Write-Utf8 ([Console]::OpenStandardError()) "$message`n"
	exit 8
}

# Moves the replacement's runtime back to where the updater staged it and the replaced runtime back into place.
# Arguments: the request that Read-Swap returned.
function Restore-Runtime($swap)
{
	try
	{
		[IO.Directory]::Move($runtime, $swap.Staged)
		[IO.Directory]::Move($swap.Kept, $runtime)
	}
	catch
	{
	}
}

# Status 78 means that the updater finished an update, saved its final result in the data directory, and
# left putting the runtime that the operation staged in place, which the updater's own JVM runs on, to this launcher.
if ($status -eq 78)
{
	$swap = Read-Swap
	if ($null -eq $swap)
	{
		Exit-NotSwapped "The runtime replacement request $swapRequest is missing or invalid; nothing was replaced."
	}
	try
	{
		$swapOutput = [IO.File]::ReadAllBytes($swapResult)
	}
	catch
	{
		$swapOutput = $null
	}
	if ($null -eq $swapOutput -or $swapOutput.Length -eq 0)
	{
		Exit-NotSwapped "The updater saved no readable result in $swapResult; nothing was replaced."
	}
	if (-not (Test-Path -LiteralPath $swap.Staged -PathType Container) -or (Test-ReparsePoint $swap.Staged) -or
		(Test-Path -LiteralPath $swap.Kept) -or (Test-ReparsePoint $swap.Kept))
	{
		Exit-NotSwapped "The runtime $($swap.Staged) is missing or $($swap.Kept) exists; nothing was replaced."
	}
	try
	{
		[IO.Directory]::Move($runtime, $swap.Kept)
	}
	catch
	{
		Exit-NotSwapped "The runtime $runtime could not be moved aside, for example because a running program uses it; nothing was replaced."
	}
	try
	{
		[IO.Directory]::Move($swap.Staged, $runtime)
	}
	catch
	{
		try
		{
			[IO.Directory]::Move($swap.Kept, $runtime)
		}
		catch
		{
		}
		Exit-NotSwapped "The runtime $($swap.Staged) could not be moved into place; the previous runtime was restored."
	}
	$check = New-Object Diagnostics.ProcessStartInfo
	$check.FileName = $java
	$check.Arguments = '-version'
	$check.UseShellExecute = $false
	$check.WorkingDirectory = $windows
	$check.RedirectStandardOutput = $true
	$check.RedirectStandardError = $true
	$check.EnvironmentVariables.Clear()
	$check.EnvironmentVariables['PATH'] = $fixedPath
	$systemRoot = [Environment]::GetEnvironmentVariable('SYSTEMROOT', 'Process')
	if ($null -ne $systemRoot)
	{
		$check.EnvironmentVariables['SYSTEMROOT'] = $systemRoot
	}
	$verified = $false
	try
	{
		$checking = [Diagnostics.Process]::Start($check)
		# Reading both streams to their end keeps a full pipe from blocking java.
		$checkOutput = $checking.StandardOutput.ReadToEndAsync()
		$null = $checking.StandardError.ReadToEnd()
		$null = $checkOutput.Result
		$checking.WaitForExit()
		$verified = $checking.ExitCode -eq 0
		$checking.Dispose()
	}
	catch
	{
	}
	if (-not $verified)
	{
		Restore-Runtime $swap
		Exit-NotSwapped "The new runtime's java -version failed; the previous runtime was restored."
	}
	# Selecting the new version through a replacement makes current name either version, never a partial line.
	$selection = Join-Path $data '.current.next'
	try
	{
		[IO.File]::WriteAllText($selection, "$($swap.To)`n")
		[IO.File]::Replace($selection, $currentPath, $null)
	}
	catch
	{
		try
		{
			[IO.File]::Delete($selection)
		}
		catch
		{
		}
		Restore-Runtime $swap
		Exit-NotSwapped "The updater version $($swap.To) could not be selected; the previous runtime was restored."
	}
	# The replacement is complete; a request or directory that stays is deleted by the next updater operation.
	try
	{
		[IO.File]::Delete($swapRequest)
	}
	catch
	{
	}
	try
	{
		[IO.File]::Delete($swapResult)
	}
	catch
	{
	}
	$swapStream = [Console]::OpenStandardOutput()
	$swapStream.Write($swapOutput, 0, $swapOutput.Length)
	$swapStream.Flush()
	exit 0
}

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
# The directory that holds every harness's installation is removed when this was its last one. A non-recursive delete
# removes only an empty directory, and a failure leaves the result unchanged.
try
{
	[IO.Directory]::Delete((Split-Path -Parent $data), $false)
}
catch
{
}
$output = [Console]::OpenStandardOutput()
$output.Write($result, 0, $result.Length)
$output.Flush()
exit 0
