# Checks whether arguments appended after CAT's frozen Codex update prefix can run code in Windows PowerShell.
#
# Codex's prefix_rule allows extra argv after [powershell.exe, (-NoProfile,) -Command, <script>]. PowerShell joins every
# argument after -Command into the command text, so this probe launches that exact argv shape with appended attack
# arguments and records whether any attack left its marker file. The script text mirrors
# InstallerRules.latestUpdateCommand(windows = true); the installer and run-part are stand-ins in a scratch directory.
# Nothing outside that directory is read or changed.
#
# Run from any directory:
#   powershell.exe -NoProfile -ExecutionPolicy Bypass -File windows-appended-argv-probe.ps1 -Out result.json
param([Parameter(Mandatory = $true)][string]$Out)
$ErrorActionPreference = 'Stop'

$root = Join-Path ([IO.Path]::GetTempPath()) ('cat-argv-probe-' + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $root | Out-Null
$systemRoot = $env:SystemRoot
$system = Join-Path $systemRoot 'System32'
$powershell = Join-Path $system 'WindowsPowerShell\v1.0\powershell.exe'

function Literal([string]$value) { "'" + $value.Replace("'", "''") + "'" }

# Rust's std::process::Command (which Codex uses) builds a Windows command line with these MSVC quoting rules
function Quote([string]$arg)
{
	if ($arg -ne '' -and $arg -notmatch '[\s"]') { return $arg }
	$result = '"'
	$slashes = 0
	foreach ($ch in $arg.ToCharArray())
	{
		if ($ch -eq '\') { $slashes++; continue }
		if ($ch -eq '"') { $result += ('\' * ($slashes * 2 + 1)) + '"' }
		else { $result += ('\' * $slashes) + $ch }
		$slashes = 0
	}
	return $result + ('\' * ($slashes * 2)) + '"'
}

function UpdateScript([string]$dir, [string]$runPart)
{
	$installer = Join-Path $dir 'installer.ps1'
	return @"
`$env:SystemRoot = $(Literal $systemRoot)
`$env:ComSpec = $(Literal (Join-Path $system 'cmd.exe'))
`$env:PATH = $(Literal $env:PATH)
`$env:PATHEXT = $(Literal $env:PATHEXT)
`$env:NoDefaultCurrentDirectoryInExePath = '1'
`$OutputEncoding = [Text.UTF8Encoding]::new()
@'
& $(Literal $powershell) -NoProfile -NonInteractive -ExecutionPolicy Bypass -File $(Literal $installer) install --harness codex --json
'@ | & $(Literal $runPart)
exit `$LASTEXITCODE;

"@
}

function Marker([string]$dir, [string]$name) { "New-Item -ItemType File -Path $(Literal (Join-Path $dir $name)) | Out-Null" }

# Each attack is a list of argv elements appended after the script; {M} is replaced by a marker-writing statement
$attacks = [ordered]@{
	'none'              = @()
	'statement'         = @('; {M}')
	'subexpression'     = @('$({M})')
	'trap'              = @('trap { {M}; continue }')
	'typed-trap'        = @('trap [System.Management.Automation.CommandNotFoundException] { {M}; continue }')
	'requires-path'     = @('#requires -Modules {P}')
	'requires-table'    = @("#requires -Modules @{ModuleName={P};ModuleVersion='0.0'}")
	'class-static'      = @('class Evil { static [object] $x = ({M}) }')
	'using-module'      = @('using module {P}')
	'param-default'     = @('param($a = ({M}))')
	'named-block'       = @('begin { {M} }')
	'newline-statement' = @("`n{M}")
	'herestring-close'  = @("`n'@`n{M}`n@'")
	'split-argv'        = @(';', '{M}')
	'comment-reopen'    = @('<#', '#>', '{M}')
}

$results = @()
$case = 0
foreach ($shellForm in @(@('-Command'), @('-NoProfile', '-Command')))
{
	foreach ($runPartState in @('present', 'missing'))
	{
		foreach ($name in $attacks.Keys)
		{
			$case++
			$dir = Join-Path $root ("case-$case")
			New-Item -ItemType Directory -Path $dir | Out-Null
			Set-Content -Path (Join-Path $dir 'installer.ps1') -Value '' -Encoding ASCII
			$runPart = Join-Path $dir 'run-part.cmd'
			if ($runPartState -eq 'present')
			{
				Set-Content -Path $runPart -Encoding ASCII -Value @(
					'@echo off',
					('more > "' + (Join-Path $dir 'stdin.txt') + '"'),
					('echo ran> "' + (Join-Path $dir 'installer-ran.txt') + '"'),
					'exit /b 0')
			}
			$module = Join-Path $dir 'evil.psm1'
			Set-Content -Path $module -Encoding ASCII -Value (Marker $dir 'attack-ran-module')
			$appended = @($attacks[$name] | ForEach-Object {
				$_.Replace('{M}', (Marker $dir 'attack-ran')).Replace('{P}', (Literal $module))
			})
			$argv = @($shellForm) + @(UpdateScript $dir $runPart) + $appended
			$info = New-Object System.Diagnostics.ProcessStartInfo
			$info.FileName = $powershell
			$info.Arguments = ($argv | ForEach-Object { Quote $_ }) -join ' '
			$info.WorkingDirectory = $dir
			$info.UseShellExecute = $false
			$info.RedirectStandardOutput = $true
			$info.RedirectStandardError = $true
			$process = [System.Diagnostics.Process]::Start($info)
			$stdout = $process.StandardOutput.ReadToEndAsync()
			$stderr = $process.StandardError.ReadToEndAsync()
			$finished = $process.WaitForExit(60000)
			if (-not $finished) { $process.Kill() }
			$attackRan = (Test-Path (Join-Path $dir 'attack-ran')) -or (Test-Path (Join-Path $dir 'attack-ran-module'))
			$results += [ordered]@{
				case = $case
				shell = ($shellForm -join ' ')
				run_part = $runPartState
				attack = $name
				finished = $finished
				exit_code = $(if ($finished) { $process.ExitCode } else { $null })
				installer_ran = (Test-Path (Join-Path $dir 'installer-ran.txt'))
				attack_ran = $attackRan
				stdout = $stdout.Result
				stderr = $stderr.Result
			}
		}
	}
}

$summary = [ordered]@{
	format_version = 1
	powershell = $powershell
	powershell_version = $PSVersionTable.PSVersion.ToString()
	os = [Environment]::OSVersion.VersionString
	cases = $results.Count
	attacks_that_ran = @($results | Where-Object { $_.attack_ran } | ForEach-Object { "$($_.case):$($_.shell):$($_.run_part):$($_.attack)" })
	baseline_installer_ran = @($results | Where-Object { $_.attack -eq 'none' -and $_.run_part -eq 'present' } |
		ForEach-Object { $_.installer_ran })
	results = $results
}
$summary | ConvertTo-Json -Depth 5 | Set-Content -Path $Out -Encoding UTF8
Remove-Item -Recurse -Force $root
Write-Output ("cases: {0}; attacks that ran: {1}" -f $results.Count, $summary.attacks_that_ran.Count)
