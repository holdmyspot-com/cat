# Installs CAT on Windows for Claude Code or Codex: the release's plugin, with its Java runtime and installer, and the
# agent permissions that later updates need, by running the install of the installer in the plugin that this release
# publishes for the harness.
#
# Run the release's cat.ps1 as a script block in Windows PowerShell 5.1 or later:
#   & ([scriptblock]::Create((irm https://github.com/holdmyspot-com/cat/releases/download/<tag>/cat.ps1))) `
#     -Harness codex
# -Harness accepts claude or codex. Without it, the script detects the harness inside an agent session and otherwise
# refuses. This entry takes no version: its release tag and every archive digest are fixed when the release is
# published. It downloads only the harness's plugin archive, checks its SHA-256 digest before extracting anything,
# extracts it into a temporary folder, runs the plugin's installer from there and deletes the folder. The installer's
# output is the script's output, and $LASTEXITCODE holds its exit status; a nonzero status also throws. The installer
# asks the harness to install the plugin. To uninstall CAT, ask the agent to uninstall it: the agent runs the installed
# plugin's installer uninstall.
[CmdletBinding()]
param(
	[ValidateSet('claude', 'codex')]
	[string] $Harness,
	# Retired: refused with a message that says how to install, repair or uninstall CAT instead
	[switch] $UpdaterOnly,
	# Retired: refused with a message that says how to install, repair or uninstall CAT instead
	[switch] $Uninstall,
	# Retired: refused with a message that says how to install, repair or uninstall CAT instead
	[switch] $DeleteData
)
Set-StrictMode -Version 2.0
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'

$CatReleaseTag = '@CAT_RELEASE_TAG@'
$CatSha256Sums = @'
@CAT_SHA256SUMS@
'@
$CatDownloadUrl = "https://github.com/holdmyspot-com/cat/releases/download/$CatReleaseTag"

