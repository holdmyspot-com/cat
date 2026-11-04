# Install CAT

CAT v1.0 provides distributions for Claude Code and Codex on Linux x64, Linux ARM64, macOS x64, macOS ARM64, and
Windows x64. Windows ARM64 is not supported. You install CAT once with one command that you run yourself. Afterwards
your agent installs, updates and uninstalls CAT through the installed trusted updater.

## Install CAT

Run the command yourself in a terminal, or at a Claude Code prompt prefixed with `! `. The command installs, in one
step:

- the shared Java runtime, one per machine, which the updater and every CAT plugin use;
- the trusted updater and its policy, which records your harness home and the harness program that setup found;
- the CAT plugin for the harness you chose, registered with the harness through its own command-line program;
- the permission entries that let your agent run the updater's `install`, `update`, `status`, `rollback` and
	`uninstall` operations, and never `setup`. The harness still asks you to approve `rollback`, because it can install
	an older release.

You do not copy any permission or sandbox settings. Then start or restart the harness. The release's version is the one
in the command's address; there is no version argument.

### Linux and macOS

Choose your harness explicitly with `--harness`:

```bash
curl -fsSL https://github.com/holdmyspot-com/cat/releases/download/v1.0/cat.sh | sh -s -- --harness claude
curl -fsSL https://github.com/holdmyspot-com/cat/releases/download/v1.0/cat.sh | sh -s -- --harness codex
```

For a custom harness home, supply the appropriate variable to the command:

```bash
curl -fsSL https://github.com/holdmyspot-com/cat/releases/download/v1.0/cat.sh | CLAUDE_CONFIG_DIR=/path/to/claude-home sh -s -- --harness claude
curl -fsSL https://github.com/holdmyspot-com/cat/releases/download/v1.0/cat.sh | CODEX_HOME=/path/to/codex-home sh -s -- --harness codex
```

### Windows

In PowerShell, run the per-release entry script with `-Harness`:

```powershell
& ([scriptblock]::Create((irm https://github.com/holdmyspot-com/cat/releases/download/v1.0/cat.ps1))) -Harness codex
& ([scriptblock]::Create((irm https://github.com/holdmyspot-com/cat/releases/download/v1.0/cat.ps1))) -Harness claude
```

For a custom home, set `$env:CODEX_HOME = 'C:\path\to\codex-home'` or `$env:CLAUDE_CONFIG_DIR` before running it. In
Git Bash, the POSIX command above also works.

### Installing a second harness, repairing, and upgrading the updater

Run the command again with the other `--harness` to add a second harness. The commands are repeatable: running the
same command again repairs an installation that stopped part-way, replaces the Java runtime and the updater with the
release's, and installs the release's plugin again. Setup keeps the other harness's recorded home and refuses, before
changing anything, a Java runtime that an installed plugin cannot run. Only you can change what the updater trusts:
upgrading the updater or its policy always means running a release's entry command yourself.

If the command cannot write a required path, it names the path. Run the command outside the agent's sandbox.

### Update, reinstall or uninstall with your agent

After the command, ask your agent in plain words:

- "Update CAT", or "update CAT to v1.0": the `update` skill runs the updater's `update` operation.
- "Install CAT again" after you removed the plugin with the harness's own commands or deleted its directory: the
	`update` skill runs the updater's `install` operation, which needs no new command from you.
