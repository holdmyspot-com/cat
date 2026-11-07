# Remote Runner Session

## Design Goals

- Before the changed commands of a workflow run on a remote runner, the agent holds an authenticated Upterm session to
	that worker and has verified that a command sent through it takes effect in the job's workspace.
- The project's workflow integration supplies every piece that such a session needs on its runner provider; the agent
	finds a missing piece by inspecting that integration and repairs it before the remote test.
- A failure is diagnosed and retried on the same worker and workspace, keeping valid completed outputs and the original
	failure's evidence, and every file sent through the shell matches its original before it is used.
- A session left idle ends by itself: one that nobody joins within the idle limit, 10 minutes unless the task states
	another or the integration cannot be set below a longer one, and a joined one after that long at its prompt, without
	ending a command that is still running. The worker's cleanup then runs.
- While any work still needs the worker, the agent does not lose it to a join deadline that nobody meets, an idle limit
	reached while the agent analyses or hands the worker over, a cancelled job, or a killed client: it knows each limit
	that can end the worker as an absolute time and claims the worker as soon as its connection details are published.
- Every remote command that the agent waits for runs the source under test and is still needed, and one that stops
	making progress is found within minutes, its diagnostics are kept, and its processes are ended from inside the
	session.
- The agent releases the worker itself as soon as nothing needs it, after copying the outputs that later work needs,
	rather than leaving it to reach its idle limit or the job's timeout. It then observes the worker's cleanup, or
	reports it as unobserved when a deliberate cancellation skipped it, and removes the client-side keys and files it
	created only after their last user has finished.

## Guidance

Use this skill when a task tests changed workflow commands on a remote runner, on any runner provider or operating
system, and when the user asks to set up, connect to, or troubleshoot an Upterm session on a runner. Work within the
authority the task already has to run remote jobs, change workflows, and publish results. Open the session before the
changed commands run and keep it open through testing and retries, even when you expect them to pass. A provider that
has already destroyed a worker cannot give it back: starting Upterm afterwards does not recover it, so arrange to keep
the worker before teardown.

### 1. Check the project's integration

Before you dispatch the remote test, inspect the project's own workflow files and the scripts and actions they run on
the worker, and find where each of these is supplied. Read their current values from those files; do not assume them.

1. **The worker outlives the changed commands.** A step at the end of the job, or a hook that the provider runs before
	teardown, waits until the remote shell exits. The wait runs whether the changed commands pass or fail.
2. **The changed commands start only once you are connected.** Either the workflow waits for a readiness signal that
	you send through the session, such as a file that you create in a directory that the job owns, or you run the
	changed commands through the connected shell yourself. A detached session lets the workflow's later steps continue
	at once, so it needs one of the two.
3. **You can read the connection details while the worker runs.** The integration publishes the SSH command that
	Upterm prints through a channel that the provider lets a client read during the job, not one that it releases only
	after the job ends. Upterm keeps its session registry in the hosting user's private directories, so a separate
	process that queries it with default settings finds no session; use the published command instead.
4. **Only your key is accepted.** The workflow takes a public key as an input, checks that it is a valid key, and gives
	it to Upterm as the only authorized key. The private key never leaves your client.
5. **The relay is pinned.** The integration holds the relay's host key or certificate authority, taken from an
	authoritative source, as a known-hosts file that your client checks against.
6. **Cleanup waits for the session.** Removing the workspace and the files that hold the key runs after the remote
	wait, even on failure. A cleanup step that the workflow places after a detached session runs too early; register
	cleanup with the provider's lifecycle so that it runs after the wait.
7. **An idle session ends.** The idle limit is 10 minutes unless the task states another, or the integration's own
	session facility cannot be set below a longer one, in which case use its shortest. A session that nobody joins
	within the limit ends, for example through Upterm's `--join-timeout` option. Once a guest has joined, Upterm never
	ends the session by itself, even after the guest disconnects without exiting the shell, so the hosted command must
	end itself after the limit passes without input from a guest, without ending a command that is still running. For
	example, host a bash, zsh or ksh shell that sets its own `TMOUT` variable to the limit in seconds and makes it
	read-only, for example in a startup file: such a shell exits after waiting that long at its prompt for a command,
	but not while a command runs. Do not export `TMOUT`, because bash scripts that the commands start would then stop
	waiting for input after that long. When the session ends this way, the remote wait ends too, and cleanup runs after
	it.
