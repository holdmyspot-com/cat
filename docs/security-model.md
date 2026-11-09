# CAT security model

This document is for security reviewers and administrators who decide whether to allow CAT in their environment. It
states what CAT's installer trusts, what it lets an agent do, what it writes and where, and how CAT's runners isolate
the sessions they start. The [installation guide](installation.md) is for the person installing CAT.

## What the installer trusts

### Approved releases

Each release publishes ten plugin archives, one per harness and platform, named
`cat-<harness>-<platform>-v<version>.tar.gz` (for example `cat-claude-linux-x64-v1.0.tar.gz`), `SHA256SUMS.txt`,
`cat-release-manifest.json` with its Ed25519 signature, and the entry scripts `cat.sh` and `cat.ps1`. A plugin carries
its own installer, `bin/installer`, and its own Java runtime, `bin/runtime/`, so nothing else is needed to install or
change a release. Claude Code and Codex each have their own installation, which never changes the other's and may run a
different release.

The installer verifies the exact manifest bytes against a key in its protected policy, then checks every downloaded
plugin archive against the signed size and SHA-256 digest. A checksum beside an unauthenticated archive is
insufficient. A maintainer signature and the policy's version limits jointly approve a release. The default policy,
from `bin/conf/installer/default-policy.json` in the plugin, permits releases at least the release that the plugin
shipped with and with a major version at most that release's major version. A build without a release key refuses to
install. HTTPS proxies may route downloads, but cannot change which signed bytes are accepted.

An install without a release name, or with `latest`, requests GitHub's latest release, which must satisfy the policy;
the installer refuses an unapproved latest release rather than searching older ones, and refuses a latest release that
is older than the active one. It goes back to an older release only when you name it, and then only when the active
release's signed migration registry registers no data migration after the older release; otherwise it refuses with exit
status `3` and names the migrations in between, because the older release cannot read data that a newer release
migrated.

An install locks the selected harness, then downloads and validates into a staging folder before it installs. Unsafe
archive paths, escaping symlinks, unsupported entries and mismatched contents are refused before anything is
installed. From the archive, the installer runs only the data migration scripts that the release registers, the
plugin's Java runtime when it prepares a start-up cache, and, once an update has made another version active, that
version's installer. That follow-up run asks nothing: a change that you declined during the update stays unmade and is
not offered again. The installer does not restore the previous version when an install fails; the failure names the
command that repeats the steps that did not finish, and every step can be repeated. `status` reads the active version,
whether the installation is consistent and the policy key IDs without creating state or recovering an unfinished
install.

### First-download trust

The first `cat.sh` or `cat.ps1` download relies on HTTPS to GitHub. It contains the selected release tag and pinned
archive digests, including the digest of the plugin archive that supplies the default public key. The release notes
publish the SHA-256 fingerprint of the public key's X.509 encoding; confirm that fingerprint through a source you trust
before you run the command. Entry scripts never fetch bootstrap code from a branch or `HEAD`. The script downloads only
the plugin archive for the chosen harness and platform, checks it against the embedded digest, and runs the installer
from the extracted archive. It deletes its download folder when it finishes.

### The policy and who can change it

The installation's policy, `policy.json` in its data directory, holds the release's public key, the oldest release it
accepts, the release source, the harness home and the harness program that the installer found. A routine install
never changes the policy's keys, release source or version limits. Only you change them, by running a release's entry
command where the harness has no CAT or where its policy is not usable, for example after an uninstall; the command
then writes the policy from the archive again.

Where the harness already has CAT and a valid policy, the entry command installs the archive's version under that
policy, like an install from a signed release, and keeps the policy's release source, version limits and keys. A policy
that lists no key, such as one that a build without a release key wrote, gets the release's signing key once the
install succeeds, and the command's result says that it added the key.

