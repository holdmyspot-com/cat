# Installs, upgrades or removes CAT's Codex plugin on Windows.
#
# Windows PowerShell's execution policy blocks script files by default but not script blocks, so run this script as a
# script block:
#
#   & ([scriptblock]::Create((Invoke-RestMethod <url of this script>))) [-Source v1.0 | -Source <extracted artifact>]
#   & ([scriptblock]::Create((Invoke-RestMethod <url of this script>))) -Uninstall [-DeleteData]
#
# -Source names the release tag to download, which is the default, or a folder that holds an extracted artifact.
# -Uninstall removes the plugin and the Claude CAT artifacts that a runner skill downloaded into .cat\bundles, and keeps
# CAT's data, so that a later installation can use it; -DeleteData deletes it too.
#
# The script reports every failure by throwing, rather than calling exit, which would close the window of a user who
# runs it interactively. It must stay ASCII so that Windows PowerShell reads it the same way in every code page, and
# compatible with Windows PowerShell 5.1.
[CmdletBinding()]
param(
	[string] $Source = '',
	[switch] $Uninstall,
	[switch] $DeleteData
)
Set-StrictMode -Version 2.0
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'

$CatVersion = '1.0'
$CatReleaseTag = "v$CatVersion"
$CatReleaseBaseUrl = $env:CAT_RELEASE_BASE_URL
if (-not $CatReleaseBaseUrl)
{
	$CatReleaseBaseUrl = "https://github.com/holdmyspot-com/cat/releases/download/$CatReleaseTag"
}

# Returns -1, 0 or 1 when the dotted numeric version $Left precedes, equals or follows $Right.
function Compare-CatVersion([string] $Left, [string] $Right)
{
	$leftParts = $Left.Split('.')
	$rightParts = $Right.Split('.')
	$count = [Math]::Max($leftParts.Count, $rightParts.Count)
	for ($i = 0; $i -lt $count; ++$i)
	{
		$leftPart = [long] 0
		$rightPart = [long] 0
		if ($i -lt $leftParts.Count) { $leftPart = [long] $leftParts[$i] }
		if ($i -lt $rightParts.Count) { $rightPart = [long] $rightParts[$i] }
		if ($leftPart -lt $rightPart) { return -1 }
		if ($leftPart -gt $rightPart) { return 1 }
	}
	return 0
}

# Returns whether a version consists of dotted numbers, such as 1.0.
function Test-CatVersion([string] $Version)
{
	return $Version -match '^[0-9]+(\.[0-9]+)*$'
}

# Writes text to a file as UTF-8 without a byte order mark.
function Write-CatFile([string] $Path, [string] $Text)
{
	[IO.File]::WriteAllText($Path, $Text, (New-Object Text.UTF8Encoding($false)))
}

# Runs a program, such as codex, and throws if it fails. Windows PowerShell turns a program's standard error into
# errors that $ErrorActionPreference = 'Stop' would end the script on, so the program runs with 'Continue'.
function Invoke-CatProgram([string] $Program, [string[]] $Arguments)
{
	$ErrorActionPreference = 'Continue'
	& $Program @Arguments | Out-Host
	if ($LASTEXITCODE -ne 0)
	{
		throw "$Program $($Arguments -join ' ') failed with exit code $LASTEXITCODE"
	}
}

# Runs a program and ignores its output and whether it fails.
function Invoke-CatProgramQuietly([string] $Program, [string[]] $Arguments)
{
	$ErrorActionPreference = 'Continue'
	& $Program @Arguments *> $null
}

# Rejects a marketplace folder that is not named cat-marketplace, before the installer deletes it.
function Assert-CatMarketplaceRoot([string] $Path)
{
	if ((Split-Path -Leaf $Path) -ne 'cat-marketplace')
	{
		throw ("CAT marketplace path must end in \cat-marketplace so CAT only replaces its own marketplace: $Path. " +
			'Set CAT_CODEX_MARKETPLACE_ROOT to a path ending in \cat-marketplace.')
	}
}