8. **Upterm's version and the job's timeout are fixed.** The integration installs a pinned Upterm version that
	supports the idle limit's options, and states the job's timeout. When the integration takes the idle limit as an
	input when you dispatch the run, pass the limit from item 7 rather than its default.

Reuse what the provider and the integration already supply, and add only what is missing. An integration that works on
one provider does not show that its lifecycle holds on another; check each provider that the test uses. If a piece is
missing or fails, report it and repair the integration before you run the remote test; do not run the changed commands
unattended instead.

### 2. Supply access

1. Use a key pair that the task already owns, or create an Ed25519 pair in a temporary directory that you own by
	running `ssh-keygen` with the arguments `-t`, `ed25519`, `-f` and `<key file>` and an empty passphrase. Keep the
	private key on your client, pass only the public key to the workflow, and decide when you remove the pair: after the
	session and every client that uses it have ended.
2. Keep the key files and the pinned known-hosts file available until the session ends.

### 3. Track the worker's deadlines

Before you dispatch the run, list every limit that can end the worker on this provider: the join deadline, by which
someone must join the session; the idle limit at the shell's prompt; the job's timeout; and any maximum lifetime that
the provider sets for a job or worker. A provider that has no such limit adds none. Write each limit down as an
absolute time in a file that you own for the run once its clock starts, such as when the integration starts the
session or the job starts, as its log shows; when you cannot see when a clock started, count from when you dispatched
the run, which is no later. Compute the times again whenever the session's state changes, such as when you connect,
when the shell returns to its prompt, or when you reconnect.

Do not dispatch a run that nothing will claim before its join deadline, such as while other work or a coming context
reset will occupy you. When another agent or a later session will use the worker, put the run's identity and these
times in the handoff; the receiver claims the worker before it does anything else.

### 4. Connect and verify

Claim the worker as soon as its connection details are published: connect, verify a command as this section describes,
and start the keepalive that the end of this section describes before you do any other work.

Record the provider's run and worker identities, and keep each run's files in its own directory. Retrieve the published
SSH command through the integration's handoff while the worker runs. Read the destination from it by splitting its
arguments with a platform facility, such as Python's `shlex.split`; never run its text as shell code. Connect with
`ssh -tt -i <private key> -o IdentitiesOnly=yes -o UserKnownHostsFile=<pinned known-hosts file> -o
StrictHostKeyChecking=yes <destination>`.

If your execution tool buffers a command's output or closes its standard input, drive SSH from one foreground process
that owns a pseudo-terminal, for example with Python's `pty.openpty()`, `subprocess.Popen()` and `select.select()`.
Keep that process alive through the connection, every remote command, the remote shell's exit, and waiting for the SSH
process to end. A detached SSH process does not hold the session. Save the session's transcript in a file that you own,
also when it fails.

Wait for the remote shell's prompt before you send a command: input sent before the prompt appears has been lost in
Upterm sessions. The prompt may carry terminal control sequences. On Windows Git Bash, for example, it arrived as
`#\x1b[22m ` followed by an OSC sequence that sets the terminal title, so removing color sequences alone left the
client waiting for a prompt that had already appeared. Handle the sequences that actually arrive, or set `PS1` and
clear `PROMPT_COMMAND` through the open connection. A prompt-detection timeout is not an authentication failure.

Before you start the changed commands, send a short command whose effect you can check in the job's workspace, such as
listing the directory that the job owns, and verify its output. A login banner or a published connection command does
not show that you can run commands.

If only your client fails while the worker is still available, reconnect to the same session and verify the
workspace's state before you continue.

Keep the session from idling out while you still need the worker. A command that runs in the foreground keeps it open,
but time that the shell waits at its prompt counts toward the idle limit, including a long analysis between commands and
time with no client connected, and reaching the limit ends the worker even while your client stays connected. Whenever
the shell would otherwise wait at its prompt, keep a keepalive command running in its foreground, such as a loop that
waits up to a minute for a key, prints the time when none arrives, and prints a marker that only it prints once it reads
a key. To send your next command, send that key and wait for the marker. Do not stop the keepalive with Ctrl-C: an
interrupt can end it before it prints its marker, so your client cannot tell when the shell is ready for input. Never
leave the shell at its prompt for longer than the idle limit less the time you need to reconnect.

### 5. Send files through the shell