The first install records the installation's protected data and policy paths, the harness home and the harness program.
Later changes to `HOME`, `PATH`, `CLAUDE_CONFIG_DIR` or `CODEX_HOME` cannot redirect a routine operation, and an
installed installer refuses a `--home` other than the one its policy records. A first install refuses, changing
nothing, when `PATH` holds no program of the harness or the program it holds cannot start in the installer's
environment. An installation serves only its own harness: its installer refuses a `--harness` that names the other one.
A first install also refuses a release older than the data version in `migration-version` in the data directory, which
records the newest data migration that has shaped CAT's data, because the older release cannot read that data.

## Permission boundary

### What an agent can ask the installer to do

A project's agent can request only a release tag through the installer's `install` operation, can run `status`, and can
run `uninstall` after you approve it in the harness. It cannot supply a repository, download URL, command,
installation destination, or replacement trust policy. Two options of `install` give your permission or redirect the
installation: `--home <directory>` names a harness home, and `--create-profile` allows the Codex profile change below.
They go before the word `install`, so that no command that the harness lets your agent run without a prompt carries
them; the installer refuses them after it. `--home` applies to a first install only.

An installer can install the other harness's plugin, as `install --harness <other>`. It downloads that plugin of its
own release from the release source of its policy, checks the signed manifest with the policy's keys as for every
release, and installs it as the entry command would, with the same version folder, policy, permission entries and
permission questions. It installs only its own release; a command that names another release is refused. It installs
only into a harness that has no CAT; where the other harness has CAT, the command is refused and names the command of
that installation's own installer that updates it. A development build, whose policy trusts no release key, cannot
verify a download and refuses. A runner of one harness that starts a session of the other runs the other harness's
installed plugin of the same release as its own and downloads nothing; when that plugin is missing, the runner stops
before it starts anything and names a command that installs or updates CAT there, which your harness asks you to
approve.

An install refuses, before changing anything, a harness configuration that it cannot change safely, such as a malformed
`settings.json` or a symbolic link in its place.

### Entries each harness gets

Each install writes the permission entries for exactly the three operations above on the active version folder's
installer, records the entries it added so that uninstall removes only those, and removes the entries that name an
older version folder. Each harness's entries name that harness's own installer. Earlier versions also added exceptions
for the runner launchers' absolute paths; an install now removes those entries and leaves an entry that you added
yourself. `status --verbose` lists each recorded entry exactly as your harness configuration holds it, under the file
and the setting that hold it; with `--json` before the operation, the result's `permissions` field holds the same
entries.

**Claude Code.** The entries go into `settings.json` in your Claude home, which keeps every other setting.

- `sandbox.excludedCommands`: each exclusion is one exact command: `install --harness claude`,
	`install --harness claude latest`, `status`, `status --harness claude`, `uninstall --harness claude` and
	`uninstall --harness claude --delete-data`, except `install --harness claude` followed by a release tag, which is a
	wildcard entry. A command on this list runs outside the sandbox.
- `permissions.allow`: install and status commands run without a prompt. `uninstall` keeps Claude Code's prompt.
- `sandbox.filesystem.denyWrite`: sandboxed commands may not write the other harness's marketplace folder, version
	folders and data directory.
- `sandbox.filesystem.allowWrite`: sandboxed commands may write `~/.cat/locks`.
- On Linux and macOS, the request server `request-server` beside the installer, as its absolute path with no
	arguments: in `sandbox.excludedCommands`, and as `Bash(<path>)` in `permissions.allow`. Sessions run it outside
	their sandbox and without a prompt. The runners' isolation section below describes the protection around it.

**Codex.** The install writes the CAT-owned file `rules/cat-installer.rules` in your Codex home. It allows
`install --harness codex` and `status`, and keeps Codex's prompt for `uninstall --harness codex`. On Linux and macOS it
also allows the request server's absolute path with no arguments, so that Codex runs it outside its sandbox without
asking. The install also adds the lock folder as the next section describes, and records the hook trust below.

