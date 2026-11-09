# Install CAT

## For AI agents

If CAT's `update` skill is installed, use it. Otherwise do not download or run this release's files, because the
sandbox blocks the writes they need. Reply with this command, for the user to run in their own terminal or at a Claude
Code prompt after `! `. Write `codex` for Codex, and also `CLAUDE_CONFIG_DIR=<value>` or `CODEX_HOME=<value>` before
`sh` when your session sets it:

```bash
curl -fsSL https://github.com/holdmyspot-com/cat/releases/download/v1.0/cat.sh | sh -s -- --harness claude
```

- Add `HOME=<your session's HOME>` before `sh` only when it differs from the account's home folder, which
	`getent passwd "$(id -un)" | cut -d: -f6` prints on Linux and `dscl . -read "/Users/$(id -un)" NFSHomeDirectory` on
	macOS, or when you cannot tell.

On Windows, write no `HOME`, and put `$env:CODEX_HOME='<value>'; ` first when your session sets it:

```powershell
& ([scriptblock]::Create((irm https://github.com/holdmyspot-com/cat/releases/download/v1.0/cat.ps1))) -Harness claude
```

## Supported platforms

CAT v1.0 supports Claude Code and Codex on Linux x64, Linux ARM64, macOS x64, macOS ARM64, and Windows x64, but not
Windows ARM64. Claude Code must be version 2.1.277 or later, because earlier versions let a command chained after CAT's
installer run outside the sandbox. CAT's runners and prompt optimization need your harness's sandbox, which CAT
cannot rely on in native Windows: on Windows, install your harness and CAT inside WSL2 and run them there.

## Install CAT

Run the command once for each harness you use, in your own terminal or at a Claude Code prompt after `! `. It downloads
only that release's plugin for your harness and platform, checks it against a SHA-256 digest that the command contains,
and installs it. The release is the one in the command's address. The release notes publish the fingerprint of the key
that signs releases; compare it through a source you trust before you run the command. The harness's own command-line
program must be on your `PATH`. You copy no settings: the installer adds the permission entries that let your agent
install and check CAT. Then start or restart the harness.

On Linux and macOS:

```bash
curl -fsSL https://github.com/holdmyspot-com/cat/releases/download/v1.0/cat.sh | sh -s -- --harness claude
curl -fsSL https://github.com/holdmyspot-com/cat/releases/download/v1.0/cat.sh | sh -s -- --harness codex
```

On Windows, in PowerShell (Git Bash also runs the commands above for Claude Code, but Codex on Windows needs `cat.ps1`):

```powershell
& ([scriptblock]::Create((irm https://github.com/holdmyspot-com/cat/releases/download/v1.0/cat.ps1))) -Harness claude
& ([scriptblock]::Create((irm https://github.com/holdmyspot-com/cat/releases/download/v1.0/cat.ps1))) -Harness codex
```

Claude Code and Codex each get their own installation, which never changes the other's and may run a different CAT
release. If an install stops part-way, run the same command again; if it names a path it cannot write, run it outside
the agent's sandbox. It refuses a release older than the CAT data that an earlier installation left, and names the
release to install.

### Choosing where CAT installs

These variables choose the folders that the entry script and the installer use. Put an assignment after the pipe,
before `sh`, such as `... | CLAUDE_CONFIG_DIR=/path/to/claude-home sh -s -- --harness claude`. In PowerShell, set
`$env:CODEX_HOME = 'C:\path\to\codex-home'` first.

| Variable | What it changes | Default |
| --- | --- | --- |
| `CLAUDE_CONFIG_DIR` (Claude Code), `CODEX_HOME` (Codex) | The harness home, which holds CAT's plugin folders, plugin data and permission entries. An existing absolute folder. | `.claude` or `.codex` in your home folder |
| `HOME` (`USERPROFILE` on Windows) | Your home folder, which holds the default harness home and `.cat/locks`, where CAT's runners take capacity locks. An absolute path. | Your account's home folder |
| `TMPDIR` (`cat.sh`), `TEMP` (`cat.ps1`) | Where the script downloads and unpacks the plugin, and deletes it afterwards. | `/tmp`; `TEMP` must be set |

If your terminal already exports one of these variables, the command uses its value. On Windows, `cat.ps1` takes the
default harness home from your user profile folder, so `USERPROFILE` moves `.cat\locks` but not the harness home; set
the harness variable for that. The installation records its harness home, so a later change to these variables does not
redirect an update.

## Codex prompts

The Codex install may ask you two things.

**Permission profile.** Codex's sandbox must let sandboxed commands write `~/.cat/locks`, where each session that CAT's
runners start takes a capacity lock. If your `config.toml` defines the permission profile that Codex uses, the installer
adds that folder to it without asking; every other project that uses the profile can then write it too. If Codex uses a
built-in profile, or `sandbox_mode` is `workspace-write` or `read-only`, the installer asks `Allow this change? [y/N]`
before it creates a CAT profile that extends yours, adds only write access to the lock folder, and selects it. Answer
`y`; uninstalling CAT removes the profile and restores your selection. If you answer `n`, or the installer cannot ask,
it refuses with a built-in profile. With `sandbox_mode` it installs anyway, adds the lock folder to your writable
folders, and leaves your shell startup files writable by sandboxed commands. When your agent ran the install, the
refusal names the command that gives the permission: your agent asks you first, and your harness then asks you to
approve it.