# Returns the entry command that installs a harness, for the user to run in a terminal whose environment may differ
# from this one. The command sets the harness's home variable when this process sets it to a directory other than the
# harness's default one in the user profile. This script takes the user's home from the user profile, so no other
# variable needs setting.
function Get-CatEntryCommand([string] $Name)
{
	$command = "& ([scriptblock]::Create((irm $CatDownloadUrl/cat.ps1))) -Harness $Name"
	if ($Name -eq 'claude')
	{
		$variable = 'CLAUDE_CONFIG_DIR'
		$defaultHome = '.claude'
	}
	else
	{
		$variable = 'CODEX_HOME'
		$defaultHome = '.codex'
	}
	$value = [Environment]::GetEnvironmentVariable($variable)
	if (-not $value) { return $command }
	$default = Join-Path ([Environment]::GetFolderPath('UserProfile')) $defaultHome
	if ($value.TrimEnd('\', '/') -ieq $default) { return $command }
	return "`$env:$variable = '" + $value.Replace("'", "''") + "'; $command"
}

# Downloads one named release asset into WorkDirectory and returns its path only after its embedded SHA-256 matches.
# Throws when the asset has no unique digest, the download fails, or its bytes differ from the published digest.
function Get-CatVerifiedAsset([string] $Name, [string] $WorkDirectory)
{
	$entries = @($CatSha256Sums -split '\r?\n' | Where-Object { ($_ -split '  ', 2)[-1] -ceq $Name })
	if ($entries.Count -ne 1) { throw "CAT $CatReleaseTag publishes no asset named $Name." }
	$expected = ($entries[0] -split '  ', 2)[0]
	$archive = Join-Path $WorkDirectory $Name
	Invoke-WebRequest -Uri "$CatDownloadUrl/$Name" -OutFile $archive -UseBasicParsing
	$actual = (Get-FileHash -Algorithm SHA256 -LiteralPath $archive).Hash
	if ($actual -ne $expected)
	{
		Remove-Item -LiteralPath $archive -Force
		throw "The download of $Name does not match the SHA-256 digest that CAT $CatReleaseTag published, so CAT " +
			"changed nothing. Expected $expected, received $actual."
	}
	return $archive
}

# Extracts a verified Archive into a fresh Directory and returns its single top directory, which must be Name.
# Throws when tar fails or the archive's top directory differs, without running extracted code.
function Expand-CatVerifiedAsset([string] $Archive, [string] $Directory, [string] $Name)
{
	New-Item -ItemType Directory -Path $Directory | Out-Null
	$tar = Join-Path $env:SystemRoot 'System32\tar.exe'
	& $tar -xzf $Archive -C $Directory | Out-Host
	if ($LASTEXITCODE -ne 0) { throw "tar could not extract $Archive; it exited with status $LASTEXITCODE." }
	$entries = @(Get-ChildItem -LiteralPath $Directory -Force)
	$isOneRealDirectory = ($entries.Count -eq 1) -and ($entries[0].Name -ceq $Name) -and $entries[0].PSIsContainer -and
		(($entries[0].Attributes -band [IO.FileAttributes]::ReparsePoint) -eq 0)
	if (-not $isOneRealDirectory)
	{
		throw "The archive $(Split-Path -Leaf $Archive) does not hold exactly the directory $Name."
	}
	return $entries[0].FullName
}

foreach ($retired in @(@('-UpdaterOnly', $UpdaterOnly), @('-Uninstall', $Uninstall), @('-DeleteData', $DeleteData)))
{
	if ($retired[1])
	{
		throw "$($retired[0]) is no longer supported. Run cat.ps1 [-Harness claude|codex] to install CAT, including " +
			"its installer. To uninstall CAT, ask your agent to uninstall CAT: it runs the installed plugin's " +
			"bin\installer.ps1 uninstall, and asks you first whether to delete CAT's data."
	}
}
if (-not $Harness)
{
	# Claude Code sets CLAUDECODE=1 in the shells that run an agent's commands, and Codex sets CODEX_THREAD_ID in them
	$inClaude = $env:CLAUDECODE -eq '1'
	$inCodex = [bool] $env:CODEX_THREAD_ID
	if ($inClaude -and -not $inCodex) { $Harness = 'claude' }
	elseif ($inCodex -and -not $inClaude) { $Harness = 'codex' }
	else
	{
		throw "CAT cannot tell whether this shell belongs to Claude Code or Codex, so it changed nothing. Run the " +
			"command again with -Harness claude or -Harness codex, such as: $(Get-CatEntryCommand 'claude') or " +
			"$(Get-CatEntryCommand 'codex')"
	}
}
$architecture = $env:PROCESSOR_ARCHITEW6432
if (-not $architecture) { $architecture = $env:PROCESSOR_ARCHITECTURE }
if ($architecture -ne 'AMD64') { throw "Unsupported platform: Windows $architecture." }
if (-not $env:TEMP) { throw 'TEMP must name a directory for the downloaded plugin.' }

# Each harness moves its home with its own variable
if ($Harness -eq 'claude')
{
	$harnessHome = $env:CLAUDE_CONFIG_DIR
	$defaultHome = '.claude'
}
else
{
	$harnessHome = $env:CODEX_HOME
	$defaultHome = '.codex'
}
if (-not $harnessHome) { $harnessHome = Join-Path ([Environment]::GetFolderPath('UserProfile')) $defaultHome }
$harnessHome = $ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($harnessHome)
# GitHub requires TLS 1.2, which Windows PowerShell 5.1 enables only when explicitly requested.
[Net.ServicePointManager]::SecurityProtocol = [Net.ServicePointManager]::SecurityProtocol -bor `
	[Net.SecurityProtocolType]::Tls12
$work = Join-Path $env:TEMP ('cat-' + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $work | Out-Null
try
{
	$name = "cat-$Harness-windows-x64-$CatReleaseTag"
	$archive = Get-CatVerifiedAsset "$name.tar.gz" $work
	$plugin = Expand-CatVerifiedAsset $archive (Join-Path $work 'extracted') $name
	# The plugin's launcher runs the Java runtime in the plugin's own bin folder
	$launcher = Join-Path $plugin 'bin\installer.ps1'
	if (-not (Test-Path -LiteralPath $launcher -PathType Leaf))
	{
		throw "CAT's $Harness plugin $CatReleaseTag has no installer for windows-x64 at $launcher."
	}
	$powershell = Join-Path $env:SystemRoot 'System32\WindowsPowerShell\v1.0\powershell.exe'
	# Windows PowerShell treats native standard error as errors when it is redirected, so let it pass and keep the
	# exit status
	$ErrorActionPreference = 'Continue'
	& $powershell -NoProfile -NonInteractive -ExecutionPolicy Bypass -File $launcher --home $harnessHome `
		install --harness $Harness
	$status = $LASTEXITCODE
	$ErrorActionPreference = 'Stop'
	if ($status -ne 0) { throw "CAT's installer install exited with status $status." }
}
finally
{
	Remove-Item -LiteralPath $work -Recurse -Force
}
