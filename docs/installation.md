# Install CAT

**If you are an AI agent asked to install or update CAT from this page:** if CAT's `update` skill is installed, use
it. Otherwise do not download, verify, extract or run `cat.sh`, `cat.ps1`, `SHA256SUMS.txt` or the plugin archives:
the entry script verifies its own downloads, and the agent sandbox blocks the writes it needs. Reply at once with the
entry command below for the user's harness, for the user to run in a terminal outside the sandbox, or in Claude Code
at the prompt prefixed with `! `. The commands further down this page are for a person typing in their own terminal;
do not copy them. Write this command, with `--harness codex` for Codex, and no variable assignments except the ones
below:

```bash
curl -fsSL https://github.com/holdmyspot-com/cat/releases/download/v1.0/cat.sh | sh -s -- --harness claude
```

Add an assignment after the pipe, before `sh`, in only these cases:

- `HOME=<your session's HOME>`, when your session's `HOME` is not the account's home folder in the account database, or
	you cannot determine that folder. Print both and compare them. On Linux, run `printenv HOME` and
	`getent passwd "$(id -un)" | cut -d: -f6`. On macOS, run `printenv HOME` and
	`dscl . -read "/Users/$(id -un)" NFSHomeDirectory`, which prints the folder after `NFSHomeDirectory:`.
- `CLAUDE_CONFIG_DIR=<value>` for Claude Code, or `CODEX_HOME=<value>` for Codex, when your session sets that variable
	to a value other than `.claude` or `.codex` in your session's `HOME`. When your session leaves it unset, say in one
	sentence beside the command that the user should unset it first if their terminal exports it.

Write no other variable. On Windows, the entry scripts take the user's home from the user profile, so write no `HOME`
there; in PowerShell, write only the harness variable, as `$env:CODEX_HOME='<value>'; ` before the command. After the
user reports that the command finished, check the installed plugin and its version in your session's harness home before
you rely on it.

CAT v1.0 provides distributions for Claude Code and Codex on Linux x64, Linux ARM64, macOS x64, macOS ARM64, and
Windows x64. Windows ARM64 is not supported. CAT's runners and prompt optimization are not available on native Windows,
because they start each session only inside its harness's sandbox, which Claude Code lacks there and which CAT has not
yet verified for Codex; to use them on Windows, install your harness and CAT inside WSL2 and run them there. You install
CAT for a harness with one command that you run yourself.
Afterwards your agent installs other CAT releases, checks CAT and uninstalls it for that harness through the installer
in the installed plugin. CAT's Claude Code support needs Claude Code 2.1.277 or later.

CAT is one plugin per harness. The plugin carries its own installer, in its `bin/installer`, and its own Java runtime,
in its `bin/runtime/`, so a release publishes one plugin archive for each harness and platform, named
`cat-<harness>-<platform>-v<version>.tar.gz` (for example `cat-claude-linux-x64-v1.0.tar.gz`), and nothing else is
needed to install or change the release. Claude Code and Codex each have their own installation of CAT, with their own
plugin, installer and Java runtime. The two installations never change each other, and they may run different CAT
releases.

## Install CAT

These commands are for a person typing in their own terminal, or at a Claude Code prompt prefixed with `! `; an agent
follows the paragraph at the top of this page instead. Run the command once for each harness you use. The command
downloads only that release's plugin archive for your harness and platform, checks it against the SHA-256 digest that
the command embeds, and runs the installer from the extracted archive. For the harness you chose,
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