**Why Claude Code 2.1.277.** Claude Code 2.1.277 and later run a command line outside the sandbox only when every
command in it matches an entry, and a redirect, a `cd` or a `$(...)` keeps it sandboxed, so a command chained after the
installer stays sandboxed. Earlier versions ran the whole command line outside the sandbox when one command matched a
wildcard entry, so CAT's Claude Code support needs 2.1.277 or later. An install of a release that the agent names, such
as `install --harness claude v1.1`, matches the wildcard entry and the allowed entry, so it runs outside the sandbox
and without a prompt.

### Codex permission profile

Codex's sandbox must let sandboxed commands write `~/.cat/locks`, because each session that CAT's runners start takes a
capacity lock there. The installer changes `config.toml` in the Codex home according to what `default_permissions`
selects:

- A permission profile that your `config.toml` defines: the installer adds `"<home>/.cat/locks" = "write"` to that
	profile's filesystem table. Every other project that uses the profile can then write that folder too, and the
	installer's result says so.
- Codex's built-in `:workspace` or `:read-only` profile: the installer cannot add an entry to a built-in profile, so
	it asks your permission to create the profile `cat-workspace` or `cat-read-only`, which extends the built-in profile
	and adds only write access to the lock folder, and to set `default_permissions` to it.
- No profile: only a selected profile can hold the `read` entries that keep sandboxed commands from writing the shell
	startup files, so the installer follows `sandbox_mode`. For `workspace-write` or `read-only` it asks your permission
	to create the profile `cat-sandbox-workspace-write`, which extends `:workspace`, or `cat-sandbox-read-only`, which
	extends `:read-only`, and to add `default_permissions` to select it. For `workspace-write` the profile also gets a
	copy of your `[sandbox_workspace_write]` settings: a `write` entry for each writable folder, `enabled = true` in its
	network table for `network_access`, and a `read` entry for `:tmpdir` and `:slash_tmp` for `exclude_tmpdir_env_var`
	and `exclude_slash_tmp`. Codex then uses the profile in place of `sandbox_mode` in every project, unless a project's
	configuration or a command-line option selects another. Without your permission, and with `danger-full-access` or
	no `sandbox_mode`, which no single profile matches in every project, the installer adds the lock folder to
	`sandbox_workspace_write.writable_roots` instead, and its result says that the startup files stay writable.

The installer asks in the terminal, with `Allow this change? [y/N]`. For a built-in profile, without your permission,
or when it cannot ask, it changes nothing and refuses with a message that explains the change. When your agent ran the
install, the refusal carries the exact command that gives the permission: the same installer command with
`--create-profile` before `install`. The agent asks you first, and your harness asks you to approve that command,
because it grants a permission. For `sandbox_mode`, the install goes on without the profile, and running the installer
again in a terminal, or with `--create-profile` before `install`, creates it later.

Uninstalling CAT sets `default_permissions` back to the built-in profile, or removes the line that the install added,
and deletes the profile that CAT created, unless that profile holds settings that CAT did not write or
`default_permissions` still names it; the uninstall result then says what it left.

### Codex hook trust

Codex runs a plugin's hook only after `config.toml` records the hash that Codex computes for the hook. CAT's hooks serve
the runners and subagents, so without the record a fresh Codex runs none of them. The Codex install therefore writes
the record for each hook that it installs: a `[hooks.state."cat@cat:hooks/hooks.json:<event>:<group>:<handler>"]` table
in `config.toml` that holds `trusted_hash`, the hash of that hook. The install computes the hashes the way Codex 0.160.0
does, for command hooks of the events `PreToolUse`, `PostToolUse`, `SessionStart` and `SubagentStart`; Codex asks you to
review any other hook of CAT's plugin.

An update to a release whose hooks differ replaces the hashes that went stale, including one that you recorded for an
earlier version of CAT's hook, and removes the record of a hook that the release no longer has. The install leaves the
records of other plugins' hooks and of your own hooks as they are, and so leaves an `enabled = false` that you set on
one of CAT's hooks, which keeps that hook off. An install refuses, changing nothing, a `config.toml` that holds the
state of one of CAT's hooks in a form that it cannot edit, such as an inline table, and names the table to write.

