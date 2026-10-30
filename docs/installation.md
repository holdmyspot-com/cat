# Install CAT

CAT v1.0 supports Claude Code and Codex on Linux x64, Linux ARM64, macOS x64, macOS ARM64, and Windows x64.

## Install or upgrade

### 1. Select a release

Each bootstrap command installs the latest release by default. Append a release tag such as `v1.0` after `sh -s`, or
`-Version v1.0` after the PowerShell command, to select a specific version. Re-run the same command to upgrade CAT to
the latest release, or append a release tag to install that specific version.

### 2. Choose a coding harness

<details>
<summary>Claude Code</summary>

On Windows, run these commands in Git Bash, which Claude Code on Windows uses to run commands.

```bash
curl -fsSL \
	https://raw.githubusercontent.com/holdmyspot-com/cat/HEAD/client/claude/lifecycle/src/main/resources/install/bootstrap-claude.sh | sh
```

To install into a custom Claude Code configuration directory, set `CLAUDE_CONFIG_DIR`:

```bash
curl -fsSL \
	https://raw.githubusercontent.com/holdmyspot-com/cat/HEAD/client/claude/lifecycle/src/main/resources/install/bootstrap-claude.sh \
	| CLAUDE_CONFIG_DIR=/path/to/claude-home sh
```

</details>

<details>
<summary>Codex</summary>

```bash
curl -fsSL \
	https://raw.githubusercontent.com/holdmyspot-com/cat/HEAD/client/codex/lifecycle/src/main/resources/install/bootstrap-codex.sh | sh
```

To install into a custom Codex home directory, set `CODEX_HOME`:

```bash
curl -fsSL \
	https://raw.githubusercontent.com/holdmyspot-com/cat/HEAD/client/codex/lifecycle/src/main/resources/install/bootstrap-codex.sh \
	| CODEX_HOME=/path/to/codex-home sh
```

</details>

<details>
<summary>Codex on Windows</summary>

Run this command in PowerShell. It runs the installer as a script block, which PowerShell's execution policy allows:

```powershell
& ([scriptblock]::Create((irm https://raw.githubusercontent.com/holdmyspot-com/cat/HEAD/client/codex/lifecycle/src/main/resources/install/bootstrap-codex.ps1)))
```

To install into a custom Codex home directory, first run `$env:CODEX_HOME = 'C:\path\to\codex-home'`.

</details>

### 3. Start or resume a session

The installer selects the matching platform archive, verifies the release checksum, and installs CAT through the
harness's plugin mechanism. Start or resume a session after installation.

## Uninstall

### Choose a coding harness

#### Claude Code

```bash
curl -fsSL \
	"https://raw.githubusercontent.com/holdmyspot-com/cat/HEAD/client/claude/lifecycle/src/main/resources/install/"\
	"bootstrap-uninstall-claude.sh" | sh
```

#### Codex

```bash
curl -fsSL \
	"https://raw.githubusercontent.com/holdmyspot-com/cat/HEAD/client/codex/lifecycle/src/main/resources/install/"\
	"bootstrap-uninstall-codex.sh" | sh
```

#### Codex on Windows

```powershell
& ([scriptblock]::Create((irm https://raw.githubusercontent.com/holdmyspot-com/cat/HEAD/client/codex/lifecycle/src/main/resources/install/bootstrap-codex.ps1))) -Uninstall
```

The Codex uninstallers keep CAT's data, so that a later installation can migrate it. To delete it too, end the command
with `| sh -s -- --delete-data` instead of `| sh`, or add `-DeleteData` after `-Uninstall` on Windows.

The Codex uninstaller removes only the CAT-owned custom agents recorded in `~/.codex/agents`
(`%USERPROFILE%\.codex\agents` on Windows), then asks Codex to remove the plugin. Claude Code removes CAT's bundled
custom agents with the plugin.

## Let sessions run CAT's runners

CAT's `claude-runner` and `codex-runner` start an isolated Claude Code or Codex session with your login. Both
plugins hold both launchers, so a session in either harness can run either harness. A session runs a launcher as one
command, by its absolute path, with no pipe, redirection, `cd` or environment-variable prefix, and gives it its records
with `--input <file>`. Every run names its model with `--model <name>` and its reasoning effort with `--effort <level>`;
the launcher refuses a run without either before it starts a session, and CAT's runner skills ask you which to use when
you name none.

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
