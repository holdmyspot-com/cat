# Install CAT

CAT v1.0 provides distributions for Claude Code and Codex on Linux x64, Linux ARM64, macOS x64, macOS ARM64, and
Windows x64. Windows ARM64 is not supported. Prompt optimization is not available on native Windows, because its trials
need a sandbox that native Windows cannot reliably provide; to optimize prompts on Windows, install your harness and CAT
inside WSL2 and run them there. You install CAT for a harness with one command that you run yourself.
Afterwards your agent installs other CAT releases, checks CAT and uninstalls it for that harness through the installer
in the installed plugin. CAT's Claude Code support needs Claude Code 2.1.277 or later.

CAT is one plugin per harness. The plugin carries its own installer, in its `bin/installer`, and its own Java runtime,
in its `bin/runtime/`, so a release publishes one plugin archive for each harness and platform, named
`cat-<harness>-<platform>-v<version>.tar.gz` (for example `cat-claude-linux-x64-v1.0.tar.gz`), and nothing else is
needed to install or change the release. Claude Code and Codex each have their own installation of CAT, with their own
plugin, installer and Java runtime. The two installations never change each other, and they may run different CAT
releases.

## Install CAT

Run the command yourself in a terminal, or at a Claude Code prompt prefixed with `! `. Run it once for each harness you
use. The command downloads only that release's plugin archive for your harness and platform, checks it against the
SHA-256 digest that the command embeds, and runs the installer from the extracted archive. For the harness you chose,
the installer, in one step:

- puts the plugin in its own version folder in the harness's plugin folders, and registers it with the harness through
	the harness's own command-line program, which must be on your `PATH`;
- runs the data migrations that the release registers after the data version that your kept CAT data records;
- writes the installation's policy: the release's public key, the oldest release it accepts, the harness home and the
	harness program that it found;
- adds the permission entries that let your agent run the installer's `install` and `status` operations without a
	prompt, and `uninstall` after you approve it in the harness, because `uninstall` removes CAT.

You do not copy any permission or sandbox settings. Then start or restart the harness. The release's version is the one
in the command's address; there is no version argument. Where the harness has no CAT, the command refuses, before
changing anything, a release that is older than the data version that your kept CAT data records, because a migration of
a newer release has shaped that data and the older release cannot read it.

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
Git Bash, the POSIX command above also works for Claude Code; Codex on Windows needs `cat.ps1`.

### Codex permission profile

Codex's sandbox must let sandboxed commands write CAT's capacity lock folder, `~/.cat/locks`, because each session that
CAT's runners start takes a capacity lock there. What the installer changes in your Codex configuration, `config.toml`
in the Codex home, depends on what `default_permissions` selects:

- A permission profile that your `config.toml` defines: the installer adds `"<home>/.cat/locks" = "write"` to that
	profile's filesystem table. Every other project that uses the profile can then write that folder too, and the
	installer's result says so.
- Codex's built-in `:workspace` or `:read-only` profile: the installer cannot add an entry to a built-in profile, so it
	asks your permission to create the profile `cat-workspace` or `cat-read-only`, which extends the built-in profile and
	adds only write access to the lock folder, and to set `default_permissions` to it. Uninstalling CAT sets
	`default_permissions` back to the built-in profile and deletes the profile that CAT created, unless that profile
	holds settings that CAT did not write or `default_permissions` still names it; the uninstall result then says what
	it left.
- No profile: the installer adds the folder to `sandbox_workspace_write.writable_roots`.

The installer asks for the permission in the terminal, with `Allow this change? [y/N]`. Without your permission, or when
it cannot ask, it changes nothing and refuses with a message that explains the change. When your agent ran the install,
the refusal carries the exact command that gives the permission: the same installer command with `--create-profile`
before `install`. The agent asks you first, and your harness asks you to approve that command, because it grants a
permission.

### Installing a second harness, repairing, and changing what the installer trusts

To use both harnesses, run the command once with `--harness claude` and once with `--harness codex`. Each run creates
or changes only its own harness's installation.

