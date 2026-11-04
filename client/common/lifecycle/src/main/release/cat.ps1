# Installs CAT on Windows for Claude Code or Codex: the shared runtime, the trusted updater, the release's plugin and
# the agent permissions that later updates need, by running the setup of the updater that this release publishes.
#
# Run the release's cat.ps1 as a script block in Windows PowerShell 5.1 or later:
#   & ([scriptblock]::Create((irm https://github.com/holdmyspot-com/cat/releases/download/<tag>/cat.ps1))) `
#     -Harness codex
# -Harness accepts claude or codex. Without it, the script detects the harness inside an agent session and otherwise
# refuses. This entry takes no version: its release tag and every archive digest are fixed when the release is
# published. It downloads only the updater archive, checks its SHA-256 digest before extracting anything, extracts it
# into a temporary folder, runs the updater's setup from there and deletes the folder. Setup's output is the script's
# output, and $LASTEXITCODE holds setup's exit status; a nonzero status also throws. Running it again repairs an
# installation. To uninstall CAT, ask the agent to uninstall it: the agent runs the installed updater's uninstall.
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
$CatVersion = $CatReleaseTag.Substring(1)
$CatDownloadUrl = "https://github.com/holdmyspot-com/cat/releases/download/$CatReleaseTag"
$CatEntryCommand = "& ([scriptblock]::Create((irm $CatDownloadUrl/cat.ps1))) -Harness codex"

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
		throw "$($retired[0]) is no longer supported. Run cat.ps1 [-Harness claude|codex] to install or repair CAT, " +
			"including its updater. To uninstall CAT, ask your agent to uninstall CAT: it runs the installed updater's " +
			"cat-updater uninstall, and asks you first whether to delete CAT's data."
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
			"command again with -Harness claude or -Harness codex, such as: $CatEntryCommand"
	}
}
$architecture = $env:PROCESSOR_ARCHITEW6432
if (-not $architecture) { $architecture = $env:PROCESSOR_ARCHITECTURE }
if ($architecture -ne 'AMD64') { throw "Unsupported platform: Windows $architecture." }
if (-not $env:TEMP) { throw 'TEMP must name a directory for the downloaded updater.' }

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
	$name = "cat-updater-$CatReleaseTag-windows-x64"
	$archive = Get-CatVerifiedAsset "$name.tar.gz" $work
	$updater = Expand-CatVerifiedAsset $archive (Join-Path $work 'extracted') $name
	# The archive mirrors the installed layout, so its versioned launcher runs the archive's own runtime
	$launcher = Join-Path $updater "$CatVersion\bin\cat-updater.ps1"
	if (-not (Test-Path -LiteralPath $launcher -PathType Leaf))
	{
		throw "CAT's updater $CatReleaseTag has no launcher for windows-x64 at $launcher."
	}
	$powershell = Join-Path $env:SystemRoot 'System32\WindowsPowerShell\v1.0\powershell.exe'
	# Windows PowerShell treats native standard error as errors when it is redirected, so let it pass and keep the
	# exit status
	$ErrorActionPreference = 'Continue'
	& $powershell -NoProfile -NonInteractive -ExecutionPolicy Bypass -File $launcher setup --harness $Harness `
		--home $harnessHome
	$status = $LASTEXITCODE
	$ErrorActionPreference = 'Stop'
	if ($status -ne 0) { throw "CAT's updater setup exited with status $status." }
}
finally
{
	Remove-Item -LiteralPath $work -Recurse -Force
}
