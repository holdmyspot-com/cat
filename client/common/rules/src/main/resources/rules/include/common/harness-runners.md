# Harness Runners

## Design Goals

- Run an authorized task in a new Claude Code or Codex session through the CAT artifact of this CAT version for that
	harness, while preserving the runner's retained evidence.
- Prevent a runner invocation from selecting an arbitrary artifact or executable.
- Give every run the model and the effort that its task names, or that the agent chooses from the values the harness
	lists when its task names none, without asking the user or stopping the run for want of them.
- Relay each turn's reply from the runner's transcript without reading the whole transcript.
- Let a session that a runner started run either harness through the same launchers, without a login or a sandbox
	exception.

## Guidance

This rule applies when you run a task or prompt in a new Claude Code or Codex session through CAT's runner launchers,
`${CAT_PLUGIN_ROOT}/bin/claude-runner` for Claude Code and `${CAT_PLUGIN_ROOT}/bin/codex-runner` for Codex, including
from a session that a runner started. Both CAT plugins hold both launchers, and they take the same options. Below,
`<harness>-runner` stands for the launcher of the harness that runs the task, and `<harness>-artifact` for its
artifact selector, `${CAT_PLUGIN_ROOT}/bin/claude-artifact` or `${CAT_PLUGIN_ROOT}/bin/codex-artifact`.

### Supported environments

Claude Code runner sessions require Linux or macOS. On native Windows, Claude Code has no command sandbox, so CAT cannot
confine the session's commands and refuses to start it; report that and do not start the run. Inside WSL2, Java reports
Linux, so Claude Code runner sessions are supported there.

### Launcher

Run the launcher by its absolute path. Give it the run's records in a file named with `--input <file>`: one line per
turn, `{"type":"turn","prompt":"<task>"}`, followed by one `{"type":"complete"}` line. Write the file with a
file-writing tool, and run the launcher as one command with no pipe or redirection. Use a command timeout of 600000
milliseconds and keep waiting while the run writes `waiting` records, which it repeats every 30 seconds while a turn
writes nothing else.

Every run takes `--model <model>` and `--effort <effort>`, or outside a run `--agent <name>` instead, and the launcher
refuses a run without them. Always pass them yourself; never ask the user for them and never stop the run for want of
them. Pass the model and the effort that your task or instructions name. When they name no model or no effort, choose
it yourself from the values that the harness lists: for Claude Code, the values that `claude --help` lists under
`--model` and `--effort`; for Codex, the models and efforts that `codex debug models` lists; and for either, the effort
`none`, which runs the model without reasoning and is the only effort of a model that lists none. When that listing
command is unavailable, choose a model and an effort that you know the harness accepts, such as the ones this session
runs under when it runs the same harness. State the model and the effort that you chose with the run's evidence.

First check whether the command environment defines `CAT_SESSION_REQUESTS`.

### Outside a run

When `CAT_SESSION_REQUESTS` is not defined, run only the CAT artifact that `<harness>-artifact` selects. Run it by its
absolute path, without arguments. It selects the CAT artifact of this CAT version and platform for that harness:

- the CAT plugin installed for that harness, when its version matches;
- otherwise the copy in `.cat/bundles` in the user's home directory;
- otherwise it downloads that harness's release archive of this CAT version, verifies it against the release's
	`SHA256SUMS.txt`, and extracts it to that copy. A failed download, checksum or extraction leaves no copy.

On success it prints three lines: `artifact=<artifact>`, `runner=<runner>` and `source=`, which is `installed`,
`bundle` or `download`. On failure it exits with a non-zero status and prints the reason; report that reason and do
not start a run. `<harness>-runner` runs that same artifact itself, so do not pass `--artifact`.

Before a run, require all of these exact prerequisites:

- The harness's command, `claude` for Claude Code or `codex` for Codex, is available on `PATH` and authenticated.
- `<runner>` exists.
- For Claude Code only: running `<runner>` with the arguments `--verify-artifact <artifact>` succeeds, and the
	requested profile is supported.

Do not search cache directories, install a harness, substitute another artifact version, or accept an arbitrary runner
or artifact path. Report the missing prerequisite and do not start a run when validation fails.