Uninstalling CAT removes the hashes that the installs recorded, and the tables that held nothing else. It keeps a hash
that you recorded yourself, because the install did not write it, and a hash that you changed after the install, which
the uninstall result lists.

### The limits of the boundary

The boundary is the harness's shell sandbox plus the installer's validation. An install changes the plugin's version
folder and marketplace folder, CAT-owned Codex agent files and the permission entries above, and registers the plugin
through the harness's own command-line program, which changes `.claude.json`, `settings.json`, `installed_plugins.json`
and `known_marketplaces.json` in Claude Code, and `config.toml` in Codex, the way the harness itself does. Uninstalling
unregisters the plugin the same way.

Hooks and MCP servers can run outside the shell sandbox as your user. Such code can change anything you can change,
including installer files and policy. Signed release approval authenticates the code you activate; it does not make
that code harmless. Project-controlled harness hooks, MCP servers, sandbox settings, or injected process variables such
as `LD_PRELOAD` are outside CAT's boundary. Administrator ownership of installer files is optional additional hardening,
not a requirement of the per-user installation. No step requires root. Plugin-loading behavior was checked with Claude
Code 2.1.317 and Codex 0.160.0; whether a long-running shared Codex app-server notices an install without restart
remains unverified.

## Installed locations

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
`~/.claude/plugins/cat-marketplace/plugins/cat/1.0+b0123456789ab/bin/installer`. The data directory is always inside the
harness home. The capacity-lock folder is always `~/.cat/locks`, with `HOME` (`USERPROFILE` on Windows) required to be
an absolute path. No variable changes where the script downloads from.

The data directory holds `policy.json`; `state/` (the installer's lock, `<h>/update.log`, the record of the active and
replaced versions, the record of the permission entries it added, and `uninstall.json`, the progress of an uninstall);
`releases/` (the signed manifest, its signature and the plugin archive of the active release and of the release it
replaced, which a later install reuses without downloading when they verify); and `migration-version`. An install never
deletes the data directory. It stays after an uninstall unless you ask to delete it.

An install keeps the version folder of the active version, the folder of the version it replaced, and the folder of the
installer that is running, and deletes every other version folder of CAT. In Claude Code, it also deletes every other
file and folder in `<home>/plugins/cat-marketplace/plugins/cat/`, which only the installer writes. Claude Code runs CAT
in place from that version folder, so a session that started before the install keeps running from its folder until
you restart it, which is why the replaced version's folder stays. Codex runs CAT from its own plugin cache, deletes the
old cache folder when it installs the new version, and runs the version folder with the greatest name, so the install
deletes a folder that Codex would run instead of the active version, such as a newer version's folder after you go
back to an older release. A folder that the installer cannot delete, such as one whose file a process still has open on
Windows, is left, and a later install deletes it. An installer cannot delete the files it runs from while it runs: on
Linux and macOS, uninstall deletes the folder that holds its version folder after it has printed its result, and on
Windows, install and uninstall start Windows PowerShell, which waits for the installer to exit and then deletes that
folder, retrying for up to 60 seconds.

An uninstall removes, in three steps that it records in `state/uninstall.json`: the permission entries that the install
added for this harness (in Codex also the profile selection, the profile and the hook hashes in `config.toml`); the
plugin's registration in the harness; and the plugin files, with all version folders and every copy of the plugin that
the harness keeps (in Codex `plugins/cache/cat`), and the Codex custom agents that CAT owns, recorded in its ownership
manifest and still unchanged. It never deletes unrelated settings, agents that CAT does not own, or your own files,
including `~/.cat/rules`. When the harness program that the first install recorded no longer exists, uninstall cannot
unregister the plugin: it removes everything else and reports `uninstalled` with `"unregistered": false`.

## Runners' isolation

CAT's `claude-runner` and `codex-runner` start an isolated Claude Code or Codex session with your login. Both plugins
hold both launchers, so a session in either harness can run either harness.

### Login

