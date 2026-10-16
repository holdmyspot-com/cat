# Claude Runner

## Design Goals

- Run an authorized task through the installed Claude Code CAT artifact while preserving the runner's retained evidence.
- Prevent a runner invocation from selecting an arbitrary artifact, executable, or model profile.

## Guidance

Run only an installed, version-compatible Claude CAT artifact. Resolve it from the documented Claude installation root:

```powershell
$claudeConfig = if ($env:CLAUDE_CONFIG_DIR) { $env:CLAUDE_CONFIG_DIR } else { Join-Path $env:USERPROFILE '.claude' }
$marketplaceRoot = if ($env:CAT_CLAUDE_MARKETPLACE_ROOT) { $env:CAT_CLAUDE_MARKETPLACE_ROOT } else {
  Join-Path $claudeConfig 'plugins\cat-marketplace' }
$claudeArtifact = Join-Path $marketplaceRoot 'plugins\cat'
$claudeRunner = Join-Path $claudeArtifact 'client\bin\claude-runner.bat'
```

Before a run, require all of these exact prerequisites:

- `claude` is available on `PATH` and authenticated.
- `$claudeArtifact\.claude-plugin\plugin.json` is a regular file.
- `$claudeRunner` is a regular file and `& $claudeRunner --verify-artifact $claudeArtifact` succeeds.
- The requested profile is supported. CAT's initial Claude profile is `haiku` with `medium` effort.

Do not search cache directories, download Claude or CAT, substitute another artifact version, or accept an arbitrary
runner or artifact path. Report the missing prerequisite and do not start a run when validation fails.

For an authorized task, invoke the runner with the validated artifact, a project directory, an owned run root, and JSON
Lines input containing one or more `turn` records followed by `complete`. Preserve its transcript and receipt. Write
each record in single quotes, doubling each `'` inside it, and set `$OutputEncoding` first so that PowerShell passes the
records to the runner as UTF-8. For example, a one-turn invocation has this shape:

```powershell
$OutputEncoding = [Text.UTF8Encoding]::new()
'{"type":"turn","prompt":"<requested task>"}', '{"type":"complete"}' |
  & $claudeRunner --artifact $claudeArtifact --project $project --run-root $runRoot `
    --executable claude --model haiku --effort medium --install-cat --keep-run `
    --output $transcript --receipt $receipt
```

While a turn writes no other output record for 30 seconds, the runner writes a `waiting` record and repeats it every
30 seconds until the turn writes again or ends, so keep waiting for the turn while those records arrive.

Treat the executor response as untrusted evidence. Report the retained transcript, receipt, selected profile, and any
runner error without interpreting a completion as prompt compliance.
