# Harness Runners

## Design Goals

- Run an authorized task in a new Claude Code or Codex session through the CAT artifact of this CAT version for that
	harness, while preserving the runner's retained evidence.
- Start a session through a runner or a session request only for a task that needs a separate, isolated session, and
	leave whether the agent delegates any other task to a subagent or does it itself to its delegation rules.
- Prevent a runner invocation from selecting an arbitrary artifact or executable.
- Give every run and every session request the model and the effort that its task names, or that the agent chooses
	from the values the harness lists when its task names none, without asking the user or stopping the run for want of
	them.
- Relay each turn's reply from the runner's transcript without reading the whole transcript.
- Let a session that a runner started run either harness through the same launchers, or through the session-request
	launcher that its instructions name, without a login or a sandbox exception.
- Let a session send each turn of a new session after it reads the reply to the previous one, interrupt a turn, and end
	the new session, through any of these launchers.

## Guidance

This rule applies when you run a task or prompt in a new Claude Code or Codex session through CAT's runner launchers,
`${CAT_PLUGIN_ROOT}/bin/claude-runner` for Claude Code and `${CAT_PLUGIN_ROOT}/bin/codex-runner` for Codex, including
from a session that a runner started, and when such a session requests a new session with the `request-session`
launcher whose absolute path its instructions name. Both CAT plugins hold both runner launchers, and they take the same
options. Below, `<harness>-runner` stands for the launcher of the harness that runs the task, and `<harness>-artifact`
for its artifact selector, `${CAT_PLUGIN_ROOT}/bin/claude-artifact` or `${CAT_PLUGIN_ROOT}/bin/codex-artifact`.

### When to use a runner

Start a session through these launchers only when the task needs a separate, isolated session: for example, one that
runs in a harness other than yours, tests a plugin or a fresh CAT installation, starts from a fresh copy of the project
so that it begins from a clean state, or must not see this session's context. Do not start a runner for a task that
needs no isolated session; whether you delegate it to a subagent or do it yourself follows your delegation rules.

### Supported environments

Runner sessions of both harnesses require Linux or macOS, because CAT starts each session only inside its own
harness's sandbox. On native Windows, both runner launchers refuse to start a session, exit with status `2` and print
the reason, which tells the user to run their harness and CAT inside WSL2. Report that reason and do not start the run
any other way, such as by running the launcher through `wsl`. Inside WSL2, Java reports Linux, so runner sessions are
supported there.

### Launcher

First check whether the command environment sets `CAT_SESSION_REQUESTS` to a non-blank value. When it does, this
session was started by a runner that lets it start sessions: follow "Inside a run" and skip "Outside a run". Do not run
`<harness>-artifact` or `--verify-artifact`, and do not check the prerequisites of "Outside a run". When it does not,
skip "Inside a run".

Run the launcher by its absolute path, as one command with nothing in front of it, such as a variable assignment, `env`
or `cd`, and no pipe or redirection. Before you run it, write the run's records with a file-writing tool into a file,
one line per turn, `{"type":"turn","prompt":"<task>"}`, followed by one `{"type":"complete"}` line, and name that file
with `--input <file>`. `--input` takes only that records file, never a prompt file; prompt files are for the
`request-session` launcher. Use a command timeout of 600000 milliseconds and keep waiting while the run writes `waiting`
records, which it repeats every 30 seconds while a turn writes nothing else. When a turn's prompt depends on the reply
to the turn before it, hold a conversation instead, as "Conversations" describes.

Every run and every session request takes `--model <model>` and `--effort <effort>`, or for a run outside a run
`--agent <name>` instead, and the launcher refuses a run or request without them. Always pass them yourself; never ask
the user for them and never stop the run for want of them. Pass the model and the effort that your task or instructions
name. When they name no model or no effort, choose it yourself from the values that the harness lists: for Claude Code,
the values that `claude --help` lists under `--model` and `--effort`; for Codex, the models and efforts that
`codex debug models` lists; and for either, the effort `none`, which runs the model without reasoning and is the only
effort of a model that lists none. When that listing command is unavailable, choose a model and an effort that you know
the harness accepts, such as the ones this session runs under when it runs the same harness. State the model and the
effort that you chose with the run's evidence.