A Codex session gets a copy of your Codex login. A Claude session uses your own Claude Code login, not a copy: its
Claude Code keeps its own settings and reads and refreshes your login where your own Claude Code keeps it, which is
the folder that `CLAUDE_SECURESTORAGE_CONFIG_DIR` names when your environment sets it, or else your Claude
configuration folder (`CLAUDE_CONFIG_DIR`, or else `.claude` in your home folder), or on macOS the Keychain. The
sessions that a session requests use it too. A copy would stop working as soon as your own session, or another copy,
refreshed the login, which can happen in the middle of a turn. The commands that a session runs cannot read your login
file in that folder.

When the environment that starts Claude Code sets `CLAUDE_CODE_OAUTH_TOKEN`, `ANTHROPIC_API_KEY` or
`ANTHROPIC_AUTH_TOKEN`, there or in the `env` of your Claude settings, `claude-runner` passes it to the session and to
the sessions that it requests. When none is set and you have not logged in, `claude-runner` on Linux and macOS refuses
the run before it starts the session, with status 2, and asks you to log in with `/login`. On Windows it starts the
run, because Claude Code may keep your login in the Windows Credential Manager, where `claude-runner` cannot look.

### The request server

A harness sandboxes its session's commands, and a sandboxed session cannot start a new session inside another sandbox.
Outside a run, a session therefore asks CAT's request server, `request-server` beside the launchers in the plugin's
`bin` folder, to start the new session. The request server runs outside the session's sandbox, and the install lets
it by adding the entries above. The entry matches only the request server's absolute path alone, with nothing in front
of it, such as a variable assignment or `env`, no arguments, and no pipe or redirection. The prompt optimizer's trials
go through the request server the same way.

Because a shell that runs the request server loads the shell startup files first, the install also keeps the session's
commands from writing them in your home folder (`.bashrc`, `.bash_profile`, `.bash_login`, `.bash_logout`, `.profile`,
`.zshrc`, `.zshenv`, `.zprofile` and `.zlogin`). A session that could write one could define a function under the name
of a wrapper word that the harness strips before it matches the request server, such as `nohup`, and the shell that
runs the next request-server command would load it and run the session's own code outside the sandbox. In Claude Code
the protection is `sandbox.filesystem.denyWrite` and `permissions.deny` `Edit(//<file>)` entries. In Codex it is
`read` entries in the permissions profile that `default_permissions` selects, which the installer offers to create when
your configuration selects none (see Codex permission profile above); Codex without such a profile gets none.
`uninstall` removes what `install` added.

A runner launcher that a session runs stays inside the session's sandbox. It packs the launch as a request into the
session's CAT session directory, waits there for the request server's response, and writes the transcript, the receipt
and any kept run itself, so every file that the launch reads is one the session may read, and every file that it
writes is one the session may write. A runner launcher that no harness runs, such as one that you run in a terminal,
needs no request server: it starts the new session itself, inside that harness's own sandbox, with exactly what the
session needs.

The request server treats every request as the session's and checks it before it starts anything:

- It identifies the session from its harness process and the session or thread ID that the harness puts in the command's
	environment (`CLAUDE_CODE_SESSION_ID` in Claude Code, `CODEX_THREAD_ID` in Codex), finds the session's transcript by
	that ID in the harness's home, and takes the session's project folder from the working directory that the transcript
	names; a launch without `--project` uses that folder, not the command's working directory.
- It reads the session's sandbox policy from that harness's own settings and command line, never from the request.
	Every path that the launch names for reading, such as the project, the input or the `--extra-permissions` file,
	must be one the session may read, and every path that it writes, such as the output, the conversation folder and the
	run root, one the session may write.
- A launch whose project folder is your home folder or contains it exits with status 2 and starts nothing, and so does
	`--verify-artifact` with an artifact that the session may not read.
- It removes the variables that make a program load other code, such as `LD_PRELOAD`, `LD_LIBRARY_PATH`, `DYLD_*`,
	`BASH_ENV` and the Java option variables.
