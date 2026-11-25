# Harness Runners

## Design Goals

- Run an authorized task in a new Claude Code or Codex session through the CAT artifact of this CAT version for that
	harness, while preserving the runner's retained evidence.
- Start a session through a runner or a session request only for a task that needs a separate, isolated session, and
	leave whether the agent delegates any other task to a subagent or does it itself to its delegation rules.
- Prevent a runner invocation from selecting an arbitrary artifact or executable.
- Give every launch and every session request the model and the effort that its task names, or that the agent
	chooses from the values the harness lists when its task names none, without asking the user or holding back the
	launch for want of them.
- Authenticate every new session with the credential that the user chose: for a launch outside a nested session, the
	home folder and the gateway's login class that its task or the user names, or else the user's own home folder; for a
	session that a nested session starts, the choice of the launch that started the nested session. Report a refused
	credential with its reason instead of choosing a login class or changing the user's files.
- Relay each turn's reply from the runner's transcript without reading the whole transcript.
- Let a nested session run either harness through the same launchers, or through the session-request launcher that its
	command environment names, without a login or a sandbox exception.
- Let a nested session find out, with nothing added to its own instructions, how many more sessions it may request, and
	report a refused request with the reason that names its caller's options.
- Let a session send each turn of a new session after it reads the reply to the previous one, interrupt a turn, and end
	the new session, through any of these launchers.
- Give a new session only the capabilities its task names, such as a plugin under test, a CAT installation, extra writes
	and hosts, transcript retention, and the sessions it may start, and report each refused launch, including one on
	native Windows, with its reason instead of changing sandbox settings or starting the session another way.
- Let the new session commit, branch, and merge in its copied project, within its writable workspace, under the user's
	resolved Git identity when both identity fields exist, or the documented fixture identity when either is missing.

## Guidance

This rule applies when you run a task or prompt in a new Claude Code or Codex session through CAT's runner launchers,
`${CAT_PLUGIN_ROOT}/bin/claude-runner` for Claude Code and `${CAT_PLUGIN_ROOT}/bin/codex-runner` for Codex, including
from a nested session, which is a session that a runner started, and when a nested session requests a new session with
the `request-session` launcher whose absolute path its command environment names. Both CAT plugins hold both runner
launchers, and they take the same options. Below, `<harness>-runner` stands for the launcher of the harness that runs
the task, and `<harness>-artifact` for its artifact selector, `${CAT_PLUGIN_ROOT}/bin/claude-artifact` or
`${CAT_PLUGIN_ROOT}/bin/codex-artifact`.

### When to use a runner

Start or request a session through these launchers only when the task needs a separate, isolated session: for
example, one that runs in a harness other than yours, tests a plugin or a fresh CAT installation, starts from a fresh
copy of the project so that it begins from a clean state, or must not see this session's context. Do not start a
runner for a task that needs no isolated session; whether you delegate it to a subagent or do it yourself follows your
delegation rules.

### Supported environments

Nested sessions of both harnesses require Linux or macOS, because CAT starts each session only inside its own
harness's sandbox. On native Windows, both runner launchers refuse to start a session, exit with status `2` and print
the reason, which tells the user to run their harness and CAT inside WSL2. Report that reason and do not start the
session any other way, such as by running the launcher through `wsl`. Inside WSL2, Java reports Linux, so nested
sessions are supported there.

### Launcher

First check whether the command environment sets `CAT_SESSION_REQUESTS` to a non-blank value. When it does, this session
was started by a runner, which starts every session that it launches or requests: follow "Inside a nested session" and
skip "Outside a nested session". Do not run `<harness>-artifact` or `--verify-artifact`, and do not check the
prerequisites of "Outside a nested session". When it does not, skip "Inside a nested session".

