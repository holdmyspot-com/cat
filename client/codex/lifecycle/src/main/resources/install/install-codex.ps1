# Installs, upgrades or removes CAT's Codex plugin on Windows.
#
# Run the verified archive's script as a script block, with -Source naming its extracted artifact directory:
#   & ([scriptblock]::Create([IO.File]::ReadAllText(<path to this script>))) -Source <extracted artifact>
# Use -Uninstall [-DeleteData] to remove the plugin and optionally its data, keeping the trusted updater.
# Initial setup and updater upgrades use the release's cat.ps1 entry, which verifies downloads before running it.
#
# Before it changes anything, the script checks that it can write every file and folder that the operation changes,
# including those that Codex changes. If it cannot, as in an agent's sandbox, it changes nothing and fails with the
# command that repeats the operation in a PowerShell window outside the sandbox.
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

# Rejects a marketplace folder that is not named cat-marketplace, before the installer deletes it. The name is read
# from the text, because Split-Path rejects a path on a drive that does not exist.
function Assert-CatMarketplaceRoot([string] $Path)
{
	if ([IO.Path]::GetFileName($Path.TrimEnd('\', '/')) -ne 'cat-marketplace')
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

# Returns a PowerShell string literal whose value is a text.
function ConvertTo-CatLiteral([string] $Value)
{
	return "'" + $Value.Replace("'", "''") + "'"
}

# Returns the command that repeats this operation through this release's cat.ps1 entry outside the sandbox,
# preserving the explicit harness-home, data and marketplace settings and adding the entry's Arguments.
function Get-CatRerunCommand([string] $Arguments)
{
	$command = ''
	foreach ($name in @('CODEX_HOME', 'CAT_CODEX_DATA_DIR', 'CAT_CODEX_MARKETPLACE_ROOT'))
	{
		$value = [Environment]::GetEnvironmentVariable($name)
		if ($value) { $command += "`$env:$name = $(ConvertTo-CatLiteral $value); " }
	}
	$url = "https://github.com/holdmyspot-com/cat/releases/download/$CatReleaseTag/cat.ps1"
	return "$command& ([scriptblock]::Create((irm $url))) -Harness codex$Arguments"
}

# Returns whether an existing file or folder can be written, without changing it. A folder is writable when a probe
# file can be created and deleted in it; a file is writable when it can be opened for appending, which writes nothing.
function Test-CatWritable([string] $Path)
{
	try
	{
		if (Test-Path -LiteralPath $Path -PathType Container)
		{
			$probe = Join-Path $Path ('.cat-write-check.' + [Guid]::NewGuid().ToString('N'))
			[IO.File]::Open($probe, 'CreateNew', 'Write').Close()
			[IO.File]::Delete($probe)
		}
		else
		{
			[IO.File]::Open($Path, 'Append', 'Write').Close()
		}
		return $true
	}
	catch
	{
		return $false
	}
}

# Throws that CAT changed nothing because it cannot write a path, with the command that repeats the operation.
function Stop-CatNotWritable([string] $Path, [string] $Command)
{
	throw ("CAT changed nothing because it cannot write $Path.`n" +
		"An agent's sandbox usually causes this. Run this command in a PowerShell window outside the sandbox:`n`n" +
		"  $Command`n`n" +
		"If you are an agent that ran the command that printed this, show the user the command above exactly as " +
		"printed, ask them to run it outside the sandbox, and end your turn there. Do not summarize the command or go " +
		"on to other work.")
}

# Returns the absolute form of a path, resolving a relative one against PowerShell's current location as the cmdlets
# and programs that the operation runs do. A rooted path is not resolved by PowerShell, which rejects a missing drive.
function Resolve-CatFullPath([string] $Path)
{
	if ([IO.Path]::IsPathRooted($Path)) { return [IO.Path]::GetFullPath($Path) }
	return [IO.Path]::GetFullPath($ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($Path))
}

# Throws, before any change, when the operation could not create or change one of the paths. A relative path is
# resolved against PowerShell's current location, as the operation resolves it. An existing file is checked together
# with its folder, because a program can replace a file by renaming a new one over it; a missing path is checked at the
# nearest existing folder above it, where the operation creates the path, and a path with no existing folder above it,
# such as one on a missing drive, cannot be written.
function Assert-CatWritable([string] $Command, [string[]] $Paths)
{
	foreach ($path in $Paths)
	{
		$existing = Resolve-CatFullPath $path
		while (-not (Test-Path -LiteralPath $existing))
		{
			$parent = [IO.Path]::GetDirectoryName($existing)
			if (-not $parent) { Stop-CatNotWritable $existing $Command }
			$existing = $parent
		}
		if (-not (Test-CatWritable $existing)) { Stop-CatNotWritable $existing $Command }
		if (-not (Test-Path -LiteralPath $existing -PathType Container))
		{
			$folder = [IO.Path]::GetDirectoryName($existing)
			if (-not (Test-CatWritable $folder)) { Stop-CatNotWritable $folder $Command }
		}
	}
}

# Throws, before any change, when the operation could not delete one of the paths that exist: both the path and the
# folder that holds it must be writable. A relative path is resolved against PowerShell's current location, and a
# missing path is skipped.
function Assert-CatRemovable([string] $Command, [string[]] $Paths)
{
	foreach ($path in $Paths)
	{
		$fullPath = Resolve-CatFullPath $path
		if (-not (Test-Path -LiteralPath $fullPath)) { continue }
		foreach ($checked in @([IO.Path]::GetDirectoryName($fullPath), $fullPath))
		{
			if (-not (Test-CatWritable $checked)) { Stop-CatNotWritable $checked $Command }
		}
	}
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
# Until the write check passes, paths are joined with [IO.Path]::Combine, because Join-Path rejects a path on a drive
# that does not exist, which the write check reports instead
$dataDirectory = $env:CAT_CODEX_DATA_DIR
if (-not $dataDirectory) { $dataDirectory = [IO.Path]::Combine($codexHome, 'plugins\data\cat-cat') }
$marketplaceRoot = $env:CAT_CODEX_MARKETPLACE_ROOT
if (-not $marketplaceRoot) { $marketplaceRoot = [IO.Path]::Combine($codexHome, 'plugins\cat-marketplace') }
Assert-CatMarketplaceRoot $marketplaceRoot

if ($Uninstall)
{
	if ($Source) { throw '-Source cannot be used with -Uninstall' }
	if ($DeleteData) { Assert-CatDataDirectory $dataDirectory }
	$userProfile = $env:USERPROFILE
	if (-not $userProfile) { $userProfile = [Environment]::GetFolderPath('UserProfile') }
	$bundles = [IO.Path]::Combine($userProfile, '.cat\bundles')
	$arguments = '-Uninstall'
	if ($DeleteData) { $arguments += ' -DeleteData' }
	$rerunCommand = Get-CatRerunCommand " $arguments"
	# "codex plugin remove" records the removal in config.toml and deletes the plugin from the plugin cache
	$writable = @($codexHome, [IO.Path]::Combine($codexHome, 'config.toml'), [IO.Path]::Combine($codexHome, 'tmp'),
		[IO.Path]::Combine($codexHome, 'plugins\cache'))
	if (Test-Path -LiteralPath ([IO.Path]::Combine($dataDirectory, 'codex-agent-files')))
	{
		$writable += @([IO.Path]::Combine($codexHome, 'agents'), $dataDirectory)
	}
	Assert-CatWritable $rerunCommand $writable
	$removable = @($marketplaceRoot, $bundles)
	if ($DeleteData) { $removable += $dataDirectory }
	Assert-CatRemovable $rerunCommand $removable

	Remove-CatAgents $codexHome $dataDirectory
	Invoke-CatProgram 'codex' @('plugin', 'remove', 'cat@cat')
	Invoke-CatProgramQuietly 'codex' @('plugin', 'marketplace', 'remove', 'cat')
	if (Test-Path -LiteralPath $marketplaceRoot)
	{
		Remove-Item -LiteralPath $marketplaceRoot -Recurse -Force
	}
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
if (-not $Source -or -not (Test-Path -LiteralPath $Source -PathType Container))
{
	throw "Expected an extracted artifact directory: $Source"
}
$artifact = (Resolve-Path -LiteralPath $Source).ProviderPath
$rerunCommand = Get-CatRerunCommand ''
Assert-CatArtifact $artifact
# Codex replaces config.toml by renaming a temporary file from its home folder over it when "codex plugin
# marketplace add" and "codex plugin add" record the marketplace and the plugin, and "codex plugin add" copies the
# plugin into the plugin cache
Assert-CatWritable $rerunCommand @($codexHome, [IO.Path]::Combine($codexHome, 'config.toml'),
	[IO.Path]::Combine($codexHome, '.tmp'), [IO.Path]::Combine($codexHome, 'tmp'),
	[IO.Path]::Combine($codexHome, 'plugins\cache'), [IO.Path]::Combine($codexHome, 'agents'), $dataDirectory,
	[IO.Path]::Combine($marketplaceRoot, '..'))
Assert-CatRemovable $rerunCommand @($marketplaceRoot)

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
