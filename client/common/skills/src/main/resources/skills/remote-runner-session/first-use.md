# Remote Runner Session

## Design Goals

- Before the changed commands of a workflow run on a remote runner, the agent holds an authenticated Upterm session to
	that worker and has verified that a command sent through it takes effect in the job's workspace.
- The project's workflow integration supplies every piece that such a session needs on its runner provider; the agent
	finds a missing piece by inspecting that integration and repairs it before the remote test.
- A failure is diagnosed and retried on the same worker and workspace, keeping valid completed outputs and the original
	failure's evidence, and every file sent through the shell matches its original before it is used.
- The session ends deliberately after the required verification, the agent observes the worker's cleanup, and it
	removes the client-side keys and files it created only after their last user has finished.

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
7. **Upterm's version and its limits are fixed.** The integration installs a pinned Upterm version, and states how
	long a session waits for its first connection and the job's timeout. Know both before you start.

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

### 3. Connect and verify

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

### 4. Send files through the shell

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

### 5. Diagnose and retry

Inspect the command that failed and its environment on the worker, find the cause, make a focused change, and retry the
affected work in the same workspace, keeping its valid completed outputs. Do not end the session to start a replacement
worker. Verify each retry by an effect you can observe on the worker and by the command's result. Keep the original
failure and its evidence separate from a successful retry. Once the retries pass, run the clean verification that the
task requires.

If the provider has already destroyed the worker, keep its logs and any valid partial outputs that it published, such
as caches, and use the smallest replacement run that can reproduce the failure.

### 6. End and verify

When you are done, exit the remote shell, wait for the SSH process to end, and observe the provider's terminal job
result and its cleanup. Do not cancel a running build merely to close the session. Remove the keys and temporary files
that you own only after their last user has finished. If a session expires before anyone connects, report that outcome
with the original logs, not a passing test.

### Testing a change to the remote-access integration

When the task changes the integration itself, test it with the cheapest run that exercises it, without rebuilding an
unchanged product: connect first, create some state, fail on purpose, verify that state over the still-open connection,
append a retry's result, exit, and verify the retained result after the remote wait. Keep the deliberate failure visible
in the job's result. Also verify that a key other than yours is refused, and check each provider and platform whose
lifecycle the change affects. Upterm refuses an unauthorized key with `session ... refused your key`; that is an
authentication refusal even when SSH does not print `Permission denied (publickey)`.