**Hook trust.** Codex runs a plugin's hook only after it records that you trust it. The install records that trust for
CAT's hooks, so Codex runs them without asking. If Codex asks you to review a CAT hook anyway, trust it, or CAT's
runners and subagents lose that hook.

## Install another release or check CAT

After the first install, ask your agent in plain words. Its `update` and `uninstall` skills do the work:

- "Update CAT" or "install CAT v1.1" installs the latest release that your policy approves, or the one you name, in a
	new version folder. Restart the harness afterwards.
- "Go back to CAT v1.0" installs that earlier release, unless CAT's data structures changed since it.
- "Which CAT version is active?" reports the active version.

Your agent can install, update and check CAT without asking you, because those commands cannot name a download source
or change what the installation trusts. It cannot uninstall without your approval in the harness. The
[security model](security-model.md) lists what an agent can and cannot do.

Without an agent, run the installer in your active version folder:

```bash
~/.claude/plugins/cat-marketplace/plugins/cat/1.0+b0123456789ab/bin/installer install --harness claude
~/.codex/plugins/cache/cat/cat/1.0+b0123456789ab/bin/installer status --harness codex
```

On Windows, run `bin\installer.ps1` as `powershell.exe -NoProfile -NonInteractive -ExecutionPolicy Bypass -File <path>
<operation>`. Each installer accepts only its own harness.

| Command | Effect |
| --- | --- |
| `install --harness <h>` or `install --harness <h> latest` | Installs the latest release that your policy approves. |
| `install --harness <h> v1.1` | Installs release `v1.1`, which must satisfy the policy. |
| `status --harness <h>` | Reads the active version without changing anything; `--verbose` also lists the permission entries. |
| `uninstall --harness <h>` | Uninstalls CAT and keeps its plugin data. |
| `uninstall --harness <h> --delete-data` | Uninstalls CAT and deletes its plugin data. |

## Uninstall

Ask your agent to uninstall CAT in the harness you use. The `uninstall` skill first asks whether to also delete CAT's
plugin data, which is kept by default, and your harness then asks you to approve the command.

Uninstalling removes this harness's CAT installation, and no other harness's: the permission entries that the install
added (in Codex also the profile and hook trust it recorded), the plugin's registration, and the plugin files with every
version folder and the Codex custom agents that CAT owns. It never deletes unrelated settings, your own files, or your
rules in `~/.cat/rules`. CAT's plugin data, in `plugins/data/cat-cat/` in the harness home, stays unless you ask to
delete it. If the uninstall stops, run it again to continue. When the harness program that the install recorded is
gone, the harness may still list the plugin, and your agent tells you the commands that remove it. To install CAT
again, run the [install command](#install-cat); it reuses the plugin data that you kept.

## Let sessions run CAT's runners

CAT's `claude-runner` and `codex-runner` start an isolated Claude Code or Codex session with your login. They need no
configuration, but the session that starts them needs a login and a working sandbox:

- Log in to Claude Code with `/login`. A Claude session uses your login in place, and its commands cannot read the
	login file. When `CLAUDE_CODE_OAUTH_TOKEN`, `ANTHROPIC_API_KEY` or `ANTHROPIC_AUTH_TOKEN` is set, the runner uses
	that instead. On Linux and macOS it refuses to start with neither.
- A Codex session that a runner starts reaches its model, so Codex's sandbox needs network access on. On Linux,
	`codex-runner` also needs bubblewrap (`bwrap`) on your `PATH`.

### Claude Code sandbox requirements

Every Claude Code session that a runner starts must run inside Claude Code's sandbox. On macOS it needs no setup. On
Linux, install your distribution's `bubblewrap` and `socat` packages, for example
`sudo apt install bubblewrap socat` or `sudo dnf install bubblewrap socat`.

On Ubuntu 24.04 and later, AppArmor keeps bubblewrap from creating the user namespaces that the sandbox needs, so a
session that a runner starts stops with "cannot start the session's sandbox" or "bubblewrap could not start its
sandbox". Save this AppArmor profile as `/etc/apparmor.d/bwrap` on Ubuntu 24.04, or as `/etc/apparmor.d/zz-bwrap`
on Ubuntu 26.04, where the file must sort after Ubuntu's own `bwrap-userns-restrict` profile, then run
`sudo systemctl reload apparmor`:

```text
abi <abi/4.0>,
include <tunables/global>

profile bwrap /usr/bin/bwrap flags=(unconfined) {
  userns,
  include if exists <local/bwrap>
}
```