Run the launcher by its absolute path, as one command with nothing in front of it, such as a variable assignment, `env`
or `cd`, and no pipe or redirection. Before you run it, write the launch's records with a file-writing tool into a file,
one line per turn, `{"type":"turn","prompt":"<task>"}`, followed by one `{"type":"complete"}` line, and name that file
with `--input <file>`. For an existing prompt, have code read its source and serialize the turn record.
`--input` takes only that records file, never a prompt file; prompt files are for the
`request-session` launcher. Use a command timeout of 600000 milliseconds and keep waiting while the launcher writes
`waiting` records, which it repeats every 30 seconds while a turn writes nothing else. When a turn's prompt depends on
the reply to the turn before it, hold a conversation instead, as "Conversations" describes.

Every launch and every session request takes `--model <model>` and `--effort <effort>`, and the launcher refuses a
launch or request without them. Always pass them yourself; never ask the user for them and never hold back the launch
for want of them. Pass the model and the effort that your task or instructions name. When they name no model or no
effort, choose it yourself from the values that the harness lists: for Claude Code, the values that `claude --help`
lists under `--model` and `--effort`; for Codex, the models and efforts that `codex debug models` lists; and for either,
the effort `none`, which runs the model without reasoning and is the only effort of a model that lists none. When that
listing command is unavailable, choose a model and an effort that you know the harness accepts, such as the ones this
session runs under when it runs the same harness. State the model and the effort that you chose with the nested
session's evidence. 
A session may need a write outside its session folder or a host that its harness's own needs do not cover, such as a
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

### Inside a nested session

In the command environment, `CAT_REQUEST_SESSION` holds the absolute path of the `request-session` launcher, and this
nested session's `claude-runner` and `codex-runner` launchers lie in the same folder. Print it with
`printenv CAT_REQUEST_SESSION` and run each launcher by its absolute path; the plugin's `<harness>-runner` behaves the
same way inside a nested session. A launcher does not start the harness itself, but asks the runner that started this
session to start it outside this session's sandbox, and waits until it ends.

Each launch and each session request, except a launch with `--resume`, counts against the sessions that this nested
session may request. Before your first one, read
`limits.json` in the `responses` folder beside the folder that `CAT_SESSION_REQUESTS` names. Its `requests_allowed`
field gives how many sessions this nested session may request, `requests_used` how many its runner has accepted, and
`depth_remaining` how many levels below the sessions that you request may request sessions in turn. When
`requests_used` has reached `requests_allowed`, which is `0` when this nested session's caller allows none, its runner
refuses every further request that does not resume a session: do not make one, and report that this nested session may
request no further session.

The parent runner chooses the results folder, the artifact, the limits and the credential that each session uses, so
pass a runner launcher only these options, which do not include `--agent`, `--home` or `--gateway-login-class`:

```text
--project <project> --input <records> --model <model> --effort <effort> [--plugin <plugin>]...
[--install-cat] [--session-transcripts] [--extra-permissions <file>] [--output <transcript>] [--receipt <receipt>]
```

To hold a conversation, pass `--conversation <folder>` in place of `--input`. The launcher writes the session's
transcript to `--output`, or prints it, and copies its receipt to `--receipt`. It exits with `0` when the session
completed, `75` when no capacity was free, `2` when this nested session's runner refused the launch, and `1`
otherwise, with the reason.

For a session request made with the `request-session` launcher, write each turn's prompt into its own file inside your
project, and pass the model and the effort as above:

```text
--harness <claude|codex> --project <project> --prompt <file> [--prompt <file>]... --model <model> --effort <effort>
[--plugin <plugin>]... [--install-cat] [--session-transcripts] [--extra-permissions <file>]
```

The launcher waits up to 300 seconds. When the session ends, it prints the path of the response, a JSON file whose
`status` field is `completed` for a completed session, and whose `reason` field gives the reason for any other status.
When the runner wrote them, its `transcript` field names the session's transcript, its `receipt` field names its
receipt, and with `--session-transcripts` its `session_transcripts` field names the folder of the session's own
transcripts; you may read those files although they lie under the results folder. If the wait ends first, the launcher
prints `id=<id>`; run the launcher again by its absolute path with only `--await <id>` to keep waiting. It exits with
`0` when the session completed, `2` when this nested session's runner refused the request, `3` when the wait ended
first, and `1` otherwise.