The command is repeatable. Running it again repairs an installation that stopped part-way, because every step of an
install can be repeated. Where the harness already has CAT and the installation has a valid policy, the command installs
the archive's CAT version under that policy, like an install from a signed release, and leaves the policy as it is. Only
you can change what the installer trusts: its key, release source and version limits change only when you run a
release's entry command where the harness has no CAT or where its policy is not usable, for example after an uninstall.
The command then writes the policy from the archive again.

A runner of one harness that starts a session of the other harness runs the other harness's installed CAT plugin, of the
same release as its own plugin, whatever build of that release. It downloads nothing. When the other harness has no
plugin of that release, the runner stops before it starts anything and tells you to install CAT in the other harness;
run the entry command of the same release for that harness.

If the command cannot write a required path, it names the path. Run the command outside the agent's sandbox.

### Install another release, check CAT or uninstall with your agent

After the command, ask your agent in plain words:

- "Update CAT", or "install CAT v1.0": CAT's `update` skill runs the installed plugin's installer `install` operation,
	which installs the latest release that your policy approves, or the release you name, in a new version folder.
- "Go back to CAT v1.0": the `update` skill runs `install` for that release, which goes back unless CAT's data
	structures changed since it. See [Routine operations](#routine-operations).
- "Install CAT again" after you removed the plugin with the harness's own commands: when the installer in the version
	folder is still there, the `update` skill runs its `install` operation, which needs no new command from you;
	otherwise the skill gives you the entry command to run.
- "Which CAT version is active?": the `update` skill runs the installer's `status` operation.
- "Uninstall CAT": the `uninstall` skill asks whether to also delete CAT's plugin data, then removes this harness's
	CAT installation. See [Uninstall](#uninstall).

## Permission boundary and installed locations

A project's agent can request only a release tag through the installer's `install` operation, can run `status`, and can
run `uninstall` after you approve it in the harness. It cannot supply a repository, download URL, command, installation
destination, or replacement trust policy. Two options of `install` give your permission or redirect the installation:
`--home <directory>` names a harness home, and `--create-profile` allows the Codex profile change above. They go before
the word `install`, so that no command that the harness lets your agent run without a prompt carries them; the installer
refuses them after it.

The first install records the installation's protected data and policy paths, its harness's home, and the harness
program that it found; later changes to `HOME`, `PATH`, `CLAUDE_CONFIG_DIR`, or `CODEX_HOME` cannot redirect a routine
operation. An installation serves only its own harness: its installer refuses a `--harness` that names the other one.

Each install writes the permission entries for exactly those operations on the active version folder's installer,
records the entries it added so that uninstall removes only those, and removes the entries that name an older version
folder. Each harness's entries name that harness's own installer.

- In Claude Code, the entries go into `settings.json` in your Claude home, keeping every other setting. In
	`sandbox.excludedCommands`, each sandbox exclusion is one exact command: `install --harness claude`,
	`install --harness claude latest`, `status`, `status --harness claude`, `uninstall --harness claude` and
	`uninstall --harness claude --delete-data`, except `install --harness claude` followed by a release tag, which is a
	wildcard entry. In `permissions.allow`, install and status commands are allowed without a prompt, and `uninstall`
	keeps Claude Code's prompt. In `sandbox.filesystem.denyWrite`, the install denies sandboxed commands the other
	harness's marketplace folder, version folders and data directory, and in `sandbox.filesystem.allowWrite` it allows
	`~/.cat/locks`. Claude Code 2.1.277 and later run a command line outside the sandbox only when every command in it
	matches an entry, and a redirect, a `cd` or a `$(...)` keeps it sandboxed, so a command chained after the installer
	stays sandboxed. Earlier versions ran the whole command line outside the sandbox when one command matched a wildcard
	entry, so CAT's Claude Code support needs Claude Code 2.1.277 or later. An install of a release that the agent names,
	such as `install --harness claude v1.1`, matches the wildcard entry and the allowed entry, so it runs outside the
	sandbox and without a prompt.
- In Codex, the install writes the CAT-owned file `rules/cat-installer.rules` in your Codex home, which allows
	`install --harness codex` and `status` and keeps Codex's prompt for `uninstall --harness codex`, and adds the lock
	folder as described in [Codex permission profile](#codex-permission-profile).

An install refuses, before changing anything, a harness configuration that it cannot change safely, such as a malformed
`settings.json` or a symbolic link in its place.

### Installed locations

In the table, `<home>` is the harness home (`~/.claude` or `CLAUDE_CONFIG_DIR` for Claude Code, `~/.codex` or
`CODEX_HOME` for Codex), `<version>` is the plugin version, which looks like `1.0+b0123456789ab`, the release followed
by the first 12 digits of the commit that built the plugin, and `<data>` is the data directory. On Windows, the
separators are backslashes and the installer is `bin\installer.ps1`.

| Location | Claude Code | Codex |
| --- | --- | --- |
| Marketplace folder | `<home>/plugins/cat-marketplace` | `<home>/plugins/cat-marketplace` |
| Version folder | `<home>/plugins/cat-marketplace/plugins/cat/<version>/` | `<home>/plugins/cache/cat/cat/<version>/` |
| Installer | `<version folder>/bin/installer` | `<version folder>/bin/installer` |
| Java runtime | `<version folder>/bin/runtime/` | `<version folder>/bin/runtime/` |
| Data directory `<data>` | `<home>/plugins/data/cat-cat/` | `<home>/plugins/data/cat-cat/` |

For example, Claude Code's installer for the version `1.0+b0123456789ab` on Linux is
`~/.claude/plugins/cat-marketplace/plugins/cat/1.0+b0123456789ab/bin/installer`.

The data directory holds `policy.json` (the installation's policy); `state/` (the installer's lock, `update.log`, the
record of the active and replaced versions, the record of the permission entries it added, and the progress of an
uninstall); `releases/` (the signed manifest, its signature and the plugin archive of the active release and of the
release it replaced, which a later install reuses without downloading when they verify); and `migration-version`,
which records the newest data migration that has shaped CAT's data. An install never deletes the data directory. It stays
after an uninstall unless you ask to delete it.

An install keeps the version folder of the active version, the folder of the version it replaced, and the folder of the
installer that is running, and deletes every other version folder of CAT. A harness session that started before the
install keeps running from its folder until you restart it, which is why the replaced version's folder stays. Codex runs
the version folder with the greatest name, so a folder that Codex would run instead of the active version, such as a
newer version's folder after you go back to an older release, is deleted. A folder that the installer cannot delete,
such as one whose file a process still has open on Windows, is left, and a later install deletes it. On Windows, the
installer cannot delete the folder it is running from, so it starts Windows PowerShell, which waits for the installer to
exit and then deletes that folder, retrying for up to 60 seconds.

The boundary is the harness's shell sandbox plus the installer's validation. An install changes the plugin's version
folder and marketplace folder, CAT-owned Codex agent files and the permission entries above, and registers the plugin
through the harness's own command-line program, which changes `.claude.json`, `settings.json`,
`installed_plugins.json` and `known_marketplaces.json` in Claude Code, and `config.toml` in Codex, the way the harness
itself does. Installing and uninstalling register and unregister the plugin the same way.

Hooks and MCP servers can run outside the shell sandbox as your user. Such code can change anything you can change,
including installer files and policy. Signed release approval authenticates the code you activate; it does not make
that code harmless. Project-controlled harness hooks, MCP servers, sandbox settings, or injected process variables
such as `LD_PRELOAD` are outside CAT's boundary. Administrator ownership of installer files is optional additional
hardening, not a requirement of the per-user installation. No step requires root.

## Approved releases and first-download trust

Each release publishes the ten plugin archives, `SHA256SUMS.txt`, `cat-release-manifest.json` with its Ed25519
signature, and the entry scripts `cat.sh` and `cat.ps1`. The installer verifies the exact manifest bytes against a key
in its protected policy, then checks every downloaded plugin archive against the signed size and SHA-256 digest. A
checksum beside an unauthenticated archive is insufficient.

The default policy, from `bin/conf/installer/default-policy.json` in the plugin, permits releases at least the release
that the plugin shipped with and with a major version at most that release's major version. A maintainer signature and
these version limits jointly approve a release. Changing keys, release source, or version limits requires an explicit
entry command that you run where the harness has no CAT. A build without a release key refuses the install. HTTPS
proxies may route downloads, but cannot change which signed bytes are accepted.

The initial `cat.sh` or `cat.ps1` download relies on HTTPS to GitHub. It contains the selected release tag and pinned
archive digests, including the digest of the plugin archive that supplies the default public key. The release notes
publish the SHA-256 fingerprint of the public key's X.509 encoding; confirm that fingerprint through a source you trust
before you run the command. Entry scripts never fetch bootstrap code from a branch or `HEAD`.

## Routine operations

Ask your agent, or run the installer in the active version folder directly:

```bash
~/.claude/plugins/cat-marketplace/plugins/cat/1.0+b0123456789ab/bin/installer install --harness claude
~/.claude/plugins/cat-marketplace/plugins/cat/1.0+b0123456789ab/bin/installer install --harness claude v1.1
~/.codex/plugins/cache/cat/cat/1.0+b0123456789ab/bin/installer status --harness codex
```

Replace the installer path with the one in your active version folder. Each harness has its own installer, and it
accepts only its own harness. On Windows the installer is a PowerShell script, and every command has this exact form,
with the operation and its arguments after the script path:

```powershell
powershell.exe -NoProfile -NonInteractive -ExecutionPolicy Bypass -File "$env:USERPROFILE\.codex\plugins\cache\cat\cat\1.0+b0123456789ab\bin\installer.ps1" status --harness codex
```

The installer has three operations, and each accepts these arguments:

| Command | Effect |
| --- | --- |
| `install --harness <h>` | Installs the latest release that your policy approves. |
| `install --harness <h> latest` | The same. |
| `install --harness <h> v1.1` | Installs release `v1.1`, which must satisfy the policy. |
| `status` or `status --harness <h>` | Reads the active version without changing anything. |
| `uninstall` or `uninstall --harness <h>` | Uninstalls CAT and keeps its plugin data. |
| `uninstall --harness <h> --delete-data` | Uninstalls CAT and deletes its plugin data. |

`--home <directory>` and `--create-profile` go before the operation and apply to the first install only; an installed
installer refuses a `--home` other than the one that its policy records.

`install` installs a release into its own version folder and makes it the active one. Omitting the release, or passing
`latest`, requests GitHub's latest release. That release must satisfy the policy; the installer refuses an unapproved
latest release rather than searching older releases. A named release requests that release and must satisfy the same
policy. The installer verifies the signed release metadata and the plugin archive against your existing policy, and
never changes the policy's keys, release source or version limits. It then, in this order: writes the plugin into the
marketplace folder and asks the harness to install it, installs CAT's files that the harness reads outside the plugin
(Codex's custom agents), runs the data migrations that the release registers after the recorded data version, prepares a
start-up cache for the plugin's runtime, rewrites the permission entries to name the new version folder's installer, and
deletes the version folders that are no longer kept.

When the requested release is the build that is already active, `install` reports `up_to_date` and installs no new
version folder; it still installs CAT's files that the harness reads outside the plugin again, runs the data migrations
that an earlier install left pending, and brings the permission entries in line with your harness configuration, which
may have changed since. When the harness has no CAT plugin, for example after `claude plugin uninstall`, an `install`
that can still run installs the release and reports `installed`.

`install` goes back to an older release only when you name it; an install of the latest release refuses a latest release
that is older than the active one. Going back to an older release is an install like any other, with no extra prompt,
when CAT's data structures did not change between the two releases: the active release's migration registry, read from
its signed release, registers no data migration after the older release, up to and including the active one. Otherwise
`install` refuses with exit status `3` and changes nothing, because the older release cannot read data that a newer
release migrated, and installing it would lose data. The message names the migrations in between.

The file `migration-version` in the data directory records the newest migration that has shaped your CAT data. A first
install, which the entry command runs where the harness has no CAT, refuses a release that is older than that version,
because a migration of a newer release has shaped the data and the older release cannot read it. The message names the
release to install instead.

Installs lock the selected harness, download and validate into staging, then install. The installer runs the data
migration scripts that the release registers and, when it prepares the start-up cache, the plugin's Java runtime; it
runs nothing else from the archive. Unsafe archive paths, escaping symlinks, unsupported entries, and mismatched
contents are refused before anything is installed. A second operation waits up to ten minutes for the lock. Each result
is a JSON object; diagnostics go to standard error and the log is `<data>/state/<h>/update.log`. An install that stops
after it began to change the harness reports `failed`, or `not_writable` with the path; run the same command again,
which repeats the steps that did not finish. The installer does not restore the previous version.

Each install that adds a plugin prepares a start-up cache for the runtime that the plugin runs. Its result's `aot_cache`
field says whether that succeeded, and `false` only means slower start-up.

`status` reads the active version, whether the installation is consistent, and the policy key IDs, without creating
state or recovering an unfinished install. Run `install` to finish one. Restart the harness after an install. A new or
changed Codex hook command may also require your review before it runs. Plugin-loading behavior was checked with Claude
Code 2.1.317 and Codex 0.160.0; whether a long-running shared Codex app-server notices an install without restart
remains unverified.

| Exit status | Meaning and next action |
| --- | --- |
| `0` | Operation succeeded, or the approved release is already active. |
| `2` | Invalid arguments; correct the command. |
| `3` | Release, signature, archive, policy, migration or consent refused before anything changed; inspect the message. |
| `4` | Download failed; retry after restoring connectivity. |
| `9` | Uninstall stopped at a step (`uninstall_incomplete`); run the same uninstall command again to continue. |
| `10` | An unexpected error stopped the operation after it may have changed the installation (`failed`); run the same command again to continue. |
| `75` | Lock wait expired; retry after the other operation finishes. |
| `77` | A required path is not writable; run the operation yourself outside the agent's sandbox. |

## Uninstall

Ask your agent to uninstall CAT in the harness you are using. The `uninstall` skill first asks whether to also delete
CAT's plugin data; the default is to keep it. It then runs that harness's installed installer's `uninstall` operation,
adding `--delete-data` when you ask to delete the data:

```bash
~/.codex/plugins/cache/cat/cat/1.0+b0123456789ab/bin/installer uninstall --harness codex
~/.codex/plugins/cache/cat/cat/1.0+b0123456789ab/bin/installer uninstall --harness codex --delete-data
```

On Windows, use the PowerShell form shown under [Routine operations](#routine-operations). Your harness asks you to
approve the `uninstall` command before it runs.

Uninstall removes this harness's CAT installation, and no other harness's, in three steps that it records in
`state/uninstall.json` in the data directory:

1. the permission entries that install added for this harness, and in Codex the profile selection and profile that
	CAT added to `config.toml`;
2. the plugin's registration in the harness;
3. the plugin files: CAT's marketplace folder and every copy of the plugin that the harness keeps (in Codex,
	`plugins/cache/cat`), with all version folders, and in Codex the custom agents that CAT owns, recorded in its
	ownership manifest and still unchanged.

It keeps CAT's plugin data, the data directory, unless you ask to delete it, and it never deletes unrelated settings,
agents that CAT does not own, or your own files, including `~/.cat/rules`. A stopped uninstall continues from its record
at the first step that did not finish when you run `uninstall` again; an uninstall that finished leaves no record. The
installer cannot delete the files it runs from while it runs: on Linux and macOS it deletes the folder that holds its
version folder after it has printed its result, and on Windows it starts Windows PowerShell, which waits for the
installer to exit and then deletes that folder, retrying for up to 60 seconds. If the folder is still there after that,
delete it yourself. If the harness program that the first install recorded no longer exists, uninstall cannot unregister
the plugin: it removes everything else and reports `uninstalled` with `"unregistered": false`, and the harness may still
list the plugin until you remove it there: in Claude Code with `claude plugin uninstall cat@cat` and
`claude plugin marketplace remove cat`; in Codex with `codex plugin remove cat@cat` and
`codex plugin marketplace remove cat`.

To install CAT again for this harness, run the [install command](#install-cat) again. Kept plugin data takes part in
that install: the entry command writes the policy again, and it refuses a release older than the data version in
`migration-version`.

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

Your harness's sandbox does not let a command start a harness with your login, so it refuses the launchers until you
exempt them. Exempt only these two launchers, so that every other command stays sandboxed. Since an exempted launcher
runs outside the sandbox, it always runs its own CAT artifact and the `claude` or `codex` command that your `PATH`
finds: a launch that names `--artifact` exits with status 2 and starts nothing. Sessions that a runner starts need no
configuration. When you pass `--session-requests <count>`, and `--request-depth <levels>` for deeper levels, such a
session runs the same launchers, which ask the runner that started it to start the session outside its sandbox. Without
`--session-requests`, it cannot start sessions.

On Linux, `codex-runner` needs bubblewrap (`bwrap`) on your `PATH` to start a session that reads earlier sessions'
results, such as a prompt optimizer's judge: it lets that session read those results while it hides the rest of your
project. Without it, the runner refuses such a session before it starts Codex. Install your distribution's
`bubblewrap` package, for example with `sudo apt install bubblewrap` or `sudo dnf install bubblewrap`.

In the examples, replace `/home/me` with your home directory. On Windows, the launchers end in `.cmd`.

<details>
<summary>Claude Code</summary>

Claude Code 2.1.285 or later runs CAT from `plugins/cat-marketplace/plugins/cat/<version>` in its configuration
directory: `~/.claude`, or `CLAUDE_CONFIG_DIR` when set. Add the launchers to `sandbox.excludedCommands`, which runs
them outside the sandbox, and to `permissions.allow`, which runs them without asking, in your user settings
(`~/.claude/settings.json`):

```json
{
	"sandbox": {
		"excludedCommands": [
			"/home/me/.claude/plugins/cat-marketplace/plugins/cat/1.0+b0123456789ab/bin/claude-runner *",
			"/home/me/.claude/plugins/cat-marketplace/plugins/cat/1.0+b0123456789ab/bin/codex-runner *"
		]
	},
	"permissions": {
		"allow": [
			"Bash(/home/me/.claude/plugins/cat-marketplace/plugins/cat/1.0+b0123456789ab/bin/claude-runner *)",
			"Bash(/home/me/.claude/plugins/cat-marketplace/plugins/cat/1.0+b0123456789ab/bin/codex-runner *)"
		]
	}
}
```

Replace `1.0+b0123456789ab` with the name of the version folder that you installed, and merge these entries into the
settings you have. Each CAT version has its own folder, so change the paths when you install another version.

</details>

<details>
<summary>Codex</summary>

Codex runs CAT from `plugins/cache/cat/cat/<version>` in its home directory: `~/.codex`, or `CODEX_HOME` when set.
Create `~/.codex/rules/cat.rules`, which lets Codex run the launchers outside the sandbox without asking:

```python
prefix_rule(
	pattern = ["/home/me/.codex/plugins/cache/cat/cat/1.0+b0123456789ab/bin/claude-runner"],
	decision = "allow",
	justification = "CAT's runner starts an isolated Claude Code session with your login",
)
prefix_rule(
	pattern = ["/home/me/.codex/plugins/cache/cat/cat/1.0+b0123456789ab/bin/codex-runner"],
	decision = "allow",
	justification = "CAT's runner starts an isolated Codex session with your login",
)
```

Replace `1.0+b0123456789ab` with the name of the version folder that you installed, and update the rule when you install
another version. To confirm that a rule matches, run:

```bash
codex execpolicy check --rules ~/.codex/rules/cat.rules \
	/home/me/.codex/plugins/cache/cat/cat/1.0+b0123456789ab/bin/codex-runner --help
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