A session may need a write outside its run folder or a host that its harness's own needs do not cover, such as a
package registry or a cache outside the project. Name them in a JSON file that you write with a file-writing tool, and
pass `--extra-permissions <file>` to the launcher:

```json
{"format_version":1,"writes":["<absolute path>"],"hosts":["<host name>"]}
```

Either list may be left out. Each write is an absolute path, each host is a host name or an IP address without a `*`
pattern, and the file has no other field. Without the option the session asks for nothing extra. The session gets
them only when the sandbox of the session that starts it allows them; otherwise the launch is refused before the
harness starts, with a reason that lists each missing write and host and the settings entry that grants it. Report
that reason and do not change sandbox settings yourself.

### Inside a run

Run the `claude-runner` or `codex-runner` launcher whose absolute path this session's instructions name; the plugin's
`<harness>-runner` behaves the same way inside a run. The launcher does not start the harness itself, but asks the
runner that started this session to start it outside this session's sandbox, and waits until it ends. Each launch
counts against the sessions that this session may request. The parent runner chooses the run root, the artifact and
the limits, so pass only these options, which do not include `--agent`:

```text
--project <project> --input <records> --model <model> --effort <effort> [--plugin <plugin>]...
[--install-cat] [--session-transcripts] [--extra-permissions <file>] [--output <transcript>] [--receipt <receipt>]
```

To hold a conversation, pass `--conversation <folder>` in place of `--input`. Each plugin must contain no symbolic
links. In the project, a symbolic link whose target lies inside the project stays a link, and the session gets a copy
of the file or folder that any other link points to; the launcher refuses a link whose target is missing or that you
may not read. Inside a run, the launcher leaves out the files that the sandbox hides behind the null device, so the
requested session sees the project as the session does. It writes the
session's transcript to `--output`, or prints it, and copies its receipt to `--receipt`. It exits with `0` when the
session completed, `75` when no capacity was free, and `1` otherwise, with the reason.

A session request made with the `request-session` launcher counts against the same sessions. Write each turn's prompt
into its own file inside your project, and pass the model and the effort as above:

```text
--harness <claude|codex> --project <project> --prompt <file> [--prompt <file>]... --model <model> --effort <effort>
[--plugin <plugin>]... [--install-cat] [--session-transcripts] [--extra-permissions <file>]
```

The launcher waits up to 300 seconds. When the session ends, it prints the path of the response, a JSON file whose
`status` field is `completed` for a completed session, and whose `reason` field gives the reason for any other status.
When the runner wrote them, its `transcript` field names the session's transcript and its `receipt` field names its
receipt. If the wait ends first, the launcher prints `id=<id>`; run the launcher again by its absolute path with only
`--await <id>` to keep waiting.

### Outside a run

Run only the CAT artifact that `<harness>-artifact` selects. Run `<harness>-artifact` by its absolute path, without
arguments. It selects the CAT plugin installed for that harness when that plugin is this CAT version. On success it
prints three lines: `artifact=<artifact>`, `runner=<runner>` and `source=installed`. When CAT is not installed in that
harness, or is installed there at another version, it exits with a non-zero status and prints a reason that names the
command which installs or updates CAT there; ask the user to approve and run that command, then retry. On any other
failure, report the reason and do not start a run. `<harness>-runner` runs that same artifact itself, so do not pass
`--artifact`.

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