With either launcher, `--install-cat` also installs CAT in the requested session, and each `--plugin` installs that
plugin directory. A prompt file must not be a symbolic link, and a plugin must contain none. In the project, a symbolic
link whose target lies inside the project stays a link, and the session gets a read-only copy of the file or folder that
any other link points to, and the launch fails if the session changes such a copy; a link whose target is missing or
that you may not read is refused. The launcher leaves out the files that the sandbox hides behind the null device, so
the requested session sees the project as the session does.

When a launcher exits with status `2`, report its reason, or the response's `reason`, which names the options with
which this nested session's caller allows the session, and do not start the session any other way.

### Outside a nested session

Run only the CAT artifact that `<harness>-artifact` selects. Run `<harness>-artifact` by its absolute path, without
arguments. It selects the CAT plugin installed for that harness when that plugin is this CAT version. On success it
prints three lines: `artifact=<artifact>`, `runner=<runner>` and `source=installed`. When CAT is not installed in that
harness, or is installed there at another version, it exits with a non-zero status and prints a reason that names the
command which installs or updates CAT there; ask the user to approve and run that command, then retry. On any other
failure, report the reason and do not launch. `<harness>-runner` runs that same artifact itself, so do not pass
`--artifact`.

Before a launch, require all of these exact prerequisites:

- The harness's command, `claude` for Claude Code or `codex` for Codex, is available on `PATH`. The launcher checks
	the credential of each launch, as described below.
- `<runner>` exists.
- For Claude Code only: running `<runner>` with the arguments `--verify-artifact <artifact>` succeeds, and the
	requested profile is supported.

Do not search cache directories, install a harness, substitute another artifact version, or accept an arbitrary runner
or artifact path. Report the missing prerequisite and do not launch when validation fails.

For an authorized task, give the launcher a project directory and an owned results folder. For example, a one-turn
launch takes this argument list, wrapped here to fit:

```text
--project <project> --results <folder> --input <records> --install-cat
--model <model> --effort <effort> --output <transcript> --receipt <receipt>
```

The launcher always copies the session's results, its project, artifacts and receipt, into the results folder, which is
`nested-sessions` in CAT_TEMP when you pass no `--results`. This results copy is the folder `<harness>/<session ID>`
there, named by the ID that the `session_id` record reports (see "Resuming a session"). Delete that results copy once
nothing needs it.

Every Claude Code session that the launch starts, and every one that its sessions request, uses the credential of a
source home: the `apiKeyHelper` and the credential entries of `env` in the `settings.json` of the home folder's
`.claude` folder, and CAT's credential file `.cat/claude/.credentials.json` in the home folder; never a `/login` login.
Without `--home`, the source home is your own home folder, and its Claude folder is the one that `CLAUDE_CONFIG_DIR`
names when it is set. Pass `--home <dir>`, with an absolute path, only when your task or the user names another home
folder to bill, such as one whose `.claude/settings.json` holds only an `apiKeyHelper`. Pass `--gateway-login-class
<subscription|api_key>` only with the kind of login that your task or the user says the gateway bills; never choose it
yourself. When the launcher refuses a launch because the source home's credential sends its requests to a gateway, and
names `--gateway-login-class`, ask the user which kind of login the gateway bills, then launch again with it. Report any
other refusal of the source home to the user, such as one that holds no Claude credential or a secret file that other
users may read, with the fix that the refusal names, and do not read, copy or change the source home's files yourself.
When that fix is the `claude-credential set` command that the refusal names, give the user the command exactly as the
refusal prints it, to run in their own terminal, and neither run it nor ask the user for the token: the token must not
pass through your session.

