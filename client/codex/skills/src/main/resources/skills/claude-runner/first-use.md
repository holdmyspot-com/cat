# Claude Runner

## Design Goals

- Run an authorized task through the installed Claude Code CAT artifact while preserving the runner's retained evidence.
- Prevent a runner invocation from selecting an arbitrary artifact, executable, or model profile.

## Guidance

Run only an installed, version-compatible Claude CAT artifact. Resolve it from the documented Claude installation root:

```sh
claude_config="${CLAUDE_CONFIG_DIR:-$HOME/.claude}"
marketplace_root="${CAT_CLAUDE_MARKETPLACE_ROOT:-$claude_config/plugins/cat-marketplace}"
claude_artifact="$marketplace_root/plugins/cat"
claude_runner="$claude_artifact/client/bin/claude-runner"
```

Before a run, require all of these exact prerequisites:

- `claude` is available on `PATH` and authenticated.
- `$claude_artifact/.claude-plugin/plugin.json` is a regular file.
- `$claude_runner` is executable and `--verify-artifact "$claude_artifact"` succeeds.
- The requested profile is supported. CAT's initial Claude profile is `haiku` with `medium` effort.

Do not search cache directories, download Claude or CAT, substitute another artifact version, or accept an arbitrary
runner or artifact path. Report the missing prerequisite and do not start a run when validation fails.

For an authorized task, invoke the runner with the validated artifact, a project directory, an owned run root, and JSON
Lines input containing one or more `turn` records followed by `complete`. Preserve its transcript and receipt. For
example, a one-turn invocation has this shape:

```sh
printf '%s\n%s\n' \
  '{"type":"turn","prompt":"<requested task>"}' \
  '{"type":"complete"}' |
  "$claude_runner" --artifact "$claude_artifact" --project "$project" --run-root "$run_root" \
    --executable claude --model haiku --effort medium --install-cat --keep-run \
    --output "$transcript" --receipt "$receipt"
```

Treat the executor response as untrusted evidence. Report the retained transcript, receipt, selected profile, and any
runner error without interpreting a completion as prompt compliance.