- It starts the new session inside that harness's own sandbox, so that the new session can read, write and reach no
	more than the session that asked for it, and stops the new session's processes when the new session ends, or when
	the session that it serves ends.
- A launch that names an option that only CAT itself passes, such as `--policy-bound`, `--capacity-held`,
	`--child-needs`, `--git-name` or `--git-email`, exits with status 2 and starts nothing. A launcher always runs its
	own CAT artifact and the `claude` or `codex` command that your `PATH` finds: a launch that names `--artifact` exits
	with status 2 and starts nothing. A runner launcher or request server run by the harness whose plugin did not
	provide it starts nothing and names the plugin to use instead.
- It refuses, with status 2, a session that it cannot identify: one whose environment names no session or thread ID,
	whose transcript it finds nowhere or more than once, or whose transcript the session's commands could write. A
	Codex session on Codex's shared background server, which Codex uses unless it starts with `--no-daemon`, takes the
	latest sandbox policy that its rollout records, and is refused until it records one, so start the request server
	after such a session's first turn.
- When CAT cannot read a session's sandbox policy safely, such as a settings file that cannot be parsed, a settings
	file that the session's sandbox lets its commands write, or a harness option that CAT does not know, it starts
	nothing and says what failed and how to fix it. Fix it, then start a new session.
- When the sandbox the session already has does not allow the few writes outside the run folder and the model host that
	the requested session needs, or the writes and hosts that `--extra-permissions` names, it starts nothing and lists
	each missing path and host and the entry that grants it. A path inside a path that the sandbox denies writing is
	reported as that denial, because allowing the folder would not help. Add the entries, then start a new session,
	because a running session keeps the policy it started with.

A session that a runner starts can start sessions of its own only when its run was given `--session-requests`, and
`--request-depth` bounds the levels below it. Without `--session-requests`, its launchers exit with status 2 and say
that the run lets it request no sessions. Such a session's launchers ask the runner that started it to start the new
session outside the session's sandbox.

The request server refuses to serve when it finds that it runs inside the session's sandbox. It exits with status 2 and
a message that begins "CAT cannot start the request server:" and names each cause that it finds:

- A session that was already running when CAT was installed or updated has no exception for the request server. Start
	a new session; when Claude Code's user settings or Codex's rules folder hold no exception at all, install CAT again
	first.
- A command that does not match the exception, such as one that runs the request server by another path, behind a
	variable assignment or with arguments, stays sandboxed. Run the absolute path that the message names as a command
	of its own.
- Codex 0.160.0 runs a command that a rule allows outside its sandbox only when the session's filesystem permissions
	deny no reads ([openai/codex#38318](https://github.com/openai/codex/issues/38318)). When the permissions profile
	denies any read, the message names the profile and each denied path and pattern. Remove those entries and start a
	new Codex session, or start the session from a terminal.
- A session of a CAT run, such as a trial, that runs the request server stays sandboxed: a run's Codex session always
	denies some reads, so Codex keeps the server inside the sandbox whatever rule allows it, and the run's thread keeps
	no rollout that could name them unless its caller keeps the session's transcripts. The message says that no request
	server serves a run's session. Such a session requests sessions through a runner launcher or `request-session`,
	which the run's runner serves when its caller passes `--session-requests`.

A Codex session whose sandbox has network access off cannot start runner sessions, because a session that it started
could not reach its model either. CAT then starts no session for it. On Linux, `codex-runner` needs bubblewrap
(`bwrap`) on `PATH` to start a session that reads earlier sessions' results, such as a prompt optimizer's judge: it
lets that session read those results while it hides the rest of the project.

### Git inside a Codex runner session

The Codex runner gives each session an independent repository in its copied project. The session can stage, commit,
create branches and child worktrees, and merge. Child worktrees go inside the copied project. The repository's
`.git/config` and `.git/hooks` remain read-only, so per-command settings such as the commit identity go through Git's
`-c` command options. A retained project is session-produced evidence: treat its Git refs, objects, `info/` and
worktree metadata as untrusted when you run Git on it after the session ends.
