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
install. Consistency includes agreement between the selected plugin copies and their permission configuration. An
unfinished permission handoff is inconsistent even when those copies agree. Status supplies an update command only
when its installed launchers exist and configuration permits that command. Otherwise, repair through the installation
entry command in your terminal and restart the harness to load the repaired permissions.

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

Before publishing or activating a version, install prepares permissions for its installer while retaining the selected
version's permissions. Ownership is recorded before each permission configuration replacement. After installing and
checking the final permissions, install retires obsolete recorded entries and prepared rules. A failed attempt retains
prepared permissions for recovery, including permissions for earlier failed targets. Codex uses separate
`cat-installer-pending-<version>.rules` files during this handoff; Claude Code merges incoming entries into settings.
Uninstall removes recorded additions whose contents still match and preserves user changes.

Each harness's entries name that harness's own installer. Earlier versions also added exceptions
for the runner launchers' absolute paths; an install now removes those entries and leaves an entry that you added
yourself. `status --verbose` lists each recorded entry exactly as your harness configuration holds it, under the file
and the setting that hold it; with `--json`, the result's `permissions` field holds the same entries.

**Claude Code.** The entries go into `settings.json` in your Claude home, which keeps every other setting.

- `sandbox.excludedCommands`: each exclusion is one exact command: `install --harness claude`,
	`install --harness claude latest`, `status`, `status --harness claude`, `uninstall --harness claude` and
	`uninstall --harness claude --delete-data`, each also with `--json` at the end, which asks for the result as one
	line of JSON, except `install --harness claude` followed by a release tag or `--json`, which is a wildcard entry.
	A command on this list runs outside the sandbox.
- `permissions.allow`: install and status commands run without a prompt. `uninstall` keeps Claude Code's prompt.
- `sandbox.filesystem.denyWrite`: sandboxed commands may not write the other harness's marketplace folder, version
	folders and data directory.
- `sandbox.filesystem.allowWrite`: sandboxed commands may write `~/.cat/locks`.
- On Linux and macOS, the request server `request-server` beside the installer, as its absolute path with no
	arguments: in `sandbox.excludedCommands`, and as `Bash(<path>)` in `permissions.allow`. Sessions run it outside
	their sandbox and without a prompt. The runners' isolation section below describes the protection around it.

**Codex.** The install writes the CAT-owned file `rules/cat-installer.rules` in your Codex home. It allows
`install --harness codex` and `status`, and keeps Codex's prompt for `uninstall --harness codex`; each rule also
covers the command with `--json` at the end. On Linux and macOS it
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

An uninstall removes, in four steps that it records in `state/uninstall.json`: the permission entries that the install
added for this harness (in Codex also the profile selection, the profile and the hook hashes in `config.toml`); the
plugin's registration in the harness; the plugin files, with all version folders and every copy of the plugin that
the harness keeps (in Codex `plugins/cache/cat`), and the Codex custom agents that CAT owns, recorded in its ownership
manifest and still unchanged; and, in Claude Code, your CAT credential file `~/.cat/claude/.credentials.json`, with
`~/.cat/claude` when that leaves it empty, whether or not you delete the plugin data, because the long-lived token in it
has no use without CAT. Its result names the deleted file. A credential file that you saved under another home folder
with `--home` stays until `claude-credential remove --home <dir>` removes it. When `HOME` (`USERPROFILE` on Windows) is
not set, the uninstall cannot find your home folder, so it leaves your credential file and its result says so and how to
remove it. A file or folder that a step would delete and that is already gone is no failure. When `policy.json` is
missing, the running installer's version folder identifies the harness home. The uninstaller skips plugin
registration and settings cleanup, reports why it skipped them and how to finish, and removes the plugin files
and applicable CAT credential file. A data directory created solely to take the uninstall lock is removed.
It never deletes unrelated settings, agents that CAT does not own, or your own files, including `~/.cat/rules`.
When the harness program that the first install recorded no longer exists, uninstall cannot
unregister the plugin: it removes everything else and reports `uninstalled` with `"unregistered": false`.

## Runners' isolation

CAT's `claude-runner` and `codex-runner` start an isolated Claude Code or Codex session, which authenticates as
"Login" describes. Both plugins hold both launchers, so a session in either harness can run either harness.

### Login

A Claude session uses the credential of a source home. The source home is the folder that a launch names with
`--home <dir>`, an absolute path, or else your home folder. Its Claude folder is `<dir>/.claude` with `--home`, and
otherwise the folder that `CLAUDE_CONFIG_DIR` names when the environment that starts the runner sets it, or else
`.claude` in your home folder. The session takes only these credentials, and the runner chooses the one that it
receives in Claude Code's own order, which is the order of this list:

- the `ANTHROPIC_AUTH_TOKEN` entry of the `env` setting of that folder's `settings.json`;
- its `ANTHROPIC_API_KEY` entry;
- the `apiKeyHelper` setting of `settings.json`, which the session runs with the source home as its home folder;
- the `CLAUDE_CODE_OAUTH_TOKEN` entry of `env`, a token that `claude setup-token` printed;
- CAT's credential file, `.cat/claude/.credentials.json` in the source home, which holds an Anthropic API key or a
  token that `claude setup-token` printed, saved with `claude-credential set`. It takes the place where Claude Code
  ranks a `/login` login, last. `CLAUDE_CONFIG_DIR` does not move it.

`claude-credential`, a launcher of the Claude Code plugin, saves, reports and removes CAT's credential file. `set` reads
the token from a prompt that does not show what you paste, or from standard input, never from an argument, which
process listings and shell history would show, and refuses a token that starts with neither `sk-ant-api` nor
`sk-ant-oat`. It writes a new file, readable by you alone (mode `0600`, in a `.cat/claude` folder of mode `0700`, or an
access list of you alone on Windows), and renames it over the earlier file, so that a launch that reads the file
meanwhile reads the earlier token or the new one. It refuses a `.cat/claude` or `.credentials.json` that is a symbolic
link or an entry of the wrong kind. No report or message of the command holds the token. It refuses to run in a
nested session, so that a session cannot choose the credential that its parent's later launches bill. The sessions
that runners start cannot read the file, but your own Claude Code sessions, which CAT's installer does not restrict,
can, as they can read a token in your own `~/.claude/settings.json`.

A chosen token reaches the session's Claude Code only through a key file, `cat-api-key` in the session's own `.claude`
folder, readable by you alone, which the session's `apiKeyHelper` prints. The runner writes it before the session's
Claude Code starts and deletes it once Claude Code has exited. No token goes into the environment of the session's
Claude Code, so the commands and hooks that it runs inherit none. The `ANTHROPIC_BASE_URL` entry of `env`, which holds
no secret, goes into that environment.

A session never uses a login that `/login` stores, in `.credentials.json` or the macOS Keychain: the runner neither
shares, copies nor reads it, and the session's Claude Code keeps its own login store, the session's new and empty
`.claude` folder. Sessions therefore never refresh, write or replace your login. A source home whose only Claude
credential is such a login is refused: run `claude setup-token` and save the token that it prints, or save an
Anthropic API key, with `claude-credential set --home <source home>`, as the refusal says.

No hook, permission, plugin, MCP server or other setting of the source home reaches the session. The session takes
none of `ANTHROPIC_API_KEY`, `ANTHROPIC_AUTH_TOKEN`, `ANTHROPIC_BASE_URL`, `CLAUDE_CODE_OAUTH_TOKEN` and
`CLAUDE_SECURESTORAGE_CONFIG_DIR` from the environment that starts the runner, and a project or managed settings file
whose `env` sets one of them, or that sets `apiKeyHelper`, refuses the session. Every Claude session that a session
requests, directly or through a session of the other harness, uses the same source home; a launch inside a nested
session cannot name another one.

When the source home's `env` sets `ANTHROPIC_BASE_URL`, the session sends its requests to that gateway, and CAT cannot
tell from the gateway which kind of login it bills. The launch then names it with
`--gateway-login-class subscription` or `--gateway-login-class api_key`, and CAT records that class for the session.

Each source file that holds a secret must be a regular file, not a symbolic link, whose mode grants its group and other
users nothing, such as after `chmod go= <file>`. These files are CAT's credential file, a `settings.json` whose `env`
holds a token, and a file that the `apiKeyHelper` command names by an absolute path or a `~/` path. The session that
starts the runner must also be allowed to read each file that the runner reads in the source home, so that a launch
never uses a credential that its requester could not read. The commands and file tools of the session, and of every
session that it requests, cannot read the files that hold a secret, the session's key file, or the source folder's
`.credentials.json`, which holds your own `/login` login.

Before it starts the session, and with status 2, `claude-runner` refuses a source home that holds none of the
credentials above, a `/login` login alone included, a CAT credential file that is not `{"format_version": 1, "token":
"<token>"}` with a token that starts with `sk-ant-api` or `sk-ant-oat`, a relative or missing `--home`, a gateway
without `--gateway-login-class`, a source `env` that selects another provider or profile (`CLAUDE_CODE_USE_BEDROCK`,
`CLAUDE_CODE_USE_VERTEX`, `CLAUDE_CODE_USE_FOUNDRY` or `ANTHROPIC_PROFILE`), and a secret file that fails the checks
above. Its message names the file, the option or the setting, and never quotes a secret. The session's `session.json`,
its receipt and each response record the source home, the names of the parts that the session received and the login
class, never their values.

