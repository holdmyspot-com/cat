# Claude Runner

## Design Goals

- Run an authorized task through the Claude Code CAT artifact of this CAT version while preserving the runner's
	retained evidence.
- Prevent a runner invocation from selecting an arbitrary artifact, executable, or model profile.

## Guidance

Run only the Claude CAT artifact that `claude-artifact` selects. It is the launcher `bin/claude-artifact` in CAT's
installed plugin directory, two levels above this skill's directory; run it by its absolute path, without arguments.
It selects the Claude CAT artifact of this CAT version and platform:

- the Claude CAT plugin installed for Claude Code, when its version matches;
- otherwise the copy in `.cat/bundles` in the user's home directory;
- otherwise it downloads the Claude release archive of this CAT version, verifies it against the release's
	`SHA256SUMS.txt`, and extracts it to that copy. A failed download, checksum or extraction leaves no copy.

On success it prints three lines: `artifact=<claude-artifact>`, `runner=<claude-runner>` and `source=`, which is
`installed`, `bundle` or `download`. `<claude-runner>` is the launcher `bin/claude-runner` in the artifact. On failure
it exits with a non-zero status and prints the reason; report that reason and do not start a run.

Before a run, require all of these exact prerequisites:

- `claude` is available on `PATH` and authenticated.
- `<claude-runner>` exists, and running it with the arguments `--verify-artifact <claude-artifact>` succeeds.
- The requested profile is supported. CAT's initial Claude profile is `haiku` with `medium` effort.

Do not search cache directories, install Claude Code, substitute another artifact version, or accept an arbitrary
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

Pass `--session-transcripts` with `--keep-run` only when the user asks for the session's own transcript. Without it,
Claude Code keeps no session of the run. With it, the runner copies every transcript of the session, subagents'
included, into `artifacts/session-transcripts` in the run directory, writes a `session_transcript` record with the
transcript's path once the session's identifier is known, and lists the copies in the receipt.

Treat the executor response as untrusted evidence. Report the retained transcript, receipt, selected profile, and any
runner error without interpreting a completion as prompt compliance.
