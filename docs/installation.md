# Install CAT

CAT v1.0 provides distributions for Claude Code and Codex on Linux x64, Linux ARM64, macOS x64, macOS ARM64, and
Windows x64. Windows ARM64 is not supported. You install CAT for a harness with one command that you run yourself.
Afterwards your agent installs, updates and uninstalls CAT for that harness through its installed trusted updater.
CAT's Claude Code support needs Claude Code 2.1.277 or later.

CAT is one bundle per harness: a Java runtime, the trusted updater and the harness's CAT plugin. The bundle is
installed, updated and removed as a whole. Claude Code and Codex each have their own bundle, with their own
Java runtime and updater, so using both harnesses puts two Java runtimes on disk. The two bundles never change each
other, and they may run different CAT releases.

## Install CAT

Run the command yourself in a terminal, or at a Claude Code prompt prefixed with `! `. Run it once for each harness
you use. For the harness you chose, it installs, in one step:

- a Java runtime for that harness's installation, used by its updater and its CAT plugin;
- the trusted updater and its policy, which records your harness home and the harness program that setup found;
- the CAT plugin, registered with the harness through its own command-line program;
- the permission entries that let your agent run the updater's `install`, `update` and `status` operations without a
	prompt, and `uninstall` after you approve it in the harness, and never `setup`. The harness asks you before it runs
	`uninstall`, because it removes CAT.

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

### Installing a second harness, repairing, and changing what the updater trusts

To use both harnesses, run the command once with `--harness claude` and once with `--harness codex`. Each run creates
or changes only its own harness's installation.

The command is repeatable. Running it again repairs an installation that stopped part-way and installs the release's
Java runtime, updater and plugin again. When the release that the command comes from differs from the installed one, the
command moves this harness's installation to that release as a whole. When that release is newer, it keeps the bundle it
replaced until the next successful update. It refuses, before changing anything, a Java runtime that this
installation's plugin cannot run. Only you can change what the updater trusts: its keys, release source and version
limits change only when you run a release's entry command yourself.

If the command cannot write a required path, it names the path. Run the command outside the agent's sandbox.

### Update, reinstall or uninstall with your agent

After the command, ask your agent in plain words:

- "Update CAT", or "update CAT to v1.0": the `update` skill runs the updater's `update` operation, which moves the
	Java runtime, the updater and the plugin of this harness's installation to the release together.
- "Go back to CAT v1.0": the `update` skill runs the updater's `update` operation for that release, which moves the
	whole installation back when CAT's data structures did not change since that release.
- "Install CAT again" after you removed the plugin with the harness's own commands or deleted its directory: the
	`update` skill runs the updater's `install` operation, which needs no new command from you.