A Codex session also uses the authentication of a source home, which a launch chooses with `--home <dir>` and
`--gateway-login-class` as for a Claude session. Its Codex folder is `<dir>/.codex` with `--home`, and otherwise the
folder that `CODEX_HOME` names when the environment that starts the runner sets it, or else `.codex` in your home
folder. The session takes only these parts of that folder, and Codex chooses among them by its own rules:

- the `model_provider`, `openai_base_url` and `forced_login_method` settings of `config.toml` and the
  `[model_providers.<id>]` table of the provider that `model_provider` selects, which go into the session's own
  `config.toml`. When that table's `auth` table names a token command, the session's Codex runs it with the source home
  as its home folder, in its own process and outside the command sandbox;
- `auth.json`, when it holds an API key, as `codex login --with-api-key` writes it. The runner copies it into the
  session's `.codex` folder, readable by you alone, and removes the copy when the session's Codex app-server ends; a
  resume copies it again from the resuming launch's source home. Codex never refreshes an API key, so the copy stays
  valid.

The session's `.codex` folder is the `CODEX_HOME` of its Codex and of its commands. An `auth.json` that holds a ChatGPT
sign-in, as `codex login` writes it, is refused: Codex refreshes such a sign-in and replaces its refresh token, so a
session and your own Codex cannot share it, and a copy would stop one of them. Log in with an API key, or give the
selected provider a token command. No hook, MCP server, profile or other setting of the source's `config.toml` reaches
the session. The session's Codex receives neither `CODEX_API_KEY` nor `OPENAI_API_KEY` from the environment that
starts the runner, and a system or managed Codex configuration that sets `openai_base_url`, `forced_login_method`,
`model_provider` or `model_providers` refuses the session, because it would replace the source home's credential or
host. Every Codex session that a session requests, directly or through a session of the other harness, uses the same
source home.

When the selected provider's `base_url` or `openai_base_url` names a gateway, the launch names the kind of login that
it bills with `--gateway-login-class`, as for a Claude gateway. The secret files of a Codex source are `auth.json` and
a file that the token command names by an absolute path or a `~/` path, and they pass the same checks as those of a
Claude source. The commands and file tools of the session, and of every session that it requests, cannot read them or
the session's copy of `auth.json`.

A selected provider that takes its token from the variable that its `env_key` names, or that holds an inline
`experimental_bearer_token`, is also used. The runner writes the token into the session's key file, `cat-api-key` in the
session's own `.codex` folder, as it does for Claude, and gives the session's copy of the provider an `auth` token
command that prints that file, in place of `env_key` and `experimental_bearer_token`. Codex runs the token command
itself, outside the command sandbox, while the session's commands, hooks and file tools, and those of every session that
it requests, cannot read the key file. The variable never reaches the app-server's environment, which Codex gives to
every hook, and the token is not in the session's `config.toml`. The runner removes the key file when the session ends
and writes it again when the session resumes.

Before it starts the session, and with status 2, `codex-runner` refuses a source home whose Codex folder holds neither
an `auth.json` with an API key nor a token command of the selected provider, a `model_provider` without its table, a
gateway without `--gateway-login-class`, and a secret file that fails the checks. It also refuses a selected provider
whose `env_key` names a variable that the runner's environment does not set, and one that sets `env_http_headers` or
holds an `http_headers` secret, because a secret that is sent in headers cannot become a bearer token. It refuses
a provider's `gateway_oauth` sign-in without `credential_export`, and a `cli_auth_credentials_store` of `keyring` or
`auto` with no `auth.json`, because the login is then in a store that the runner cannot copy. Its message names the
file, the option or the setting, and never quotes a secret. The session's `session.json`, its receipt and each response
record the source home, the names of the copied parts and the login class, never their values.

### The request server

A harness sandboxes its session's commands, and a sandboxed session cannot start a new session inside another sandbox.
Outside a nested session, a session therefore asks CAT's request server, `request-server` beside the launchers in the
plugin's `bin` folder, to start the new session. The request server runs outside the session's sandbox, and the install
lets it by adding the entries above. The entry matches only the request server's absolute path alone, with nothing in
front of it, such as a variable assignment or `env`, no arguments, and no pipe or redirection. The prompt optimizer's
trials go through the request server the same way.

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
writes is one the session may write. A runner launcher runs only from a Claude Code or Codex session: one that no
harness session runs, such as one that you run in a terminal or a script, refuses with status 2 and says to run it from
a Claude Code or Codex session.

The request server treats every request as the session's and checks it before it starts anything:

- It identifies the session from its harness process and the session or thread ID that the harness puts in the command's
	environment (`CLAUDE_CODE_SESSION_ID` in Claude Code, `CODEX_THREAD_ID` in Codex), finds the session's transcript by
	that ID in the harness's home, and takes the session's project folder from the working directory that the transcript
	names; a launch without `--project` uses that folder, not the command's working directory.
