# Installs or removes this release's Codex plugin on Windows, or installs only its trusted updater.
#
# Run the release's cat.ps1 as a script block in Windows PowerShell 5.1 or later:
#   & ([scriptblock]::Create((irm https://github.com/holdmyspot-com/cat/releases/download/<tag>/cat.ps1)))
# Use -UpdaterOnly to set up or upgrade the updater without installing the plugin. Use -Uninstall [-DeleteData] to
# remove the plugin and optionally its data, keeping the updater. -Harness accepts only codex, which is the default.
# This entry takes no version: its release tag and every archive digest are fixed when the release is published.
# Download or digest failures throw before any installed files change. Extraction and installation failures throw;
# a failed rename restores an existing updater. If restoration fails, the error names the retained backup directory.
[CmdletBinding()]
param(
	[ValidateSet('codex')]
	[string] $Harness = 'codex',
	[switch] $UpdaterOnly,
	[switch] $Uninstall,
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

# Downloads one named release asset into WorkDirectory and returns its path only after its embedded SHA-256 matches.
# Throws when the asset has no unique digest, the download fails, or its bytes differ from the published digest.
function Get-CatVerifiedAsset([string] $Name, [string] $WorkDirectory)
{
	$matches = @($CatSha256Sums -split '\r?\n' | Where-Object { ($_ -split '  ', 2)[1] -ceq $Name })
	if ($matches.Count -ne 1) { throw "CAT $CatReleaseTag publishes no unique digest for $Name." }
	$expected = ($matches[0] -split '  ', 2)[0]
	$archive = Join-Path $WorkDirectory $Name
	Invoke-WebRequest -Uri "$CatDownloadUrl/$Name" -OutFile $archive -UseBasicParsing
	$actual = (Get-FileHash -Algorithm SHA256 -LiteralPath $archive).Hash
	if ($actual -ne $expected)
	{
		throw "The download of $Name does not match the SHA-256 digest: expected $expected, received $actual."
	}
	return $archive
}

# Runs an executable with Arguments and throws with its exit status on failure. Output is shown to the caller.
function Invoke-CatEntryProgram([string] $Program, [string[]] $Arguments)
{
	# Windows PowerShell treats native stderr as errors, so allow it while preserving the program's exit status.
	$ErrorActionPreference = 'Continue'
	& $Program @Arguments | Out-Host
	if ($LASTEXITCODE -ne 0) { throw "$Program failed with exit code $LASTEXITCODE." }
}

# Extracts a verified Archive into a fresh Directory and returns its single top directory, which must be Name.
# Throws when tar fails or the archive's top directory differs, without running extracted code.
function Expand-CatVerifiedAsset([string] $Archive, [string] $Directory, [string] $Name)
{
	New-Item -ItemType Directory -Path $Directory | Out-Null
	Invoke-CatEntryProgram (Join-Path $env:SystemRoot 'System32\tar.exe') @('-xzf', $Archive, '-C', $Directory)
	$entries = @(Get-ChildItem -LiteralPath $Directory -Force)
	if ($entries.Count -ne 1) { throw "The archive must hold exactly the directory $Name." }
	$entry = $entries[0]
	$isDirectory = $entry.PSIsContainer -and (($entry.Attributes -band [IO.FileAttributes]::ReparsePoint) -eq 0)
	if (($entry.Name -cne $Name) -or -not $isDirectory)
	{
		throw "The archive must hold exactly the real directory $Name."
	}
	return $entry.FullName
}

# Replaces the updater's version directory with the verified Extracted directory, staging under DataDirectory.
# Restores the previous directory when activation fails. If restoration also fails, throws with the retained backup's
# path; otherwise removes only this call's staging directory at exit.
function Install-CatVerifiedUpdater([string] $Extracted, [string] $DataDirectory)
{
	New-Item -ItemType Directory -Force -Path $DataDirectory | Out-Null
	$stage = Join-Path $DataDirectory ('.cat-staging-' + [Guid]::NewGuid().ToString('N'))
	New-Item -ItemType Directory -Path $stage | Out-Null
	$removeStage = $true
	try
	{
		$staged = Join-Path $stage 'updater'
		Copy-Item -LiteralPath $Extracted -Destination $staged -Recurse
		$live = Join-Path $DataDirectory $CatVersion
		$previous = Join-Path $stage 'previous'
		if (Test-Path -LiteralPath $live) { Move-Item -LiteralPath $live -Destination $previous }
		try
		{
			Move-Item -LiteralPath $staged -Destination $live
		}
		catch
		{
			if (Test-Path -LiteralPath $previous)
			{
				try
				{
					Move-Item -LiteralPath $previous -Destination $live
				}
				catch
				{
					$removeStage = $false
					throw "CAT could not restore its updater at $live. The previous updater remains at $previous. $_"
				}
			}
			throw
		}
	}
	finally
	{
		if ($removeStage) { Remove-Item -LiteralPath $stage -Recurse -Force }
	}
}

if ($UpdaterOnly -and $Uninstall) { throw '-UpdaterOnly cannot be used with -Uninstall.' }
if ($DeleteData -and -not $Uninstall) { throw '-DeleteData requires -Uninstall.' }
$architecture = $env:PROCESSOR_ARCHITEW6432
if (-not $architecture) { $architecture = $env:PROCESSOR_ARCHITECTURE }
if ($architecture -ne 'AMD64') { throw "Unsupported platform: Windows $architecture." }
if (-not $env:TEMP) { throw 'TEMP must name a directory for downloaded release assets.' }
if (-not $Uninstall -and -not $env:LOCALAPPDATA) { throw 'LOCALAPPDATA must name the updater data directory parent.' }

$codexHome = $env:CODEX_HOME
if (-not $codexHome) { $codexHome = Join-Path ([Environment]::GetFolderPath('UserProfile')) '.codex' }
$codexHome = $ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($codexHome)
# GitHub requires TLS 1.2, which Windows PowerShell 5.1 enables only when explicitly requested.
[Net.ServicePointManager]::SecurityProtocol = [Net.ServicePointManager]::SecurityProtocol -bor `
	[Net.SecurityProtocolType]::Tls12
$work = Join-Path $env:TEMP ('cat-' + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $work | Out-Null
try
{
	$plugin = ''
	if (-not $UpdaterOnly)
	{
		$name = "cat-codex-$CatReleaseTag-windows-x64"
		$archive = Get-CatVerifiedAsset "$name.tar.gz" $work
		$plugin = Expand-CatVerifiedAsset $archive (Join-Path $work 'plugin') $name
	}
	if ($Uninstall)
	{
		$installer = Join-Path $plugin 'install\install-codex.ps1'
		& ([scriptblock]::Create([IO.File]::ReadAllText($installer))) -Uninstall -DeleteData:$DeleteData
		return
	}

	# Verify both downloads before the installer can replace an existing plugin.
	$name = "cat-updater-$CatReleaseTag-windows-x64"
	$archive = Get-CatVerifiedAsset "$name.tar.gz" $work
	$updater = Expand-CatVerifiedAsset $archive (Join-Path $work 'updater') $name
	if (-not (Test-Path -LiteralPath (Join-Path $updater 'bin\cat-updater.cmd') -PathType Leaf))
	{
		throw 'The updater archive is missing bin\cat-updater.cmd.'
	}
	if (-not $UpdaterOnly)
	{
		$installer = Join-Path $plugin 'install\install-codex.ps1'
		& ([scriptblock]::Create([IO.File]::ReadAllText($installer))) -Source $plugin
	}
	$data = Join-Path $env:LOCALAPPDATA 'cat-updater'
	Install-CatVerifiedUpdater $updater $data
	Invoke-CatEntryProgram (Join-Path $data "$CatVersion\bin\cat-updater.cmd") `
		@('setup', '--harness', $Harness, '--home', $codexHome)
}
finally
{
	Remove-Item -LiteralPath $work -Recurse -Force
}