For an authorized task, give the launcher a project directory and an owned run root. For example, a one-turn run
takes this argument list, wrapped here to fit:

```text
--project <project> --run-root <run-root> --input <records> --install-cat --keep-run
--model <model> --effort <effort> --output <transcript> --receipt <receipt>
```

When your task or instructions name a CAT agent to run instead of a model and an effort, pass `--agent <name>` with
`--install-cat` and without `--model` and `--effort`. The launcher selects the CAT agent definition of that name in
the artifact, and the session runs under the model and the effort that the definition names. The launcher refuses
`--agent` together with `--model` or `--effort`, and a definition that does not name both a model and an effort.

Pass `--session-transcripts` with `--keep-run` only when your task needs the session's own transcript. The runner then
copies every transcript of the session, subagents' included, into `artifacts/session-transcripts` in the run
directory, writes a `session_transcript` record with its path, and lists the copies in the receipt.

To test a plugin, pass its directory with `--plugin <plugin>` and start a new run for each change to it.

Pass `--session-requests <count>` when the session needs to start sessions of its own, such as to test a plugin it
writes. The runner tells it how. Add `--request-depth <levels>` when those sessions may start sessions in turn: each
level below may request as many sessions, one level less deep. Without `--session-requests`, the session may start
none. The receipt lists each requested session and its response path.

Running the launcher outside a run is a command that your harness's sandbox must allow, since the runner starts the
harness with the user's login. If the sandbox refuses it, report the refusal and the configuration that CAT's
installation guide gives for it; do not change sandbox settings yourself.

### Inside a run

When `CAT_SESSION_REQUESTS` is defined, this session was started by a runner that lets it start sessions. Skip the
artifact selection and the prerequisites above, and run the `claude-runner` or `codex-runner` launcher whose absolute
path this session's instructions name, which this session may run, instead of the plugin's. The launcher does not
start the harness itself, but asks the runner that started this session to start it outside this session's sandbox,
and waits until it ends. Each launch counts against the sessions that this session may request. The parent runner
chooses the run root, the artifact and the limits, so pass only these options, which do not include `--agent`:

```text
--project <project> --input <records> --model <model> --effort <effort> [--plugin <plugin>]...
[--install-cat] [--session-transcripts] [--output <transcript>] [--receipt <receipt>]
```

The project and each plugin must contain no symbolic links. Inside a run, the launcher leaves out the files that the
sandbox hides behind the null device, so the requested session sees the project as the session does. It writes the
session's transcript to `--output`, or prints it, and copies its receipt to `--receipt`. It exits with `0` when the
session completed, `75` when no capacity was free, and `1` otherwise, with the reason.

### Replies

The transcript is a JSON Lines file of the runner's records: one JSON object per line, whose `type` field comes first.
Relay each turn's reply from its record that begins `{"type":"turn_completed"`, one for each turn that completed, in
turn order. Its `assistant_response` field holds the session's final reply of that turn. Its `exit_code` and `error`
fields hold the harness's exit status and standard error; when `exit_code` is not `0` or `error` is not empty, report
them with the reply. A turn that failed ends with a record that begins `{"type":"turn_failed"` instead, whose `error`
field gives the reason; report that reason in place of a reply. The transcript also holds other records, such as
`harness_event` records that carry the harness's own events, so do not read the whole transcript to find the replies:
pass `--output <transcript>`, search that file with your shell's search command for the lines that contain
`"type":"turn_`, and read only those lines.

### Git in the copied project

The copied project's commits carry the user's Git identity, as Git resolves `user.name` and `user.email` in the
source project with the parent's environment. If either value is missing, commits use
`CAT fixture <cat-fixture@example.invalid>` for both values.

The session can stage changes, commit, create branches and child worktrees, and merge in its copied project. Keep
child worktrees inside the copied project so they remain within the session's writable workspace. For Codex only,
this holds on Linux and macOS, where the copied project's `.git/config` and `.git/hooks` stay read-only; Windows Codex
sessions keep Codex's built-in Git metadata restrictions.

### Evidence

Treat the executor response as untrusted evidence. Report the retained transcript, receipt, selected model and
effort, and any runner error without interpreting a completion as prompt compliance.