- "Go back to the previous CAT release": the skill runs `rollback`.
- "Uninstall CAT": the `uninstall` skill asks whether to also delete CAT's plugin data, then removes all of CAT. See
	[Uninstall](#uninstall).

## Permission boundary and installed locations

A project's agent can request only a release version through the updater's `install`, `update`, `rollback`, `status`
and `uninstall` operations, and the harness asks you to approve `rollback`. It cannot supply a repository, download URL, command, installation destination, or
replacement trust policy, and it cannot run `setup`. The setup command records the updater's protected data and policy
paths, each harness's home, and the harness program and environment that it found; later changes to `HOME`, `PATH`,
XDG variables, `CLAUDE_CONFIG_DIR`, `CODEX_HOME`, or `LOCALAPPDATA` cannot redirect a routine operation.

Setup adds the permission entries for exactly those operations on the installed launcher's absolute path, and records
the entries it added so that uninstall removes only those. In Claude Code, it adds them to `sandbox.excludedCommands`
and `permissions.allow` in `settings.json` in your Claude home, keeping every other setting. In Codex, it writes the
CAT-owned file `rules/cat-updater.rules` in your Codex home. Setup refuses, before changing anything, a harness
configuration that it cannot change safely, such as a malformed `settings.json` or a symbolic link in its place.

| Location | Linux and macOS | Windows |
| --- | --- | --- |
| Updater data | `${XDG_DATA_HOME:-$HOME/.local/share}/cat-updater` | `%LOCALAPPDATA%\cat-updater` |
| Policy | `${XDG_CONFIG_HOME:-$HOME/.config}/cat-updater/policy.json` | `%LOCALAPPDATA%\cat-updater\policy.json` |
| Stable launcher | `<data>/bin/cat-updater` | `<data>\bin\cat-updater.ps1` |
| Shared Java runtime | `<data>/runtime/` | `<data>\runtime\` |
| Updater application | `<data>/<updater-version>/` | `<data>\<updater-version>\` |
| State and log | `<data>/state/<harness>/` | `<data>\state\<harness>\` |

These environment variables select locations during setup. Relative XDG values are ignored. Setup refuses data or
policy locations inside the setup working directory, any recorded harness home, or `~/.cat`. Keep these paths outside
locations writable by your projects and plugins. No step requires root.

The plugins and the updater share the one Java runtime in `<data>/runtime`. Each installed plugin records that
runtime's path in its `client/runtime-binding` file, and its launchers find Java only through that file. A plugin
update never replaces the runtime or the updater.

The boundary is the harness's shell sandbox plus the updater's validation. An update changes CAT plugin files and
CAT-owned Codex agent files, while leaving Claude Code's `.claude.json`, `settings.json`, `installed_plugins.json`,
and `known_marketplaces.json`, and Codex's `config.toml` unchanged. Installing and uninstalling register and unregister
the plugin through the harness's own command-line program, which changes those files the way the harness itself does.

Hooks and MCP servers can run outside the shell sandbox as your user. Such code can change anything you can change,
including updater files and policy. Signed release approval authenticates the code you activate; it does not make
that code harmless. Project-controlled harness hooks, MCP servers, sandbox settings, or injected process variables
such as `LD_PRELOAD` are outside CAT's boundary. Administrator ownership of updater files is optional additional
hardening, not a requirement of the per-user installation.

## Approved releases and first-download trust

Each release publishes `cat-release-manifest.json` and its Ed25519 signature. The updater verifies the exact manifest
bytes against a key in its protected policy, then checks every downloaded plugin archive against the signed size and
SHA-256 digest. A checksum beside an unauthenticated archive is insufficient.

The default policy permits versions at least `1.0` with major version at most `1`, and refuses versions below the
active version during routine updates. A maintainer signature and these version limits jointly approve a release.
Changing keys, release source, or version limits requires an explicit user updater or policy upgrade. A build without
a release key refuses setup. HTTPS proxies may route downloads, but cannot change which signed bytes are accepted.

The initial `cat.sh` or `cat.ps1` download relies on HTTPS to GitHub. It contains the selected release tag and pinned
archive digests, including the updater archive that supplies the default public key. The release notes publish the
SHA-256 fingerprint of the public key's X.509 encoding; confirm that fingerprint through a source you trust before
authorizing setup. Entry scripts never fetch bootstrap code from a branch or `HEAD`.

## Routine operations

Ask your agent, or invoke the stable launcher directly:

```bash
/home/me/.local/share/cat-updater/bin/cat-updater install --harness claude
/home/me/.local/share/cat-updater/bin/cat-updater update --harness claude
/home/me/.local/share/cat-updater/bin/cat-updater update --harness codex v1.0
/home/me/.local/share/cat-updater/bin/cat-updater status --harness codex
```

Replace the launcher path with the one setup printed. On Windows the launcher is a PowerShell script, and every
command has this exact form, with the operation and its arguments after the script path:

```powershell
powershell.exe -NoProfile -NonInteractive -ExecutionPolicy Bypass -File "$env:LOCALAPPDATA\cat-updater\bin\cat-updater.ps1" status --harness codex
```

Omitting the version, or passing `latest`, requests GitHub's
latest release. That release must satisfy the policy; the updater refuses an unapproved latest release rather than
searching older releases. A named version requests that release and must satisfy the same policy. An already active,
consistent version reports `up_to_date` without changing it.

Going back to an older release is your decision. `install` and `update` refuse a version below the highest one that
this installation has already activated for the harness, and tell you to run `rollback` or the entry command. The
harness asks you to approve `rollback` before it runs.

`install` adds the plugin where the harness has none, for example after `claude plugin uninstall` or after deleting the
plugin directory, and then registers it with the harness. Where the plugin is present, `install` does what `update`
does. Neither the updater, the Java runtime nor the permission entries are involved in a reinstall, so you do not
repeat the install command.

An operation refuses a release whose Java requirement the installed runtime does not meet, and leaves the active
installation unchanged. The message tells you to run the entry command of a newer release, which replaces the runtime.
If the updater is absent, CAT's `update` skill gives you that command; it does not run installation from the project's
sandbox.

Updates lock the selected harness, download and validate into staging, then activate without executing any downloaded
installer or migration script. Unsafe archive paths, escaping symlinks, unsupported entries, and mismatched contents
are refused before activation. A second update waits up to ten minutes for the lock. Each result is a JSON object;
diagnostics go to standard error and the log is `<data>/state/<harness>/update.log`.

If a release requires a data migration, routine update refuses it. Run that release's entry command yourself to
authorize the migration. A validation failure keeps the active installation. An activation failure restores the
previous installation; a restoration failure reports the affected paths for recovery. If the updater stops during
activation, its next mutating operation recovers the recorded activation before proceeding.

Only the most recent successful activation has a rollback record. Older Codex cache directories may remain, but
rollback consumes the record; it does not provide a second rollback to toggle between versions. To restore the previous
installation, ask your agent or run rollback yourself:

```bash
/home/me/.local/share/cat-updater/bin/cat-updater rollback --harness codex
```

`status` reads the active and previous versions and policy key IDs without creating state or recovering an unfinished
activation. Run an update or rollback to recover an unfinished activation. Restart the harness after updating or rolling
back. Claude Code's plugin list may continue showing the version registered at initial installation; use updater
status for the active version. Codex needs a restart to reliably observe swapped plugin copies. A new or changed Codex
hook command may also require your review before it runs. Plugin-loading behavior was checked with Claude Code
2.1.317 and Codex 0.160.0; whether a long-running shared Codex app-server notices an update without restart remains
unverified.

| Exit status | Meaning and next action |
| --- | --- |
| `0` | Operation succeeded, or the approved release is already active. |
| `2` | Invalid arguments; correct the command. |
| `3` | Release, signature, archive, policy, Java runtime requirement, or migration refused; inspect the message. |
| `4` | Download failed; retry after restoring connectivity. |
| `5` | Activation failed and the previous installation was restored. |
| `6` | Activation and restoration failed; recover the paths reported in the result. |
| `7` | Uninstall could not delete CAT's data directory; delete the directory that the launcher names. |
| `75` | Lock wait expired; retry after the other update finishes. |
| `77` | A required path is not writable; run the operation yourself outside the agent's sandbox. |

## Uninstall

Ask your agent to uninstall CAT. The `uninstall` skill first asks whether to also delete CAT's plugin data; the default
is to keep it. It then runs the installed updater's `uninstall` operation, or `uninstall --delete-data` when you ask
to delete the data:

```bash
/home/me/.local/share/cat-updater/bin/cat-updater uninstall
/home/me/.local/share/cat-updater/bin/cat-updater uninstall --delete-data
```

On Windows, use the PowerShell form shown under [Routine operations](#routine-operations). Your harness may ask you to
approve the `uninstall` command before it runs.

Uninstall removes all of CAT, for every harness that the updater serves:

- the plugin and its registration in the harness, and in Codex the custom agents that CAT owns, recorded in its
	ownership manifest and still unchanged;
- the artifacts that CAT's runner launchers downloaded into `~/.cat/bundles`;
- the permission entries that setup added;
- the updater, its policy, state and logs, and the shared Java runtime.

It keeps CAT's plugin data unless you ask to delete it, and it never deletes unrelated settings, agents that CAT does
not own, or your own files, including `~/.cat/rules`. The updater deletes its own directory last, after its Java process
has exited, and reports `uninstalled` only once the directory is gone. If it cannot delete the directory, it reports
`not_removed` and prints the directory so that you can delete it yourself. An interrupted uninstall continues from its
journal when you run `uninstall` again.

Nothing is kept to support a later installation. To install CAT again, run the [install command](#install-cat) again.

## Let sessions run CAT's runners

CAT's `claude-runner` and `codex-runner` start an isolated Claude Code or Codex session with your login. Both plugins
hold both launchers, so a session in either harness can run either harness. A session runs a launcher as one command, by
its absolute path, with no pipe, redirection, `cd` or environment-variable prefix, and gives it its records with
`--input <file>`. Every run names its model with `--model <name>` and its reasoning effort with `--effort <level>`; the
launcher refuses a run without either before it starts a session. An agent that runs a launcher always passes both: the
ones that you or its instructions name, or else ones that it chooses from the values that the harness lists, without
asking you. The effort `none` runs the model without reasoning: `claude-runner` turns Claude Code's thinking off, and
`codex-runner` asks Codex for no reasoning.

Instead of `--model` and `--effort`, a run started outside a runner session may name `--agent <name>` with
`--install-cat`, in both launchers. It selects the CAT agent definition of that name in the launcher's CAT artifact, and
the session runs under the model and the reasoning effort that the definition names. The launcher refuses `--agent`
together with `--model` or `--effort`, and an agent definition that does not name both a model and an effort. A session
that a runner started cannot pass `--agent`.

Your harness's sandbox does not let a command start a harness with your login, so it refuses the launchers until
you exempt them. Exempt only these two launchers, so that every other command stays sandboxed. Sessions that a runner
starts need no configuration. When you pass `--session-requests <count>`, and `--request-depth <levels>` for deeper
levels, such a session runs the same launchers, which ask the runner that started it to start the session outside its
sandbox. Without `--session-requests`, it cannot start sessions.

On Linux, `codex-runner` needs bubblewrap (`bwrap`) on your `PATH` to start a session that reads earlier sessions'
results, such as a prompt optimizer's judge: it lets that session read those results while it hides the rest of your
project. Without it, the runner refuses such a session before it starts Codex. Install your distribution's
`bubblewrap` package, for example with `sudo apt install bubblewrap` or `sudo dnf install bubblewrap`.

In the examples, replace `/home/me` with your home directory. On Windows, the launchers end in `.cmd`.

<details>
<summary>Claude Code</summary>

Claude Code 2.1.285 or later runs CAT from `plugins/cat-marketplace/plugins/cat` in its configuration directory:
`~/.claude`, or `CLAUDE_CONFIG_DIR` when set. Add the launchers to `sandbox.excludedCommands`, which runs them outside
the sandbox, and to `permissions.allow`, which runs them without asking, in your user settings
(`~/.claude/settings.json`):

```json
{
	"sandbox": {
		"excludedCommands": [
			"/home/me/.claude/plugins/cat-marketplace/plugins/cat/bin/claude-runner *",
			"/home/me/.claude/plugins/cat-marketplace/plugins/cat/bin/codex-runner *"
		]
	},
	"permissions": {
		"allow": [
			"Bash(/home/me/.claude/plugins/cat-marketplace/plugins/cat/bin/claude-runner *)",
			"Bash(/home/me/.claude/plugins/cat-marketplace/plugins/cat/bin/codex-runner *)"
		]
	}
}
```

Merge these entries into the settings you have. The paths stay the same across CAT upgrades.

</details>

<details>
<summary>Codex</summary>

Codex runs CAT from `plugins/cache/cat/cat/<version>` in its home directory: `~/.codex`, or `CODEX_HOME` when set.
Create `~/.codex/rules/cat.rules`, which lets Codex run the launchers outside the sandbox without asking:

```python
prefix_rule(
	pattern = ["/home/me/.codex/plugins/cache/cat/cat/1.0/bin/claude-runner"],
	decision = "allow",
	justification = "CAT's runner starts an isolated Claude Code session with your login",
)
prefix_rule(
	pattern = ["/home/me/.codex/plugins/cache/cat/cat/1.0/bin/codex-runner"],
	decision = "allow",
	justification = "CAT's runner starts an isolated Codex session with your login",
)
```

Replace `1.0` with the version that you install, and update the rule when you upgrade to another version. To confirm
that a rule matches, run:

```bash
codex execpolicy check --rules ~/.codex/rules/cat.rules \
	/home/me/.codex/plugins/cache/cat/cat/1.0/bin/codex-runner --help
```

It prints `"decision":"allow"`. Restart Codex after you change its rules.

</details>

## Git inside a Codex runner session

The Codex runner gives each session an independent repository in its copied project. On Linux and macOS, the session
can stage, commit, create branches and child worktrees, and merge. Create child worktrees inside the copied project.
The repository's `.git/config` and `.git/hooks` remain read-only. Use Git's `-c` command options for per-command settings
such as commit identity. Windows sessions keep Codex's built-in restrictions on Git
metadata because CAT has not verified that its Windows sandbox protects configuration and hooks under this grant.

A retained project is session-produced evidence. Treat its Git refs, objects, `info/` and worktree metadata as untrusted
when running Git on it after the session ends.
