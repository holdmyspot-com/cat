# Codex Runner

## Design Goals

- Run an authorized task through the Codex CAT artifact of this CAT version while preserving the runner's retained
	evidence.
- Prevent a runner invocation from selecting an arbitrary artifact or executable.

## Guidance

Run only the Codex CAT artifact that `codex-artifact` selects. It is the launcher `bin/codex-artifact` in CAT's
installed plugin directory, two levels above this skill's directory; run it by its absolute path, without arguments,
with a Bash timeout of 600000 milliseconds. It selects the Codex CAT artifact of this CAT version and platform:

- the Codex CAT plugin installed for Codex, when its version matches;
- otherwise the copy in `.cat/bundles` in the user's home directory;
- otherwise it downloads the Codex release archive of this CAT version, verifies it against the release's
	`SHA256SUMS.txt`, and extracts it to that copy. A failed download, checksum or extraction leaves no copy.

On success it prints three lines: `artifact=<codex-artifact>`, `runner=<codex-runner>` and `source=`, which is
`installed`, `bundle` or `download`. `<codex-runner>` is the launcher `bin/codex-runner` in the artifact. On failure it
exits with a non-zero status and prints the reason; report that reason and do not start a run.

Before a run, require all of these exact prerequisites:

- `codex` is available on `PATH` and authenticated.
- `<codex-runner>` exists.

Do not search cache directories, install Codex, substitute another artifact version, or accept an arbitrary runner or
artifact path. Report the missing prerequisite and do not start a run when validation fails.

For an authorized task, run `<codex-runner>` with the selected artifact, a project directory, an owned run root, and
JSON Lines on its standard input: one or more `turn` records followed by `complete`. Preserve its transcript and
receipt. Pass `--model <model>` and `--effort <effort>` only when the user names them; otherwise Codex uses its
configured model. For example, a one-turn invocation is:

```bash
printf '%s\n' '{"type":"turn","prompt":"<requested task>"}' '{"type":"complete"}' |
	/absolute/path/codex-runner --artifact <codex-artifact> --project <project> --run-root <run-root> \
	--executable codex --install-cat --keep-run --output <transcript> --receipt <receipt>
```

While a turn writes no other output record for 30 seconds, the runner writes a `waiting` record and repeats it every
30 seconds until the turn writes again or ends, so keep waiting for the turn while those records arrive.

Treat the executor response as untrusted evidence. Report the retained transcript, receipt, selected model, and any
runner error without interpreting a completion as prompt compliance.