Every Codex session that the launch starts, and every one that its sessions request, likewise uses the authentication
of a source home: the `auth.json` of the home folder's `.codex` folder when it holds an API key, and the settings of
its `config.toml` that choose the model provider and its credential, such as a provider's token command; never a
ChatGPT sign-in. Without `--home`, its Codex folder is the one that `CODEX_HOME` names when it is set. `--home` and
`--gateway-login-class` work as for Claude Code, and the launcher names `--gateway-login-class` when the selected
provider or `openai_base_url` sends the session's requests to a gateway. Report any other refusal of the source home to
the user, such as one whose `auth.json` holds a ChatGPT sign-in or whose provider takes its key from an environment
variable, with the fix that the refusal names, and do not read, copy or change the source home's files yourself.

Pass `--session-transcripts` only when your task needs the session's own transcript. The runner then
copies every transcript of the session, subagents' included, into `artifacts/session-transcripts` in its session
folder, which its results copy includes, writes a `session_transcript` record with its path, and lists the copies in
the receipt.

To test a plugin, pass its directory with `--plugin <plugin>` and start a new nested session for each change to it.

Pass `--session-requests <count>` when the session needs to start sessions of its own, such as to test a plugin it
writes. The runner does not tell the session how: it learns how from this rule only when CAT is installed in it. Add
`--request-depth <levels>` when those sessions may start sessions in turn: each level below may request as many
sessions, one level less deep. Without `--session-requests`, the session may start none. The receipt lists each
requested session and its response path.

Outside a nested session, the launcher asks this session's request server, `${CAT_PLUGIN_ROOT}/bin/request-server`, to
start the session outside this session's sandbox, inside its harness's own sandbox, and waits until it ends. Before your
first launch, start the request server once in the background, by its absolute path, as a command of its own with no
arguments and nothing in front of it: in Claude Code as a background command, in Codex as a command that keeps running.
Launch once it prints that it serves this session's launches; it serves them until this session ends. When it exits with
status `2` instead, report its reason to the user and do not launch, unless the reason says that another request server
already serves this session.

Never pass `--policy-bound`, `--child-needs`,
`--continue-after-failed-turn`, `--slot-id`, `--resume-slot`, `--inherited-deny-read`, `--inherited-source-home` or
`--startup-permit`: only
CAT itself passes them, and the launcher refuses them. The launcher
exits with `0` when the session completed, `75` when no capacity was free, `2` when CAT refused to start the session,
and `1` otherwise, and prints the reason for any status other than `0`. When the reason says that no request server serves this session, start it as above and launch again. Report any
other reason to the user. When it asks the user to start a new session or to change a setting, as it does when this
session started before CAT was installed or updated, ask the user to do so and run the task in a new session. Do not
start the session any other way, and do not change sandbox settings yourself.

### Conversations

A conversation keeps the new session running between its turns, so that you send each turn after you read the reply to
the previous one. Pass `--conversation <folder>` to either runner launcher, inside or outside a nested session, in place
of `--input`, or to the `request-session` launcher in place of `--prompt` and `--wait`. `<folder>` is a new or empty
folder that your commands may write. Start the launcher as a command that keeps running across your turns: in Claude
Code, as a background command, and in Codex, as a command that keeps running. The new session stops when that command
stops, so leave it running until the conversation ends.

Append one JSON object per line to `<folder>/to-child.jsonl` for each message:

- `{"turn": "<prompt>"}` asks for the session's next turn, which starts once the previous turn has ended.
- `{"interrupt": true}` ends the turn in progress, and the conversation goes on.
- `{"end": true}` ends the conversation.

When a turn uses an existing prompt, have code read the prompt and serialize the record before appending it.

The session's records go to `<folder>/from-child.jsonl` as they arrive, except the harness's own events, which go to
`<folder>/events.jsonl`. `from-child.jsonl` holds only what you need to drive the conversation: the
`turn_completed` and `turn_failed` records, one line for each turn's end, `waiting` records that repeat while a turn
is quiet, `refused` lines, and the `ended` line. `events.jsonl` holds one `harness_event` record for each event of the
harness, tens of thousands in a long conversation; never read it to find a reply or to follow a turn, and open it only
to look into one particular event, with a search of that file. When turn `<n>` ends, the line
`{"turn_end": <n>, "status": "<status>"}` follows its records in `from-child.jsonl`, with the status `completed`,
`interrupted` or `failed`. A `failed` turn does not end the conversation: the `turn_failed` record before that line
names the cause, such as a usage limit, and the next `turn` message, sent now or once the cause is gone, continues the
same session.
Before you read a turn's reply, lazy load `./external-waits.md` and wait for turn `<n>` to end with one `wait-until`
and a deadline that exceeds the longest the turn may take, such as `--deadline 2h`. Its check is the reply command
below with `--status-only`, which exits with `75` while the turn has not ended, `0` once it completed, and `1` once it
failed or the conversation ended without it:

