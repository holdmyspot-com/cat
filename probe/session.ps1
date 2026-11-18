<#
.SYNOPSIS
Hosts a task-key-only Windows diagnostic shell until its caller exits.
.DESCRIPTION
Reads probe/authorized-key.pub and probe/known_hosts from the checkout. Downloads hash-pinned Upterm 0.41.1,
publishes connection.json in RUNNER_TEMP/cat-remote, and returns only after the hosted shell exits. The shell
ends after ten idle minutes; an unclaimed session ends after ten minutes. Native command failures terminate
setup. The workflow retains the connection and terminal receipts, then removes the key and workspace.
#>
$ErrorActionPreference = 'Stop'
# A pinned official release is required on both download routes.
$version = '0.41.1'
$digest = '7d50afedb81aa91cedadf774ec2a9cad949d1081f3a8383c2bb47be4ab11afc6'
$root = Join-Path $env:RUNNER_TEMP 'cat-remote'
New-Item -ItemType Directory -Path $root -ErrorAction Stop | Out-Null
$archive = Join-Path $root 'upterm.tar.gz'
try {
    Invoke-WebRequest "https://github.com/owenthereal/upterm/releases/download/v$version/upterm_windows_amd64.tar.gz" -OutFile $archive
} catch {
    # GitHub's asset API serves the same immutable release bytes; the same digest verifies this fallback.
    Invoke-WebRequest 'https://api.github.com/repos/owenthereal/upterm/releases/assets/714435830' `
        -Headers @{ Accept = 'application/octet-stream'; 'X-GitHub-Api-Version' = '2022-11-28' } -OutFile $archive
}
if ((Get-FileHash -Algorithm SHA256 -LiteralPath $archive).Hash.ToLowerInvariant() -ne $digest) {
    throw 'Upterm archive checksum mismatch'
}
tar -xzf $archive -C $root
if ($LASTEXITCODE -ne 0) { throw 'Cannot unpack Upterm' }
$key = Join-Path $root 'authorized-key.pub'
Copy-Item -LiteralPath 'probe/authorized-key.pub' -Destination $key
ssh-keygen -l -f $key
if ($LASTEXITCODE -ne 0) { throw 'Invalid task public key' }
$knownHosts = Join-Path $root 'known_hosts'
Copy-Item -LiteralPath 'probe/known_hosts' -Destination $knownHosts
$rc = Join-Path $root 'shell.rc'
@'
# Diagnostic shell: leave running commands alone, but end after ten minutes waiting for input.
TMOUT=600
readonly TMOUT
PS1='CAT_REMOTE_READY> '
unset PROMPT_COMMAND
'@ | Set-Content -LiteralPath $rc -Encoding utf8NoBOM
$bash = 'C:/msys64/usr/bin/bash.exe'
if (!(Test-Path -LiteralPath $bash)) { throw 'The Windows worker has no MSYS2 Bash' }
$upterm = Join-Path $root 'upterm.exe'
$connection = & $upterm host --detach --accept --allow-exec --name cat-windows --output json `
    --authorized-keys $key --known-hosts $knownHosts --join-timeout 10m -- `
    $bash --noprofile --rcfile ($rc.Replace('\', '/')) -i
if ($LASTEXITCODE -ne 0) { throw 'Cannot start Upterm' }
$connection | Set-Content -LiteralPath (Join-Path $root 'connection.json') -Encoding utf8NoBOM
# The workflow uploads the connection while this process waits on the same session.
Write-Output 'CAT_CONNECTION_PUBLISHED'
& $upterm session wait cat-windows
$sessionExit = $LASTEXITCODE
& $upterm session info cat-windows --output json | Set-Content -LiteralPath (Join-Path $root 'terminal.json') -Encoding utf8NoBOM
if ($LASTEXITCODE -ne 0) { throw 'Cannot retain Upterm terminal state' }
exit $sessionExit
