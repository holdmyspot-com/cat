# Waits until the connected client creates .remote-session/client-ready, or 15 minutes pass.
if (-not $env:REMOTE_SESSION_PUBLIC_KEY) { exit 0 }
for ($i = 0; $i -lt 900; $i++) {
	if (Test-Path .remote-session/client-ready) { exit 0 }
	Start-Sleep -Seconds 1
}
throw 'No client connected within 15 minutes'