# Rejects a data folder that is not plugins\data\cat-cat, before the uninstaller deletes it.
function Assert-CatDataDirectory([string] $Path)
{
	$normalized = $Path.Replace('/', '\').TrimEnd('\')
	if (-not $normalized.EndsWith('\plugins\data\cat-cat', [StringComparison]::OrdinalIgnoreCase))
	{
		throw ("CAT data path must end in \plugins\data\cat-cat so CAT only removes its own data: $Path. " +
			'Set CAT_CODEX_DATA_DIR to a path ending in \plugins\data\cat-cat.')
	}
}

# Returns the release platform of this computer.
function Get-CatPlatform
{
	$architecture = $env:PROCESSOR_ARCHITEW6432
	if (-not $architecture) { $architecture = $env:PROCESSOR_ARCHITECTURE }
	if ($architecture -eq 'AMD64') { return 'windows-x64' }
	throw "Unsupported platform: Windows $architecture"
}

# Downloads, verifies and extracts the release archive into a folder, and returns the extracted artifact's folder.
function Expand-CatRelease([string] $Folder)
{
	$archiveName = "cat-codex-v$CatVersion-$(Get-CatPlatform).tar.gz"
	$archive = Join-Path $Folder $archiveName
	$checksums = Join-Path $Folder 'SHA256SUMS.txt'
	# Windows PowerShell 5.1 does not offer TLS 1.2, which GitHub requires, unless asked to
	$protocols = [Net.ServicePointManager]::SecurityProtocol
	[Net.ServicePointManager]::SecurityProtocol = $protocols -bor [Net.SecurityProtocolType]::Tls12
	Invoke-WebRequest -Uri "$CatReleaseBaseUrl/$archiveName" -OutFile $archive -UseBasicParsing
	Invoke-WebRequest -Uri "$CatReleaseBaseUrl/SHA256SUMS.txt" -OutFile $checksums -UseBasicParsing
	$expected = $null
	foreach ($line in [IO.File]::ReadAllLines($checksums))
	{
		$fields = $line.Trim() -split '\s+', 2
		if ($fields.Count -eq 2 -and ($fields[1] -eq $archiveName -or $fields[1] -eq "*$archiveName"))
		{
			$expected = $fields[0]
		}
	}
	if (-not $expected) { throw "No checksum published for $archiveName" }
	$actual = (Get-FileHash -Algorithm SHA256 -LiteralPath $archive).Hash
	if ($actual -ne $expected)
	{
		throw "The checksum of $archiveName does not match SHA256SUMS.txt: expected $expected, got $actual"
	}
	Invoke-CatProgram (Join-Path $env:SystemRoot 'System32\tar.exe') @('-xzf', $archive, '-C', $Folder)
	$artifact = Join-Path $Folder $archiveName.Substring(0, $archiveName.Length - '.tar.gz'.Length)
	if (-not (Test-Path -LiteralPath $artifact -PathType Container))
	{
		throw "Archive did not contain the expected directory: $(Split-Path -Leaf $artifact)"
	}
	return $artifact
}

# Rejects a folder that is not an extracted CAT artifact for Codex on Windows.
function Assert-CatArtifact([string] $Artifact)
{
	if (-not (Test-Path -LiteralPath (Join-Path $Artifact '.codex-plugin\plugin.json') -PathType Leaf))
	{
		throw 'Not a Codex CAT artifact: missing .codex-plugin\plugin.json'
	}
	$versionFile = Join-Path $Artifact 'client\VERSION'
	if (-not (Test-Path -LiteralPath $versionFile -PathType Leaf) -or
		[IO.File]::ReadAllText($versionFile).Trim() -ne $CatVersion)
	{
		throw "CAT artifact version must be $CatVersion"
	}
	if (-not (Test-Path -LiteralPath (Join-Path $Artifact 'bin\plugin-info.cmd') -PathType Leaf))
	{
		throw 'CAT artifact is missing the plugin-info.cmd launcher; install the windows-x64 release'
	}
}

# Runs each migration of CAT's data after the recorded version through this version, then records this version.
# The registry names each migration's POSIX script; Windows runs the PowerShell script beside it, which has the same
# name with the extension .ps1.
function Update-CatData([string] $DataDirectory, [string] $Migrations)
{
	$versionFile = Join-Path $DataDirectory 'migration-version'
	$registry = Join-Path $Migrations 'registry.tsv'
	New-Item -ItemType Directory -Force -Path $DataDirectory | Out-Null
	if (-not (Test-Path -LiteralPath $registry -PathType Leaf)) { throw "CAT migration registry is missing: $registry" }
	$firstInstall = -not (Test-Path -LiteralPath $versionFile -PathType Leaf)
	$previous = $CatVersion
	if (-not $firstInstall) { $previous = [IO.File]::ReadAllText($versionFile).Trim() }
	if (-not $previous) { throw "CAT migration version is empty: $versionFile" }
	if (-not (Test-CatVersion $previous))
	{
		throw "CAT migration version must be dotted numeric text: $versionFile"
	}
	if ((Compare-CatVersion $CatVersion $previous) -lt 0)
	{
		throw "CAT data was created by a newer version ($previous); install CAT $previous or newer."
	}
	$registryPrevious = $null
	foreach ($line in [IO.File]::ReadAllLines($registry))
	{
		if (-not $line -or $line.StartsWith('#')) { continue }
		$fields = $line.Split("`t")
		$target = $fields[0]
		$script = ''
		if ($fields.Count -gt 1) { $script = $fields[1] }
		if (-not (Test-CatVersion $target))
		{
			throw "CAT migration registry contains an invalid target version: $target"
		}
		if ($null -ne $registryPrevious -and (Compare-CatVersion $registryPrevious $target) -ge 0)
		{
			throw "CAT migration registry versions must be strictly increasing: $registry"
		}
		$registryPrevious = $target
		if (-not $script -or [IO.Path]::IsPathRooted($script) -or $script -match '(^|[\\/])\.{1,2}([\\/]|$)' -or
			$script -match '[\\/]{2}' -or -not $script.EndsWith('.sh'))
		{
			throw "CAT migration registry contains an invalid script path: $script"
		}
		$windowsScript = Join-Path $Migrations ($script.Substring(0, $script.Length - '.sh'.Length) + '.ps1')
		if (-not (Test-Path -LiteralPath $windowsScript -PathType Leaf))
		{
			throw "CAT migration script is missing: $windowsScript"
		}
		if (-not $firstInstall -and (Compare-CatVersion $previous $target) -lt 0 -and
			(Compare-CatVersion $target $CatVersion) -le 0)
		{
			$env:CAT_MIGRATION_DATA_DIR = $DataDirectory
			$env:CAT_MIGRATION_FROM_VERSION = $previous
			$env:CAT_MIGRATION_TO_VERSION = $target
			try
			{
				& ([scriptblock]::Create([IO.File]::ReadAllText($windowsScript)))
			}
			finally
			{
				Remove-Item Env:CAT_MIGRATION_DATA_DIR, Env:CAT_MIGRATION_FROM_VERSION, Env:CAT_MIGRATION_TO_VERSION
			}
		}
	}
	Write-CatFile $versionFile "$CatVersion`n"
}

# Returns the names of the agent files that CAT installed, as its ownership manifest records them.
function Get-CatAgentNames([string] $Manifest)
{
	if (-not (Test-Path -LiteralPath $Manifest -PathType Leaf)) { return @() }
	$names = @()
	foreach ($name in [IO.File]::ReadAllLines($Manifest))
	{
		if (-not $name) { continue }
		if ($name -notmatch '^cat-[^\\/]*\.toml$')
		{
			throw "CAT agent ownership manifest contains an invalid filename: $name"
		}
		$names += $name
	}
	return $names
}

# Removes the Codex custom agents that CAT installed.
function Remove-CatAgents([string] $CodexHome, [string] $DataDirectory)
{
	$manifest = Join-Path $DataDirectory 'codex-agent-files'
	foreach ($name in (Get-CatAgentNames $manifest))
	{
		Remove-Item -LiteralPath (Join-Path $CodexHome "agents\$name") -Force -ErrorAction SilentlyContinue
	}
	Remove-Item -LiteralPath $manifest -Force -ErrorAction SilentlyContinue
}

# Installs the artifact's Codex custom agents into Codex's agents folder, refusing to replace an agent that CAT did
# not install, and records them in CAT's ownership manifest.
function Install-CatAgents([string] $Artifact, [string] $CodexHome, [string] $DataDirectory)
{
	$sources = Join-Path $Artifact 'agents\codex'
	$agents = Join-Path $CodexHome 'agents'
	$manifest = Join-Path $DataDirectory 'codex-agent-files'
	if (-not (Test-Path -LiteralPath $sources -PathType Container))
	{
		throw "CAT artifact is missing Codex custom agents: $sources"
	}
	$definitions = @(Get-ChildItem -LiteralPath $sources -Filter '*.toml' -File)
	if ($definitions.Count -eq 0) { throw "CAT artifact contains no Codex custom-agent definitions: $sources" }
	New-Item -ItemType Directory -Force -Path $agents, $DataDirectory | Out-Null
	$owned = Get-CatAgentNames $manifest
	$names = @()
	foreach ($definition in $definitions)
	{
		$name = "cat-$($definition.Name)"
		$target = Join-Path $agents $name
		if ((Test-Path -LiteralPath $target) -and $owned -notcontains $name)
		{
			throw "Refusing to overwrite an unowned Codex agent: $target"
		}
		$names += $name
	}
	Remove-CatAgents $CodexHome $DataDirectory
	foreach ($definition in $definitions)
	{
		Copy-Item -LiteralPath $definition.FullName -Destination (Join-Path $agents "cat-$($definition.Name)") -Force
	}
	Write-CatFile $manifest (($names -join "`n") + "`n")
}

$codexHome = $env:CODEX_HOME
if (-not $codexHome) { $codexHome = Join-Path ([Environment]::GetFolderPath('UserProfile')) '.codex' }
$dataDirectory = $env:CAT_CODEX_DATA_DIR
if (-not $dataDirectory) { $dataDirectory = Join-Path $codexHome 'plugins\data\cat-cat' }
$marketplaceRoot = $env:CAT_CODEX_MARKETPLACE_ROOT
if (-not $marketplaceRoot) { $marketplaceRoot = Join-Path $codexHome 'plugins\cat-marketplace' }
Assert-CatMarketplaceRoot $marketplaceRoot

if ($Uninstall)
{
	if ($Source) { throw '-Source cannot be used with -Uninstall' }
	if ($DeleteData) { Assert-CatDataDirectory $dataDirectory }
	Remove-CatAgents $codexHome $dataDirectory
	Invoke-CatProgram 'codex' @('plugin', 'remove', 'cat@cat')
	Invoke-CatProgramQuietly 'codex' @('plugin', 'marketplace', 'remove', 'cat')
	if (Test-Path -LiteralPath $marketplaceRoot)
	{
		Remove-Item -LiteralPath $marketplaceRoot -Recurse -Force
	}
	$userProfile = $env:USERPROFILE
	if (-not $userProfile) { $userProfile = [Environment]::GetFolderPath('UserProfile') }
	$bundles = Join-Path $userProfile '.cat\bundles'
	if (Test-Path -LiteralPath $bundles)
	{
		Remove-Item -LiteralPath $bundles -Recurse -Force
	}
	if ($DeleteData -and (Test-Path -LiteralPath $dataDirectory))
	{
		Remove-Item -LiteralPath $dataDirectory -Recurse -Force
	}
	return
}
if ($DeleteData) { throw '-DeleteData can only be used with -Uninstall' }
if (-not $Source) { $Source = $CatReleaseTag }

$downloads = $null
try
{
	if (Test-Path -LiteralPath $Source -PathType Container)
	{
		$artifact = (Resolve-Path -LiteralPath $Source).ProviderPath
	}
	elseif ($Source -eq $CatReleaseTag)
	{
		$downloads = Join-Path ([IO.Path]::GetTempPath()) ('cat-codex-install-' + [Guid]::NewGuid().ToString('N'))
		New-Item -ItemType Directory -Path $downloads | Out-Null
		$artifact = Expand-CatRelease $downloads
	}
	else
	{
		throw "Expected $CatReleaseTag or an extracted artifact directory: $Source"
	}
	Assert-CatArtifact $artifact

	Update-CatData $dataDirectory (Join-Path $artifact 'install\migrations')
	Install-CatAgents $artifact $codexHome $dataDirectory
	Invoke-CatProgramQuietly 'codex' @('plugin', 'remove', 'cat@cat')
	Invoke-CatProgramQuietly 'codex' @('plugin', 'marketplace', 'remove', 'cat')
	if (Test-Path -LiteralPath $marketplaceRoot)
	{
		Remove-Item -LiteralPath $marketplaceRoot -Recurse -Force
	}
	$plugin = Join-Path $marketplaceRoot 'plugins\cat'
	$metadata = Join-Path $marketplaceRoot '.agents\plugins'
	New-Item -ItemType Directory -Force -Path $plugin, $metadata | Out-Null
	Get-ChildItem -LiteralPath $artifact -Force | Copy-Item -Destination $plugin -Recurse -Force
	Copy-Item -LiteralPath (Join-Path $artifact 'install\codex-marketplace.json') `
		-Destination (Join-Path $metadata 'marketplace.json')

	Invoke-CatProgram 'codex' @('plugin', 'marketplace', 'add', $marketplaceRoot)
	Invoke-CatProgram 'codex' @('plugin', 'add', 'cat@cat')
	Invoke-CatProgram (Join-Path $plugin 'bin\plugin-info.cmd') @()
}
finally
{
	if ($downloads -and (Test-Path -LiteralPath $downloads))
	{
		Remove-Item -LiteralPath $downloads -Recurse -Force
	}
}