- It reads the session's sandbox policy from that harness's own settings and command line, never from the request.
	Every path that the launch names for reading, such as the project, the input or the `--extra-permissions` file,
	must be one the session may read, and every path that it writes, such as the output, the conversation folder and the
	results folder, one the session may write.
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
- When the sandbox the session already has does not allow the few writes outside the session folder and the model host
	that the requested session needs, or the writes and hosts that `--extra-permissions` names, it starts nothing and
	lists each missing path and host and the entry that grants it. A path inside a path that the sandbox denies writing
	is reported as that denial, because allowing the folder would not help. Add the entries, then start a new session,
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
	new Codex session.
- A session of a CAT run, such as a trial, that runs the request server stays sandboxed: a run's Codex session always
	denies some reads, so Codex keeps the server inside the sandbox whatever rule allows it, and the run's thread keeps
	no rollout that could name them unless its caller keeps the session's transcripts. The message says that no request
	server serves a run's session. Such a session requests sessions through a runner launcher or `request-session`,
	which the nested session's runner serves when its caller passes `--session-requests`.

A Codex session whose sandbox has network access off cannot start runner sessions, because a session that it started
could not reach its model either. CAT then starts no session for it. On Linux, `codex-runner` needs bubblewrap
(`bwrap`) on `PATH` to start a session that reads earlier sessions' results, such as a prompt optimizer's judge: it
lets that session read those results while it hides the rest of the project.

### A Claude session whose sandbox does not start

Claude Code confines a session's commands in a sandbox on Linux, WSL2 included, and on macOS. When that sandbox cannot
start, Claude Code reports the failure as the result of the session's first Bash call, and then runs every later command
without the sandbox, even though CAT's settings forbid unsandboxed commands. CAT therefore checks twice, and each check
runs once per session, never once per command.

**Before the session starts**, `claude-runner` refuses the launch with status 2 when a known cause would stop the
sandbox. It creates no session and makes no model call, and its message names the check, the value that it found and the
fix. On Linux it checks that:

- the temporary folder of the Claude process, which is `/tmp` because CAT passes the process no `TMPDIR`, or a `TMPDIR`
	that a project or managed settings file's `env` sets, has a path of at most 72 bytes, because the sandbox puts
	sockets in it and a Unix socket's path has a length limit;
- `bwrap`, or the file that a settings file's `sandbox.bwrapPath` names, is executable, and `socat` is in a directory of
	the Claude process's `PATH`;
- `bwrap --unshare-user --dev-bind / / true` succeeds, so that unprivileged user namespaces work; and
- the temporary folder is a directory in which CAT can create and remove a file.

On macOS it checks that `/usr/bin/sandbox-exec` exists and runs `/usr/bin/true` under `(version 1)(allow default)`, and
that the temporary folder's path has at most 68 bytes.

**While the session runs**, the runner reads the result of the session's first Bash call. When it is Claude Code's report
that its sandbox failed to start, the runner stops the Claude process at once and sends it no further input. The report
has one of two forms, whatever the reason. The runner's sessions require their sandbox, and Claude Code reports
`Sandbox is required but failed to initialize: <reason>. Restart to retry. Running it outside the sandbox
(dangerouslyDisableSandbox) is not allowed either.` A session that does not require it gets `Sandbox is enabled but
failed to initialize: <reason>. Sandboxing is disabled for the rest of this session; restart to retry.` The turn ends as
an infrastructure failure, never as a pass or a fail outcome. Its reason quotes the report and says whether a Bash
result followed it, which shows whether a command ran after the report. This covers causes that the checks before the
start cannot see, such as a sandbox that does not finish starting in time on a slow machine. Fix the cause and start a
new session.

**Known limitation.** The runner can read the report only once Claude Code has printed it. Bash calls that the model
issued in the same message as the call that receives the report could run outside the sandbox, with your permissions,
before the runner stops the session. This window opens only when the sandbox fails for a cause that the checks before the
start did not catch. In a measured run with the stop, none of the other calls ran: the stream ended after the report, and
no file outside the project was written. Without the stop, two other calls of the same message ran unsandboxed. Treat the
measurement as an observation, not a guarantee.

### Git inside a Codex runner session

The Codex runner gives each session an independent repository in its copied project. The session can stage, commit,
create branches and child worktrees, and merge. Child worktrees go inside the copied project. The repository's
`.git/config` and `.git/hooks` remain read-only, so per-command settings such as the commit identity go through Git's
`-c` command options. A retained project is session-produced evidence: treat its Git refs, objects, `info/` and
worktree metadata as untrusted when you run Git on it after the session ends.
