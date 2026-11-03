# Install CAT

CAT v1.0 provides distributions for Claude Code and Codex on Linux x64, Linux ARM64, macOS x64, macOS ARM64, and
Windows x64. Windows ARM64 is not supported. Installation, routine plugin updates, and upgrades of the trusted
updater are separate operations.

## Install or migrate an existing installation

Run setup yourself in a terminal, or at a Claude Code prompt prefixed with `! `. Setup installs the plugin through the
harness's plugin mechanism, installs the trusted updater, and records the harness home in its policy. This operation
may change harness configuration. It does not add sandbox exemptions or permission rules for you.

### Linux and macOS

Choose your harness explicitly. The release URL selects the version; there is no version argument to the script.

```bash
curl -fsSL https://github.com/holdmyspot-com/cat/releases/download/v1.0/cat.sh | sh -s -- --harness claude
curl -fsSL https://github.com/holdmyspot-com/cat/releases/download/v1.0/cat.sh | sh -s -- --harness codex
```

For a custom harness home, supply the appropriate variable to setup:

```bash
curl -fsSL https://github.com/holdmyspot-com/cat/releases/download/v1.0/cat.sh | CLAUDE_CONFIG_DIR=/path/to/claude-home sh -s -- --harness claude
curl -fsSL https://github.com/holdmyspot-com/cat/releases/download/v1.0/cat.sh | CODEX_HOME=/path/to/codex-home sh -s -- --harness codex
```

### Windows

For Codex, run the per-release entry script in PowerShell:

```powershell
& ([scriptblock]::Create((irm https://github.com/holdmyspot-com/cat/releases/download/v1.0/cat.ps1))) -Harness codex
```

For a custom Codex home, set `$env:CODEX_HOME = 'C:\path\to\codex-home'` before setup. For Claude Code, use the
POSIX command above in Git Bash with `--harness claude`.

### Existing installations and updater upgrades

To add the trusted updater to an existing CAT installation, run the chosen release's command with `--updater-only`.
The same mode upgrades the updater and its policy without reinstalling the plugin:

```bash
curl -fsSL https://github.com/holdmyspot-com/cat/releases/download/v1.0/cat.sh | sh -s -- --harness claude --updater-only
curl -fsSL https://github.com/holdmyspot-com/cat/releases/download/v1.0/cat.sh | sh -s -- --harness codex --updater-only
```

On Windows, add `-UpdaterOnly` to the PowerShell setup command. Run this yourself; routine updates never replace the
trusted updater or change its policy. Setup refreshes the trust settings from that release's default policy and
preserves the other harness's recorded home when you set up both harnesses.

### Add the updater exemption, then restart

Setup prints entries containing the installed launcher's absolute path. Copy the complete entries into your user
settings, including any quoting and escaping; do not construct them by concatenating a raw path.
Exempt only the `update` operation, not the entire updater launcher. Then start or restart the harness.

For Claude Code, add the printed command to both `sandbox.excludedCommands` and `permissions.allow`. With the default
POSIX location, replacing `/home/me` with your actual home directory, the entries are:

```json
{
  "sandbox": {
    "excludedCommands": ["/home/me/.local/share/cat-updater/bin/cat-updater update --harness claude"]
  },
  "permissions": {
    "allow": ["Bash(/home/me/.local/share/cat-updater/bin/cat-updater update --harness claude)"]
  }
}
```

Merge these entries into your existing user settings rather than replacing the file. This exact form permits a
request for GitHub's latest release, subject to your updater policy. To permit a named release, add an exact entry
ending in that release, such as `v1.0`, in both lists. A looser `update:*` prefix permits all arguments after `update`;
the updater still rejects arguments outside its fixed grammar. `permissions.allow` alone does not take a command outside the sandbox. Claude Code may still ask
you to approve another unsandboxed command unless you set `sandbox.allowUnsandboxedCommands` to `false`.

For Codex, create `~/.codex/rules/cat-updater.rules` (under your recorded Codex home when customized):

```python
prefix_rule(
    pattern = ["/home/me/.local/share/cat-updater/bin/cat-updater", "update", "--harness", "codex"],
    decision = "allow",
    justification = "Request an authenticated CAT release through the trusted updater",
)
```

Use the absolute launcher path printed by setup; on Windows it ends in `.cmd`. This permits appended arguments, so the
updater validates the whole command itself. Environment prefixes, substitutions, redirections, or compound commands
may prevent these exemptions from matching. Invoke the launcher directly as one command.

## Permission boundary and installed locations

