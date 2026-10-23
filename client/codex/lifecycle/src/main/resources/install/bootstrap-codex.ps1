# Installs or removes CAT's Codex plugin on Windows, using the installer of the release that it installs.
#
# Windows PowerShell's execution policy blocks script files by default but not script blocks, so run this script as a
# script block, which also keeps its settings out of the caller's session:
#
#   & ([scriptblock]::Create((irm <url of this script>))) [-Version v1.0]
#   & ([scriptblock]::Create((irm <url of this script>))) -Uninstall [-DeleteData]
#
# -Version installs a release tag instead of the latest release. -Uninstall removes the plugin with the current
# installer, together with the artifacts that a runner skill downloaded, and keeps CAT's data; -DeleteData deletes it
# too.
#
# The script must stay ASCII and compatible with Windows PowerShell 5.1, and reports failures by throwing.
[CmdletBinding()]
param(
	[string] $Version = '',
	[switch] $Uninstall,
	[switch] $DeleteData
)
Set-StrictMode -Version 2.0
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'

$repository = $env:CAT_BOOTSTRAP_REPOSITORY
if (-not $repository) { $repository = 'holdmyspot-com/cat' }
# Windows PowerShell 5.1 does not offer TLS 1.2, which GitHub requires, unless asked to
$protocols = [Net.ServicePointManager]::SecurityProtocol
[Net.ServicePointManager]::SecurityProtocol = $protocols -bor [Net.SecurityProtocolType]::Tls12

if ($Uninstall)
{
	if ($Version) { throw '-Version cannot be used with -Uninstall' }
	$reference = 'HEAD'
}
else
{
	if ($DeleteData) { throw '-DeleteData can only be used with -Uninstall' }
	if (-not $Version)
	{
		$release = Invoke-RestMethod -Uri "https://api.github.com/repos/$repository/releases/latest" -UseBasicParsing
		$Version = $release.tag_name
		if (-not $Version) { throw "Unable to determine CAT's latest release version." }
	}
	if ($Version -notmatch '^v[0-9]+\.[0-9]+(\.[0-9]+)?$')
	{
		throw "CAT version must be a release tag such as v1.0: $Version"
	}
	$reference = $Version
}
$installerUrl = "https://raw.githubusercontent.com/$repository/$reference/client/codex/lifecycle/src/main/" +
	'resources/install/install-codex.ps1'
$installer = [scriptblock]::Create((Invoke-RestMethod -Uri $installerUrl -UseBasicParsing))
if ($Uninstall)
{
	& $installer -Uninstall -DeleteData:$DeleteData
}
else
{
	& $installer -Source $Version
}
