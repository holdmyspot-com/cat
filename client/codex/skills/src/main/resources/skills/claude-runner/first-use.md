# Claude Runner

## Design Goals

- Run an authorized task through the installed Claude Code CAT artifact while preserving the runner's retained evidence.
- Prevent a runner invocation from selecting an arbitrary artifact, executable, or model profile.

## Guidance

Run only an installed, version-compatible Claude CAT artifact. Resolve it from the documented Claude installation root:

- The Claude configuration directory is the value of the `CLAUDE_CONFIG_DIR` environment variable or, when that is
  unset or empty, `.claude` in the user's home directory.
- The marketplace root is the value of `CAT_CLAUDE_MARKETPLACE_ROOT` or, when that is unset or empty,
  `plugins/cat-marketplace` in the Claude configuration directory.
- The artifact, `<claude-artifact>`, is `plugins/cat` in the marketplace root.
- The runner, `<claude-runner>`, is the launcher `bin/claude-runner` in the artifact.

Before a run, require all of these exact prerequisites:

- `claude` is available on `PATH` and authenticated.
- `.claude-plugin/plugin.json` in `<claude-artifact>` is a regular file.
- `<claude-runner>` exists, and running it with the arguments `--verify-artifact <claude-artifact>` succeeds.
- The requested profile is supported. CAT's initial Claude profile is `haiku` with `medium` effort.

Do not search cache directories, download Claude or CAT, substitute another artifact version, or accept an arbitrary
runner or artifact path. Report the missing prerequisite and do not start a run when validation fails.

For an authorized task, run `<claude-runner>` with the validated artifact, a project directory, an owned run root, and
JSON Lines on its standard input: one or more `turn` records followed by `complete`. Preserve its transcript and
receipt. For example, a one-turn invocation sends these lines:

```text
{"type":"turn","prompt":"<requested task>"}
{"type":"complete"}
```

and this argument list, wrapped here to fit:

```text
--artifact <claude-artifact> --project <project> --run-root <run-root> --executable claude --model haiku
--effort medium --install-cat --keep-run --output <transcript> --receipt <receipt>
```

While a turn writes no other output record for 30 seconds, the runner writes a `waiting` record and repeats it every
30 seconds until the turn writes again or ends, so keep waiting for the turn while those records arrive.

Treat the executor response as untrusted evidence. Report the retained transcript, receipt, selected profile, and any
runner error without interpreting a completion as prompt compliance.