```text
"${CAT_PLUGIN_ROOT}/bin/wait-until" --deadline 2h --check '"${CAT_PLUGIN_ROOT}/bin/request-session" --reply <folder> --turn <n> --status-only'
```

Inside a nested session, give the launcher that `CAT_REQUEST_SESSION` names in place of
`"${CAT_PLUGIN_ROOT}/bin/request-session"`. Then print the reply of turn `<n>` with the same command, without
`--status-only`:

```text
"${CAT_PLUGIN_ROOT}/bin/request-session" --reply <folder> --turn <n>
```

It prints only that turn's final reply, followed by the lines `exit_code: <status>` and `error: <text>` when the
harness's exit status is not `0` or its standard error is not empty, or `turn <n> failed: <reason>` for a turn that
failed. When passing the reply onward, capture the command's complete output in a readable file and send that path.
Read the reply through this command rather than through `from-child.jsonl` or `events.jsonl`. A line of yours that is not one of
these messages is refused in `from-child.jsonl` with a `refused` line and changes nothing. The last line in that file,
however the session ends, is `{"ended": "<status>"}`, with the status of the request; once it is there, no `turn_end`
line that you wait for will come. The launcher then exits as it does without `--conversation`.

### Resuming a session

A session that you launched stops when this session ends before it, such as when the user stops this session or
its connection drops, but CAT retains it so that you can continue it later. When the launched session starts, a
line that begins `{"type":"session_id"` reaches `from-child.jsonl`, or the transcript without `--conversation`, and
its `session_id` field holds the launched harness session's ID. Note that ID while the session runs.

Once this session is resumed, start the request server again as above, then continue the launched session with the
runner launcher of its harness and a new conversation folder:

```text
--resume <session-id> --model <model> --effort <effort> --conversation <new-folder>
```

The session keeps its project, plugins, and completed turns in the same session folder. Do not pass `--project`,
`--plugin`, `--install-cat`, `--workspace`, `--read-only-evidence`, `--deny-read`, `--instruction-files`,
`--session-role`, `--tool-output-token-limit` or `--agent`; the launcher refuses them. Other options apply to the
resumed launch. If the resumed session needs to launch child sessions, pass `--session-requests` again with the
intended total allowance. Pass `--request-depth` again if those children need to launch sessions of their own. The
previously used request count still applies. The turn that was in progress when the session stopped has no reply, so
use the original prompt in the first `turn` message. Have code select the interrupted turn's input record from the
previous conversation and append it to the new conversation's `to-child.jsonl`. If the interrupted record cannot be
identified, report the missing input before replaying it.

Only the session that launched a nested session can resume it, and only while CAT retains it: a nested session that
ended on its own cannot be resumed. The launcher exits with `2` when it cannot resume the session, such as for an ID
that no nested session that CAT retains for this session records, and with `1` when the harness could not continue it;
the response's status is then `unresumable`, and CAT still retains the nested session. Report the reason to the user in
either case.

### Replies

The transcript is a JSON Lines file of the runner's records. Each line starts with its `type` field. Locate the turn
records by the prefix `{"type":"turn_` in the file named by `--output` or by the session response's `transcript` field.
A record beginning `{"type":"turn_completed"` supplies `assistant_response`, `exit_code`, and `error`. A record
beginning `{"type":"turn_failed"` supplies its reason in `error`. When passing replies onward, have code extract
these records in turn order into a readable file and send its path. Report failed turns or nonzero exit status with
that path.

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