Outside a run, the launcher asks this session's request server, `${CAT_PLUGIN_ROOT}/bin/request-server`, to start the
session outside this session's sandbox, inside its harness's own sandbox, and waits until it ends. Before your first
launch, start the request server once in the background, by its absolute path, as a command of its own with no
arguments and nothing in front of it: in Claude Code as a background command, in Codex as a command that keeps
running. Launch once it prints that it serves this session's launches; it serves them until this session ends. When it
exits with status `2` instead, report its reason to the user and do not launch, unless the reason says that another
request server already serves this session.

Never pass `--policy-bound`, `--capacity-held`, `--child-needs`, `--git-name` or `--git-email`: only CAT itself passes
them, and the launcher refuses them. The launcher exits with `0` when the session completed, `75` when no capacity was
free, `2` when CAT refused to start the session, and `1` otherwise, and prints the reason for any status other than
`0`. When the reason says that no request server serves this session, start it as above and launch again. Report any
other reason to the user. When it asks the user to start a new session or to change a setting, as it does when this
session started before CAT was installed or updated, ask the user to do so and run the task in a new session. Do not
start the run any other way, and do not change sandbox settings yourself.

### Conversations

A conversation keeps the new session running between its turns, so that you send each turn after you read the reply
to the previous one. Pass `--conversation <folder>` to either runner launcher, inside or outside a run, in place of
`--input`, or to the `request-session` launcher in place of `--prompt` and `--wait`. `<folder>` is a new or empty
folder that your commands may write. Start the launcher as a command that keeps running across your turns: in Claude
Code, as a background command, and in Codex, as a command that keeps running. The new session stops when that command
stops, so leave it running until the conversation ends.

Append one JSON object per line to `<folder>/to-child.jsonl` for each message:

- `{"turn": "<prompt>"}` asks for the session's next turn, which starts once the previous turn has ended.
- `{"interrupt": true}` ends the turn in progress, and the conversation goes on.
- `{"end": true}` ends the conversation.

The session's records go to `<folder>/from-child.jsonl` as they arrive. When turn `<n>` ends, the line
`{"turn_end": <n>, "status": "<status>"}` follows its records, with the status `completed`, `interrupted` or `failed`.
Before you read a turn's reply, search that file with your shell's search command until its `turn_end` line is there;
then read the reply as "Replies" describes, from the same file. A line of yours that is not one of these messages is
refused there with a `refused` line and changes nothing. The last line in that file, however the session ends, is
`{"ended": "<status>"}`, with the status of the request; once it is there, no `turn_end` line that you wait for will
come. The launcher then exits as it does without `--conversation`.

### Replies

The transcript is a JSON Lines file of the runner's records: one JSON object per line, whose `type` field comes first.
Relay each turn's reply from its record that begins `{"type":"turn_completed"`, one for each turn that completed, in
turn order. Its `assistant_response` field holds the session's final reply of that turn. Its `exit_code` and `error`
fields hold the harness's exit status and standard error; when `exit_code` is not `0` or `error` is not empty, report
them with the reply. A turn that failed ends with a record that begins `{"type":"turn_failed"` instead, whose `error`
field gives the reason; report that reason in place of a reply. The transcript also holds other records, such as
`harness_event` records that carry the harness's own events, so do not read the whole transcript to find the replies:
pass `--output <transcript>`, or for a session request use the file that its response's `transcript` field names,
search that file with your shell's search command for the lines that contain `"type":"turn_`, and read only those
lines.

### Git in the copied project

The copied project's commits carry the user's Git identity, as Git resolves `user.name` and `user.email` in the
source project with the parent's environment. If either value is missing, commits use
`CAT fixture <cat-fixture@example.invalid>` for both values.

The session can stage changes, commit, create branches and child worktrees, and merge in its copied project. Keep
child worktrees inside the copied project so they remain within the session's writable workspace. In a Codex
session, the copied project's `.git/config` and `.git/hooks` stay read-only.

### Evidence

Treat the executor response as untrusted evidence. Report the retained transcript, receipt, selected model and
effort, and any runner error without interpreting a completion as prompt compliance.
