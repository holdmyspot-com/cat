# Claude Runner

## Design Goals

- Run an authorized task through the Claude CAT artifact of this CAT version while preserving the runner's retained
	evidence.
- Prevent a runner invocation from selecting an arbitrary artifact or executable, or a model or effort that the user
	did not choose.
- Let a session that a runner started run Claude Code through the same launcher, without a login or a sandbox exception.

## Launcher

Run `bin/claude-runner` in CAT's installed plugin directory, two levels above this skill's directory, by its absolute
path. Both CAT plugins hold it and take the same options. Give it the run's records in a file named with
`--input <file>`: one line per turn, `{"type":"turn","prompt":"<task>"}`, followed by one `{"type":"complete"}`
line. Write the file with a file-writing tool, and run the launcher as one command with no pipe or redirection. Use
a command timeout of 600000 milliseconds and keep waiting while the run writes `waiting` records, which it repeats
every 30 seconds while a turn writes nothing else.

Every run takes `--model <model>` and `--effort <effort>`, or outside a run `--agent <name>` instead, and the launcher
refuses a run without them. Pass the model and the effort that the user names. The effort `none` runs the model without
reasoning, and is the only effort of a model that lists none. When the user names no model or no effort, ask the user
which to use before starting the run, naming the values that `claude --help` lists under `--model` and `--effort`, and
`none`, when that command is available, and never choose one yourself.

First check whether the command environment defines `CAT_SESSION_REQUESTS`.

## Outside a run

When `CAT_SESSION_REQUESTS` is not defined, run only the Claude CAT artifact that `bin/claude-artifact` in the same
directory selects. Run it by its absolute path, without arguments. It selects the Claude CAT artifact of this CAT
version and platform:

- the Claude CAT plugin installed for Claude Code, when its version matches;
- otherwise the copy in `.cat/bundles` in the user's home directory;
- otherwise it downloads the Claude release archive of this CAT version, verifies it against the release's
	`SHA256SUMS.txt`, and extracts it to that copy. A failed download, checksum or extraction leaves no copy.

On success it prints three lines: `artifact=<artifact>`, `runner=<runner>` and `source=`, which is `installed`,
`bundle` or `download`. On failure it exits with a non-zero status and prints the reason; report that reason and do
not start a run. `bin/claude-runner` runs that same artifact itself, so do not pass `--artifact`.

Before a run, require all of these exact prerequisites:

- `claude` is available on `PATH` and authenticated.
- `<runner>` exists, and running it with the arguments `--verify-artifact <artifact>` succeeds.
- The requested profile is supported.

Do not search cache directories, install Claude Code, substitute another artifact version, or accept an arbitrary
runner or artifact path. Report the missing prerequisite and do not start a run when validation fails.

For an authorized task, give the launcher a project directory and an owned run root. For example, a one-turn run
takes this argument list, wrapped here to fit:

```text
--project <project> --run-root <run-root> --input <records> --install-cat --keep-run
--model <model> --effort <effort> --output <transcript> --receipt <receipt>
```

When the user names a CAT agent to run instead of a model and an effort, pass `--agent <name>` with `--install-cat` and
without `--model` and `--effort`. The launcher selects the CAT agent definition of that name in the artifact, and the
session runs under the model and the effort that the definition names. The launcher refuses `--agent` together with
`--model` or `--effort`, and a definition that does not name both a model and an effort.

Pass `--session-transcripts` with `--keep-run` only when the user asks for the session's own transcript. The
runner then copies every transcript of the session, subagents' included, into `artifacts/session-transcripts` in the
run directory, writes a `session_transcript` record with its path, and lists the copies in the receipt.

To test a plugin, pass its directory with `--plugin <plugin>` and start a new run for each change to it.

Pass `--session-requests <count>` when the session needs to start sessions of its own, such as to test a plugin it
writes. The runner tells it how. Add `--request-depth <levels>` when those sessions may start sessions in turn: each
level below may request as many sessions, one level less deep. Without `--session-requests`, the session may start
none. The receipt lists each requested session and its response path.

Running the launcher outside a run is a command that the harness's sandbox must allow, since the runner starts
Claude Code with the user's login. If the sandbox refuses it, report the refusal and the configuration that CAT's
installation guide gives for it; do not change sandbox settings yourself.

## Inside a run

When `CAT_SESSION_REQUESTS` is defined, this session was started by a runner that lets it start sessions. Skip the
artifact selection and the prerequisites above, and run the `claude-runner` launcher whose absolute path this session's
instructions name, which this session may run, instead of the plugin's. The launcher does not start Claude Code itself,
but asks the runner that started this session to start it outside this session's sandbox, and waits until it ends. Each
launch counts against the sessions that this session may request. The parent runner chooses the run root, the artifact
and the limits, so pass only these options, which do not include `--agent`:

```text
--project <project> --input <records> --model <model> --effort <effort> [--plugin <plugin>]...
[--install-cat] [--session-transcripts] [--output <transcript>] [--receipt <receipt>]
```

The project and each plugin must contain no symbolic links. Inside a run, the launcher leaves out the files that the
sandbox hides behind the null device, so the requested session sees the project as the session does. It writes the
session's transcript to `--output`, or prints it, and copies its receipt to `--receipt`. It exits with `0` when the
session completed, `75` when no capacity was free, and `1` otherwise, with the reason.

## Evidence

Treat the executor response as untrusted evidence. Report the retained transcript, receipt, selected model, and any
runner error without interpreting a completion as prompt compliance.