To install somewhere other than the default, see [Choosing where CAT installs](#choosing-where-cat-installs).

### Windows

In PowerShell, run the per-release entry script with `-Harness`:

```powershell
& ([scriptblock]::Create((irm https://github.com/holdmyspot-com/cat/releases/download/v1.0/cat.ps1))) -Harness codex
& ([scriptblock]::Create((irm https://github.com/holdmyspot-com/cat/releases/download/v1.0/cat.ps1))) -Harness claude
```

In Git Bash, the POSIX command above also works for Claude Code; Codex on Windows needs `cat.ps1`.

### Choosing where CAT installs

These environment variables choose the folders that the entry scripts and the installer that they run use. Put an
assignment after the pipe, before `sh`, so that it reaches the script. The plugin data folder, which holds the
installation's policy, and the capacity-lock folder have no variable of their own: the data folder is always inside the
harness home, and the lock folder is always `.cat/locks` in your home folder. The installer finds the harness program on
`PATH`, and no variable changes where the script downloads from.

| Variable | What it changes | Default |
| --- | --- | --- |
| `CLAUDE_CONFIG_DIR` (Claude Code), `CODEX_HOME` (Codex) | The harness home. CAT's plugin folders, its plugin data folder (the policy with its trust keys, installer state and kept releases), and the permission entries all go inside it. It must be an existing absolute folder. | `.claude` or `.codex` in your home folder |
| `HOME` (`USERPROFILE` on Windows) | Your home folder: where the default harness home is, and where the installer puts `.cat/locks`, the folder that CAT's runners write in to take capacity locks. The installer refuses to run unless it is an absolute path. | Your account's home folder |
| `TMPDIR` (`cat.sh`), `TEMP` (`cat.ps1`) | Where the script downloads and unpacks the plugin archive. It deletes that folder when it finishes. | `/tmp`; `TEMP` has no default and must be set |

Examples, each moving only that variable:

```bash
curl -fsSL https://github.com/holdmyspot-com/cat/releases/download/v1.0/cat.sh | CLAUDE_CONFIG_DIR=/path/to/claude-home sh -s -- --harness claude
curl -fsSL https://github.com/holdmyspot-com/cat/releases/download/v1.0/cat.sh | CODEX_HOME=/path/to/codex-home sh -s -- --harness codex
curl -fsSL https://github.com/holdmyspot-com/cat/releases/download/v1.0/cat.sh | HOME=/path/to/home sh -s -- --harness claude
curl -fsSL https://github.com/holdmyspot-com/cat/releases/download/v1.0/cat.sh | TMPDIR=/path/to/temp sh -s -- --harness claude
```

```powershell
$env:CLAUDE_CONFIG_DIR = 'C:\path\to\claude-home'; & ([scriptblock]::Create((irm https://github.com/holdmyspot-com/cat/releases/download/v1.0/cat.ps1))) -Harness claude
$env:CODEX_HOME = 'C:\path\to\codex-home'; & ([scriptblock]::Create((irm https://github.com/holdmyspot-com/cat/releases/download/v1.0/cat.ps1))) -Harness codex
$env:USERPROFILE = 'C:\path\to\home'; & ([scriptblock]::Create((irm https://github.com/holdmyspot-com/cat/releases/download/v1.0/cat.ps1))) -Harness codex
$env:TEMP = 'C:\path\to\temp'; & ([scriptblock]::Create((irm https://github.com/holdmyspot-com/cat/releases/download/v1.0/cat.ps1))) -Harness codex
```

If your terminal already exports one of these variables, the command uses its value. On Windows, `cat.ps1` takes the
default harness home from your user profile folder, not from `USERPROFILE`, so setting `USERPROFILE` moves
`.cat\locks` but not the harness home; set the harness variable to move that. In Git Bash, `cat.sh` reads `HOME` for the default
harness home and the installer reads `USERPROFILE`. The installation records its harness home, so a later change to
these variables does not redirect an update; [Installed locations](#installed-locations) lists what the installer
writes.

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

### Codex hook trust

Codex runs a plugin's hook only after `config.toml` records the hash that Codex computes for the hook, which Codex
records when you review and trust the hook. CAT's SessionStart hook starts the session launcher, and its other hooks
serve the runners and subagents, so without the record a fresh Codex runs none of them and CAT's runners report that no
session launcher is running. The Codex install therefore writes the record for each hook that it installs: a
`[hooks.state."cat@cat:hooks/hooks.json:<event>:<group>:<handler>"]` table in `config.toml` that holds `trusted_hash`,
the hash of that hook. With the record, Codex runs CAT's hooks without asking you, and the install result says so.

An update to a release whose hooks differ replaces the hashes that went stale, including one that you recorded for an
earlier version of CAT's hook, and removes the record of a hook that the release no longer has. The install leaves the
records of other plugins' hooks and of your own hooks as they are, and so it leaves an `enabled = false` that you set on
one of CAT's hooks, which keeps that hook off.

Uninstalling CAT removes the hashes that the installs recorded, and the tables that held nothing else. It keeps a hash
that you recorded yourself, because the install did not write it, and a hash that you changed after the install, which
the uninstall result lists. The install computes the hashes the way Codex 0.160.0 does, for command hooks of the events
`PreToolUse`, `PostToolUse`, `SessionStart` and `SubagentStart`; Codex asks you to review any other hook of CAT's
plugin. An install refuses, changing nothing, a `config.toml` that holds the state of one of CAT's hooks in a form that
it cannot edit, such as an inline table, and names the table to write.

### Installing a second harness, repairing, and changing what the installer trusts

To use both harnesses, install CAT into the second harness with the installer of the first one's installed plugin. In
Claude Code, for example, the installed plugin's version folder holds `bin/installer`; run, in a terminal:

```sh
<installed plugin>/bin/installer install --harness codex
```

On Windows, run `bin/installer.ps1` in PowerShell instead. The installer downloads the other harness's plugin of its
own release from the release source of its policy, checks the download's signed manifest with the policy's keys, as it
does for every release that it installs, and installs that plugin as the entry command would: the other harness's
version folder, policy, permission entries and permission questions are the same. It installs only its own release: a
command that names another release is refused. It installs only into a harness that has no CAT: where the other harness
already has CAT, the command is refused and names the command of that installation's own installer that updates it.
A development build, whose policy trusts no release key, cannot verify a download and refuses; use the entry command
instead.

Running the entry command once with `--harness claude` and once with `--harness codex` installs both harnesses too.
Each run creates or changes only its own harness's installation.

The command is repeatable. Running it again repairs an installation that stopped part-way, because every step of an
install can be repeated. Where the harness already has CAT and the installation has a valid policy, the command installs
the archive's CAT version under that policy, like an install from a signed release, and keeps the policy's release
source and version limits. A policy that lists a release key keeps its keys. A policy that lists none, such as one that
a build without a release key wrote, gets the release's signing key once the install succeeds, and the command's result
says that it added the key; from then on the installer can verify that key's releases. Only you can change what the
installer trusts: a policy's keys, release source and version limits are replaced only when you run a release's entry
command where the harness has no CAT or where its policy is not usable, for example after an uninstall. The command
then writes the policy from the archive again.

A runner of one harness that starts a session of the other harness runs the other harness's installed CAT plugin, of the
same release as its own plugin, whatever build of that release. The runner itself downloads nothing. When the other
harness has no plugin of that release, the runner stops before it starts anything and names a command that installs or
updates CAT in the other harness, such as the installer command above. Your harness asks you to approve that command
before it runs; once it has run, start the runner again. The entry command of the same release for that harness does
the same.

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
	folder as described in [Codex permission profile](#codex-permission-profile). It also trusts CAT's hooks, as
	described in [Codex hook trust](#codex-hook-trust).

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
installer that is running, and deletes every other version folder of CAT. In Claude Code, it also deletes every other
file and folder in `<home>/plugins/cat-marketplace/plugins/cat/`, which only the installer writes, such as the plugin
files that an earlier layout put directly in that folder. Claude Code runs CAT in place from that version folder, so a
Claude Code session that started before the install keeps running from its folder until you restart it, which is why the
replaced version's folder stays. Codex runs CAT from its own plugin cache and deletes the old cache folder when it
installs the new version, so restart a running Codex session after an install. Codex runs the version folder with the
greatest name, so a folder that Codex would run instead of the active version, such as a newer
version's folder after you go back to an older release, is deleted. A folder that the installer cannot delete,
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
entry command that you run where the harness has no CAT; where the harness has CAT, the entry command only adds its
release's key to a policy that lists no key. A build without a release key refuses the install. HTTPS
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
| `status --verbose` or `status --harness <h> --verbose` | Also lists the permission and sandbox entries. |
| `uninstall` or `uninstall --harness <h>` | Uninstalls CAT and keeps its plugin data. |
| `uninstall --harness <h> --delete-data` | Uninstalls CAT and deletes its plugin data. |

`--home <directory>` and `--create-profile` go before the operation and apply to the first install only; an installed
installer refuses a `--home` other than the one that its policy records.

The report that `install` prints for you groups the permission and sandbox entries it wrote by their effect, such as
"Runs outside the sandbox", and shortens their paths: `~` stands for your home folder and `installer` for the active
version folder's installer. `status --verbose` lists each entry that install recorded exactly as your harness
configuration holds it, under the file and the setting that hold it, such as `settings.json` and
`sandbox.excludedCommands`; with `--json` before the operation, the result's `permissions` field holds the same entries.
It refuses with exit status `3`, changing nothing, when `HOME` (`USERPROFILE` on Windows) is not an absolute path.

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
state or recovering an unfinished install. Run `install` to finish one. Restart the harness after an install. The Codex
install trusts CAT's hooks, as [Codex hook trust](#codex-hook-trust) describes, so Codex runs them without your review.
Plugin-loading behavior was checked with Claude Code 2.1.317 and Codex 0.160.0; whether a long-running shared Codex
app-server notices an install without restart remains unverified.

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
	CAT added to `config.toml` and the hook hashes that it recorded there;
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
its absolute path, with no pipe or redirection, and gives it its records with `--input <file>`. Every run names its
model with `--model <name>` and its reasoning effort with `--effort <level>`; the launcher refuses a run without either
before it starts a session. An agent that runs a launcher always passes both: the ones that you or its instructions
name, or else ones that it chooses from the values that the harness lists, without asking you. The effort `none` runs
the model without reasoning: `claude-runner` turns Claude Code's thinking off, and `codex-runner` asks Codex for no
reasoning.

Instead of `--model` and `--effort`, a run started outside a runner session may name `--agent <name>` with
`--install-cat`, in both launchers. It selects the CAT agent definition of that name in the launcher's CAT artifact, and
the session runs under the model and the reasoning effort that the definition names. The launcher refuses `--agent`
together with `--model` or `--effort`, and an agent definition that does not name both a model and an effort. A session
that a runner started cannot pass `--agent`.

The runners need no configuration. When a Claude Code or Codex session starts, CAT's SessionStart hook starts a session
launcher for it: a process outside the session's sandbox that serves that session until it ends. A runner launcher that
the session runs asks its session launcher to start the new session, and the session launcher starts it inside that
harness's own sandbox, so that the new session can read, write and reach no more than the session that asked for it.
The session launcher reads the asking session's sandbox policy from that harness's own settings and command line, never
from the request. A launcher always runs its own CAT artifact and the `claude` or `codex` command that your `PATH`
finds: a launch that names `--artifact` exits with status 2 and starts nothing. A runner launcher that no harness runs,
such as one that you run in a terminal, starts the new session itself, inside that harness's own sandbox, with exactly
what the session needs. A runner launcher run by the harness whose plugin did not provide it starts nothing and names
the plugin to use instead.

A session that was already running when you installed or updated CAT has no session launcher, so its runner launchers
start nothing and print: "No CAT session launcher serves this session. CAT starts one when a session starts; sessions
started before CAT was installed or updated have none. Start a new Claude Code session and run the command again." (or
"Codex session" in Codex). Start a new session and run the task there.

A Codex session that runs in Codex's shared background server, which Codex uses unless it starts with `--no-daemon`,
has no session launcher either, because the server outlives the session and would keep its launcher running after the
session ends. Codex's message then adds: "A Codex session that runs in Codex's shared background server, which Codex
uses unless it starts with --no-daemon, has none either; start Codex with --no-daemon." Start Codex with
`--no-daemon`, then start a new session.

When CAT cannot read a session's sandbox policy safely, its runner launchers start nothing either. The message begins
"CAT cannot start the session: it cannot read this Claude Code session's sandbox policy safely:" (or "this Codex
session's") and goes on to say what failed and how to fix it: for example, a settings file that cannot be parsed, a
settings file that the session's sandbox lets its commands write, or a harness option that CAT does not know. Make the
fix that the message names, then start a new session.

A session that you ask a runner to start needs a few writes outside its run folder, such as its dependency caches, and
the host of its model. When the session that asks does not allow all of them in its sandbox, the launcher starts nothing.
Its message begins "CAT cannot start the session: this Claude Code session's sandbox does not let its commands write
... and reach ..., which the requested session needs." (or "this Codex session's"), lists every missing path and host
and names the entries to add: in Claude Code, to `sandbox.filesystem.allowWrite` and `sandbox.network.allowedDomains` in
your settings; in Codex, to the filesystem and network domain entries of your permissions profile. Add them, then start
a new session, because a running session keeps the policy it started with. When a path lies inside a path that your
sandbox denies writing, the message names that denial: remove it, because allowing the folder would not help.

A launch may ask for more than a session's own needs, such as the host of a package registry or a cache outside your
project, with `--extra-permissions <file>`, where the file is JSON like
`{"format_version": 1, "writes": ["/home/me/.cache/pip"], "hosts": ["pypi.org"]}`. Either list may be left out, every
write is an absolute path, and a host is a name or an IP address, never a pattern. Without the option nothing extra is
requested. The extra writes and hosts are treated like the session's own: when your sandbox does not allow them, the
launch is refused with the message above, which lists them. The prompt optimizer's `start` takes the same option and
applies the file to every session of the campaign.

Earlier versions of this guide told you to exempt the two runner launchers from your harness's sandbox. They no longer
need that exception, so `install` removes it and reports each removal: in Claude Code, the
runner entries in `sandbox.excludedCommands` and `permissions.allow` of your user settings; in Codex, the file
`rules/cat.rules` in your Codex home when it holds only those runner rules. When that file also holds other content,
`install` leaves it as it is and lists the runner rules for you to remove; remove them, then restart Codex.

Sessions that a runner starts need no configuration either. When you pass `--session-requests <count>`, and
`--request-depth <levels>` for deeper levels, such a session runs the same launchers, which ask the runner that started
it to start the session outside its sandbox. Without `--session-requests`, it cannot start sessions: its runner
launchers exit with status 2 and say that its run lets it request no sessions.

Runner sessions need Linux or macOS, in both harnesses. On native Windows, the runner launchers refuse to start a
session, exit with status 2 and tell you to install your harness and CAT inside WSL2, because Claude Code has no
sandbox there and CAT has not yet verified that Codex's sandbox confines a session there. Inside WSL2, runner sessions
are supported.

A Codex session whose sandbox has network access off cannot start runner sessions, because a session that it started
could not reach its model either. Its runner launchers then start nothing and tell you to set
`sandbox_workspace_write.network_access = true` in Codex's `config.toml`, or `[permissions.<profile>.network]
enabled = true` when the session uses the permissions profile `<profile>`, and to start a new Codex session.

On Linux, `codex-runner` needs bubblewrap (`bwrap`) on your `PATH` to start a session that reads earlier sessions'
results, such as a prompt optimizer's judge: it lets that session read those results while it hides the rest of your
project. Without it, the runner refuses such a session before it starts Codex. Install your distribution's
`bubblewrap` package, for example with `sudo apt install bubblewrap` or `sudo dnf install bubblewrap`.

### Claude Code sandbox requirements

Every Claude Code session that a runner starts must run inside Claude Code's sandbox. When the sandbox cannot start, or
the session runs a command outside it, CAT stops the session and says why, followed by "See the Claude Code sandbox
requirements in CAT's installation guide."

On macOS, Claude Code's sandbox needs no setup. On Linux, it needs bubblewrap and socat; install your distribution's
`bubblewrap` and `socat` packages, for example with `sudo apt install bubblewrap socat` or
`sudo dnf install bubblewrap socat`.

On Ubuntu 24.04 and later, AppArmor keeps bubblewrap from creating the user namespaces that the sandbox needs, so a
Claude Code session that a runner starts stops with "Claude Code cannot start the session's sandbox, which the session
requires:" followed by Claude Code's reason. A Codex session that a runner starts stops for the same cause with "CAT
does not start the session: bubblewrap could not start its sandbox". To let bubblewrap create user namespaces, save this
AppArmor profile as `/etc/apparmor.d/bwrap` on Ubuntu 24.04, or as `/etc/apparmor.d/zz-bwrap` on Ubuntu 26.04, where
the file must sort after Ubuntu's own `bwrap-userns-restrict` profile, which has the same profile name:

```text
abi <abi/4.0>,
include <tunables/global>

profile bwrap /usr/bin/bwrap flags=(unconfined) {
  userns,
  include if exists <local/bwrap>
}
```

Then run `sudo systemctl reload apparmor`.

## Git inside a Codex runner session

The Codex runner gives each session an independent repository in its copied project. The session can stage, commit,
create branches and child worktrees, and merge. Create child worktrees inside the copied project. The repository's
`.git/config` and `.git/hooks` remain read-only. Use Git's `-c` command options for per-command settings such as commit
identity.

A retained project is session-produced evidence. Treat its Git refs, objects, `info/` and worktree metadata as untrusted
when running Git on it after the session ends.