A project's agent can request only a release version through the updater's `update` operation. It cannot supply a
repository, download URL, command, installation destination, or replacement trust policy. Setup records the updater's
protected data and policy paths and the harness homes; later changes to `HOME`, XDG variables, `CLAUDE_CONFIG_DIR`,
`CODEX_HOME`, or `LOCALAPPDATA` cannot redirect a routine update.

| Location | Linux and macOS | Windows |
| --- | --- | --- |
| Updater data | `${XDG_DATA_HOME:-$HOME/.local/share}/cat-updater` | `%LOCALAPPDATA%\cat-updater` |
| Policy | `${XDG_CONFIG_HOME:-$HOME/.config}/cat-updater/policy.json` | `%LOCALAPPDATA%\cat-updater\policy.json` |
| Stable launcher | `<data>/bin/cat-updater` | `<data>\bin\cat-updater.cmd` |
| Runtime | `<data>/<updater-version>/` | `<data>\<updater-version>\` |
| State and log | `<data>/state/<harness>/` | `<data>\state\<harness>\` |

These environment variables select locations during setup. Relative XDG values are ignored. Setup refuses data or
policy locations inside the setup working directory, any recorded harness home, or `~/.cat`. Keep these paths outside
locations writable by your projects and plugins, and exempt only the installed launcher. No step requires root.

The boundary is the harness's shell sandbox plus the updater's validation. Routine activation changes CAT plugin
files and CAT-owned Codex agent files, while leaving Claude Code's `.claude.json`, `settings.json`,
`installed_plugins.json`, and `known_marketplaces.json`, and Codex's `config.toml` unchanged.

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

## Routine updates and rollback

After setup and the exemption, ask your agent to update CAT, or invoke the stable launcher directly:

```bash
/home/me/.local/share/cat-updater/bin/cat-updater update --harness claude
/home/me/.local/share/cat-updater/bin/cat-updater update --harness codex v1.0
/home/me/.local/share/cat-updater/bin/cat-updater status --harness codex
```

Replace the launcher path with the one setup printed. Omitting the version, or passing `latest`, requests GitHub's
latest release. That release must satisfy the policy; the updater refuses an unapproved latest release rather than
searching older releases. A named version requests that release and must satisfy the same policy. An already active,
consistent version reports `up_to_date` without changing it. If the updater is absent, CAT's `update` skill gives you
the setup command; it does not run installation from the project's sandbox.

Updates lock the selected harness, download and validate into staging, then activate without executing any downloaded
installer or migration script. Unsafe archive paths, escaping symlinks, unsupported entries, and mismatched contents
are refused before activation. A second update waits up to ten minutes for the lock. Each result is a JSON object;
diagnostics go to standard error and the log is `<data>/state/<harness>/update.log`.

If a release requires a data migration, routine update refuses it. Run that release's explicit setup command yourself
to authorize the migration. A validation failure keeps the active installation. An activation failure restores the
previous installation; a restoration failure reports the affected paths for recovery. If the updater stops during
activation, its next mutating operation recovers the recorded activation before proceeding.

Only the most recent successful activation has a rollback record. Older Codex cache directories may remain, but
rollback consumes the record; it does not provide a second rollback to toggle between versions. To restore the previous
installation, run rollback yourself outside the project sandbox; do not exempt rollback:

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
| `3` | Release, signature, archive, policy, or migration refused; inspect the message. |
| `4` | Download failed; retry after restoring connectivity. |
| `5` | Activation failed and the previous installation was restored. |
| `6` | Activation and restoration failed; recover the paths reported in the result. |
| `75` | Lock wait expired; retry after the other update finishes. |
| `77` | A required path is not writable; add only the printed updater exemption or run the user operation yourself. |

## Uninstall

Ask your agent to uninstall CAT using its installed `uninstall` skill, or run the selected release's entry script
yourself with an explicit harness:

```bash
curl -fsSL https://github.com/holdmyspot-com/cat/releases/download/v1.0/cat.sh | sh -s -- --harness claude --uninstall
curl -fsSL https://github.com/holdmyspot-com/cat/releases/download/v1.0/cat.sh | sh -s -- --harness codex --uninstall
```

For Codex on Windows, add `-Uninstall` to the PowerShell command. Codex keeps CAT's data for later migration; add
`--delete-data` (PowerShell `-DeleteData`) to remove it. The Codex uninstaller removes only CAT-owned custom agents
recorded in its ownership manifest. Claude Code removes CAT's bundled agents with the plugin.

Uninstalling CAT leaves the updater and policy in place. To remove them, first remove their exemption entries, ensure
no update is running, and delete the recorded updater data directory and policy directory. If data and policy share
one directory, delete it only once. This removes updater logs and rollback records too.

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