Ordinary short commands need no special handling. To send a file through the interactive shell, encode its bytes as
text, such as base64, and send it in lines whose length, including the shell syntax around the payload, stays below the
remote terminal's input limit. On native macOS, 512-byte base64 chunks arrived intact, while chunks of about 1,000
bytes plus their shell syntax made the terminal ring its bell and drop input. After each chunk, wait for an
acknowledgement that only that chunk's command prints before sending the next; a successful write to your local
terminal shows only that you sent it. When a write accepts only part of a chunk, send the rest. After decoding the file
in a remote directory that you own, compare its size and SHA-256 hash with the original before you run or use it.

If a transfer loses input, keep its transcript. When no test command is running, press Ctrl-C to clear the unfinished
input, check that a short command works on the same connection, delete only the incomplete file, and send it again in
shorter acknowledged lines. Continue testing once the decoded file matches the original, keeping completed build
outputs and the worker.

### 6. Keep remote work needed and progressing

Before you wait for any remote command, including a workflow step that is already running, confirm that its result is
still needed: it runs the source under test, not a commit that a later fix has replaced, and it answers a question that
no faster check through the live session can. If it runs superseded source or its result no longer matters, end it now
from inside the session instead of waiting for it.

So that the shell stays free to check on a long command that you start, run it in the shell's background with its
output written to a file in a directory that you own, note its process ID, and keep the keepalive in the foreground
between checks. Within a few minutes of a long remote command's start, check that it is making progress, then check
again at intervals much shorter than its expected duration. Count as progress what you can observe through the
session: new processes in its process tree, growth of its output or logs, and CPU time that its processes consume. A
process that is merely still running is not progress. When none of these advances across checks, treat the command as
stalled: first save its diagnostics, such as its process tree, open files, thread or stack dumps and the end of its
log, then end its whole process tree from inside the remote shell and check that none of its descendants or process
groups remain.

Never cancel the provider's job, or kill your SSH client, to stop remote work: either can end the worker and the
workspace that it keeps. Let a command that is still making progress and still needed finish.

### 7. Diagnose and retry

Inspect the command that failed and its environment on the worker, find the cause, make a focused change, and retry the
affected work in the same workspace, keeping its valid completed outputs. Do not end the session to start a replacement
worker. Verify each retry by an effect you can observe on the worker and by the command's result. Keep the original
failure and its evidence separate from a successful retry. When you are debugging a failure, try each fix here until
every check of the failed job passes before you push anything. Once the retries pass, run the clean verification that
the task requires.

If the provider has already destroyed the worker, keep its logs and any valid partial outputs that it published, such
as caches, and use the smallest replacement run that can reproduce the failure.

### 8. Shut down and verify

Release the worker yourself as soon as no remaining command, retry or required verification needs it; do not leave it
to reach its idle limit or the job's timeout. First record the session's results and copy every output that later work
needs from the worker, such as by printing it encoded through the session and checking its SHA-256 hash. Then end the
keepalive, run `exit` in the remote shell, wait for the SSH process to end, and check that the session has ended.
Disconnecting your client without running `exit` leaves the session waiting at its prompt and the worker held.

After the shell has exited, if the job would still run steps other than its cleanup and nobody needs their results,
you may cancel the job through the provider. Record that as a deliberate shutdown, not a product or provider failure,
and report its cleanup as unobserved: cancelling can skip the provider's cleanup steps. Otherwise wait for the job to
end, and observe its terminal result and its cleanup. Never cancel while you still need the worker or the remaining
steps' results.

Remove the keys and temporary files that you own only after their last user has finished. If a session expires before
anyone connects, or ends because it was left idle, report that outcome with the original logs, not a passing test. The
worker's cleanup has run by then, so a further test needs a new run.

### Testing a change to the remote-access integration

When the task changes the integration itself, test it with the cheapest run that exercises it, without rebuilding an
unchanged product: connect first, create some state, fail on purpose, verify that state over the still-open connection,
append a retry's result, exit, and verify the retained result after the remote wait. Keep the deliberate failure visible
in the job's result. Also verify that a key other than yours is refused, and that, with an idle limit of a minute or
two, a session that nobody joins ends, a joined session ends after that long at its prompt, a command that runs longer
than the limit is not ended, and cleanup runs after each. Check each provider and platform whose lifecycle the change
affects. Upterm refuses an unauthorized key with `session ... refused your key`; that is an authentication refusal even
when SSH does not print `Permission denied (publickey)`.