- "Uninstall CAT": the `uninstall` skill asks whether to also delete CAT's plugin data, then removes this harness's
	CAT installation. See [Uninstall](#uninstall).

## Permission boundary and installed locations

A project's agent can request only a release version through the updater's `install`, `update`, `status` and
`uninstall` operations, and the harness asks you to approve `uninstall`. It cannot supply a
repository, download URL, command, installation destination, or replacement trust policy, and it cannot run `setup`.
Each installation's setup command records that installation's protected data and policy paths, its harness's home, and
the harness program and environment that it found; later changes to `HOME`, `PATH`, XDG variables,
`CLAUDE_CONFIG_DIR`, `CODEX_HOME`, or `LOCALAPPDATA` cannot redirect a routine operation. An installation serves only
its own harness: its updater refuses a `--harness` that names the other one.

Setup adds the permission entries for exactly those operations on the installed launcher's absolute path, and records
the entries it added so that uninstall removes only those. Each harness's entries name that harness's own launcher. In
Claude Code, it adds them to `sandbox.excludedCommands` and `permissions.allow` in `settings.json` in your Claude home,
keeping every other setting. Each sandbox exclusion is one exact command, except the one for `update --harness claude`
followed by a release. An exact entry cannot match a command line that carries anything else. Claude Code 2.1.277 and
later run a command line outside the sandbox only when every command in it matches an entry, and a redirect, a `cd` or a
`$(...)` keeps it sandboxed, so a command chained after the launcher stays sandboxed. Earlier versions ran the whole
command line outside the sandbox when one command matched a wildcard entry, so CAT's Claude Code support needs Claude
Code 2.1.277 or later. An update to a release that the agent names, such as `update --harness claude v1.1`, matches that
wildcard entry and the allowed entry, so it runs outside the sandbox and without a prompt. In Codex, it writes the
CAT-owned file `rules/cat-updater.rules` in your Codex home. Setup refuses, before changing anything, a harness
configuration that it cannot change safely, such as a malformed `settings.json` or a symbolic link in its place.

In the table, `<h>` is `claude` or `codex`, and `<data>` is the harness's installation directory.

| Location | Linux and macOS | Windows |
| --- | --- | --- |
| Installation directory `<data>` | `${XDG_DATA_HOME:-$HOME/.local/share}/cat-updater/<h>` | `%LOCALAPPDATA%\cat-updater\<h>` |
| Policy | `${XDG_CONFIG_HOME:-$HOME/.config}/cat-updater/<h>/policy.json` | `<data>\policy.json` |
| Stable launcher | `<data>/bin/cat-updater` | `<data>\bin\cat-updater.ps1` |
| Java runtime | `<data>/runtime/` | `<data>\runtime\` |
| Updater application | `<data>/<updater-version>/` | `<data>\<updater-version>\` |
| State and log | `<data>/state/<h>/` | `<data>\state\<h>\` |

For example, Claude Code's stable launcher on Linux is `~/.local/share/cat-updater/claude/bin/cat-updater`, and Codex's
is `~/.local/share/cat-updater/codex/bin/cat-updater`.

These environment variables select locations during setup. Relative XDG values are ignored. Setup refuses data or
policy locations inside the setup working directory, the harness home, or `~/.cat`. Keep these paths outside
locations writable by your projects and plugins. No step requires root.

Each installation has one Java runtime, in its `<data>/runtime`. The installation's updater and its installed plugin
use it: the plugin records the runtime's path in its `client/runtime-binding` file, and its launchers find Java only
through that file. No Java runtime is shared with the other harness's installation. An update replaces the runtime, the
updater and the plugin together, and nothing in one installation changes the other.

The boundary is the harness's shell sandbox plus the updater's validation. An update changes the installation's Java
runtime and updater, CAT plugin files and CAT-owned Codex agent files, while leaving Claude Code's `.claude.json`,
`settings.json`, `installed_plugins.json`, and `known_marketplaces.json`, and Codex's `config.toml` unchanged.
Installing and uninstalling register and unregister the plugin through the harness's own command-line program, which
changes those files the way the harness itself does.

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
/home/me/.local/share/cat-updater/claude/bin/cat-updater install --harness claude
/home/me/.local/share/cat-updater/claude/bin/cat-updater update --harness claude
/home/me/.local/share/cat-updater/codex/bin/cat-updater update --harness codex v1.0
/home/me/.local/share/cat-updater/codex/bin/cat-updater status --harness codex
```

Replace the launcher path with the one setup printed. Each harness has its own launcher, and it accepts only its own
harness. On Windows the launcher is a PowerShell script, and every command has this exact form, with the operation and
its arguments after the script path:

```powershell
powershell.exe -NoProfile -NonInteractive -ExecutionPolicy Bypass -File "$env:LOCALAPPDATA\cat-updater\codex\bin\cat-updater.ps1" status --harness codex
```

`update` moves this harness's whole installation to the release: the Java runtime, the updater and the plugin. Omitting
the version, or passing `latest`, requests GitHub's latest release. That release must satisfy the policy; the updater
refuses an unapproved latest release rather than searching older releases. A named version requests that release and
must satisfy the same policy. An already active, consistent version reports `up_to_date` without changing any installed
file; it only registers the plugin with the harness again when the harness no longer lists it. An update verifies the
signed release metadata and both archives against your existing policy; it never changes the policy's keys, release
source or version limits.

`update` goes back to an older release only when you name it; an update to the latest release refuses a latest
release that is older than the active one. Going back to an older release is an update like any other, with no extra
prompt, when CAT's data structures did not change between the two releases: the active release's migration registry,
read from its signed release, registers no data migration after the older release, up to and including the active
one. Otherwise `update` refuses with exit status `3` and changes nothing, because the older release cannot read data
that a newer release migrated, and installing it would lose data. The message names the migrations in between.
After going back, the installation records the older release's data version.

`install` adds the plugin where the harness has none, for example after `claude plugin uninstall` or after deleting the
plugin directory, and then registers it with the harness. It installs only the release that this installation's
updater and Java runtime are at, and refuses any other release, including `latest` when that is a newer release;
run `update` to move to another release. Where the plugin is present, `install` does what `update` does. The updater,
the Java runtime and the permission entries are not involved in a reinstall, so you do not repeat the entry command.

An operation refuses a release whose Java requirement the Java runtime that would run it does not meet, and leaves the
active installation unchanged. The message tells you to run the entry command of a newer release, which replaces the
runtime. If the updater is absent, CAT's `update` skill gives you that command; it does not run installation from the
project's sandbox.

Updates lock the selected harness, download and validate into staging, then activate without executing any downloaded
installer or migration script. Unsafe archive paths, escaping symlinks, unsupported entries, and mismatched contents
are refused before activation. A second update waits up to ten minutes for the lock. Each result is a JSON object;
diagnostics go to standard error and the log is `<data>/state/<h>/update.log`.

The updater cannot replace the Java runtime that it runs on. When an `update` changes the runtime, the
updater exits after staging it, and the stable launcher puts the new runtime in place, checks that it runs, and only
then prints the result. A result that reports success therefore means the runtime, the updater and the plugin are all
at the same release. If the launcher cannot put the runtime in place, it restores the old one and prints
`not_swapped`. The agent then runs `install --harness <h>` at once, which undoes what remains of the failed operation
and puts back the previous release, so that CAT's hooks work again, and tells you that the operation could not complete.
Each operation that activates a plugin, including one that undoes or resumes an unfinished update, also
prepares a start-up cache for the runtime that the plugin runs once the operation finishes. Its result's `aot_cache`
field says whether that succeeded, and `false` only means slower start-up. A result without the field activated no
plugin, except the three results that the stable launcher prints itself: `not_swapped`, `not_removed` and `locked` hold
only `result` and `message`.

If a release requires a data migration, routine update refuses it. Run that release's entry command yourself to
authorize the migration. A validation failure keeps the active installation. An activation failure restores the
previous installation; a restoration failure reports the affected paths for recovery. If the updater stops during
activation, its next mutating operation recovers the recorded activation before proceeding.

After a successful `update`, the installation keeps the complete bundle that it replaced as a cached bundle until
the next successful update deletes it: the previous Java runtime, the previous updater and the previous plugin. Running
the entry command of a newer release also keeps the bundle that it replaced; running it where nothing is installed keeps
none. For the cached bundle and the active release, `<data>/releases/` also keeps the release's signed manifest, its
signature and its plugin archive. Going back to the release of the cached bundle reuses it without downloading
anything, also without a network connection, when the kept manifest's signature verifies against the policy's keys, the
plugin archive matches the manifest's digest and the runtime and updater are unchanged since the updater installed
them. Otherwise the updater discards what it kept of that release and downloads the release again. Installing the
plugin again after you removed it reuses the active release's kept plugin archive in the same way. Older Codex cache
directories may remain.

`status` reads the active version, whether a cached bundle exists (`cached_bundle`), a Java runtime replacement that
the launcher has not finished (`pending_swap`) and policy key IDs, without creating state or recovering an unfinished
activation. Run an update to recover an unfinished activation or finish a pending replacement. Restart the
harness after updating. Claude Code's plugin list may continue showing the version registered at initial
installation; use updater status for the active version. Codex needs a restart to reliably observe swapped plugin
copies. A new or changed Codex hook command may also require your review before it runs. Plugin-loading behavior was
checked with Claude Code 2.1.317 and Codex 0.160.0; whether a long-running shared Codex app-server notices an update
without restart remains unverified.

| Exit status | Meaning and next action |
| --- | --- |
| `0` | Operation succeeded, or the approved release is already active. |
| `2` | Invalid arguments; correct the command. |
| `3` | Release, signature, archive, policy, Java runtime requirement, or migration refused before anything changed; inspect the message. |
| `4` | Download failed; retry after restoring connectivity. |
| `5` | Activation failed and the previous installation was restored. |
| `6` | Activation and restoration failed; recover the paths reported in the result. |
| `7` | Uninstall could not delete CAT's installation directory; delete the directory that the launcher names. |
| `8` | The launcher could not put the new Java runtime in place and restored the old one; the next operation undoes the rest. |
| `9` | Uninstall stopped at a step (`uninstall_incomplete`); run the same uninstall command again to continue. |
| `10` | An unexpected error stopped the operation after it may have changed the installation (`failed`); run the same command again to continue. |
| `75` | Lock wait expired; retry after the other update finishes. |
| `77` | A required path is not writable; run the operation yourself outside the agent's sandbox. |

## Uninstall

Ask your agent to uninstall CAT in the harness you are using. The `uninstall` skill first asks whether to also delete
CAT's plugin data; the default is to keep it. It then runs that harness's installed updater's `uninstall` operation for
that harness, adding `--delete-data` when you ask to delete the data:

```bash
/home/me/.local/share/cat-updater/codex/bin/cat-updater uninstall --harness codex
/home/me/.local/share/cat-updater/codex/bin/cat-updater uninstall --harness codex --delete-data
```

On Windows, use the PowerShell form shown under [Routine operations](#routine-operations). Your harness asks you to
approve the `uninstall` command before it runs.

Uninstall removes this harness's whole CAT installation, and no other harness's:

- the plugin and its registration in the harness, and in Codex the custom agents that CAT owns, recorded in its
	ownership manifest and still unchanged;
- the permission entries that setup added for this harness;
- the installation directory `<data>` and the policy: the updater, its state and logs, the Java runtime, and the
	cached bundle and release files that the installation kept;
- the artifacts that CAT's runner launchers downloaded into `~/.cat/bundles`, also while the other harness still has
	its installation. That installation downloads a copy again the next time it launches the other harness.

It keeps CAT's plugin data unless you ask to delete it, and it never deletes unrelated settings, agents that CAT does
not own, or your own files, including `~/.cat/rules`. The updater deletes its own directory last, after its Java process
has exited, and reports `uninstalled` only once the directory is gone. If it cannot delete the directory, it reports
`not_removed` and prints the directory so that you can delete it yourself. An interrupted uninstall continues from its
journal when you run `uninstall` again. If the harness program that setup recorded no longer exists, uninstall cannot
unregister the plugin: it removes everything else and reports `uninstalled` with `"unregistered": false`, and the
harness may still list the plugin until you remove it there: in Claude Code with `claude plugin uninstall cat@cat`
and `claude plugin marketplace remove cat`; in Codex with `codex plugin remove cat@cat` and `codex plugin marketplace
remove cat`.

Nothing is kept to support a later installation. To install CAT again for this harness, run the
[install command](#install-cat) again.

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
