# Get started with CAT

Install CAT once for each harness you use (Claude Code or Codex), check it, and keep it current. Run every command in
your own terminal, or at a Claude Code prompt after `! `. An AI agent follows the
[agent instructions](agent-installation.md) instead.

## Requirements

- Claude Code 2.1.277 or later (earlier versions run a command chained after CAT's installer outside the sandbox), or
  Codex.
- Linux x64 or ARM64, macOS x64 or ARM64, or Windows x64 (not Windows ARM64).
- The harness's program on your `PATH`: check with `claude --version` or `codex --version`.
- Linux and macOS: `curl`, `tar`, and `sha256sum` or `shasum`. Windows: PowerShell 5.1 or later.
- To use CAT's runners and prompt optimization on Windows, install your harness and CAT inside WSL2 and follow the
  Linux steps there. CAT cannot rely on the harness's sandbox in native Windows.

## 1. Install

The command downloads only that release's plugin for your harness and platform, checks it against a SHA-256 digest that
the command contains, and installs it. The release is the one in the command's address. Before you run it, compare the
signing-key fingerprint in the release notes through a source you trust.

Linux and macOS:

```bash
curl -fsSL https://github.com/holdmyspot-com/cat/releases/download/v1.0/cat.sh | sh -s -- --harness claude
curl -fsSL https://github.com/holdmyspot-com/cat/releases/download/v1.0/cat.sh | sh -s -- --harness codex
```

Windows, in PowerShell:

```powershell
& ([scriptblock]::Create((irm https://github.com/holdmyspot-com/cat/releases/download/v1.0/cat.ps1))) -Harness claude
& ([scriptblock]::Create((irm https://github.com/holdmyspot-com/cat/releases/download/v1.0/cat.ps1))) -Harness codex
```

Run only the line for each harness you use. Git Bash on Windows also runs the `cat.sh` command for Claude Code, but
Codex on Windows needs `cat.ps1`. Each harness gets its own installation, which never changes the other's and may run a
different CAT release. You copy no settings: the installer adds the entries described in
[What the installer adds, and why](#what-the-installer-adds-and-why).

- If the install stops part-way, run the same command again.
- If it names a path it cannot write, run it outside your agent's sandbox.
- It refuses a release older than the CAT data that an earlier installation left, and names the release to install.

### Install into another harness home

By default CAT installs into `~/.claude` or `~/.codex`. To install elsewhere, put an assignment before `sh` (Linux and
macOS) or a statement first (PowerShell). The installation records its harness home, so later changes to these
variables do not redirect an update.

```bash
curl -fsSL https://github.com/holdmyspot-com/cat/releases/download/v1.0/cat.sh | CLAUDE_CONFIG_DIR=/path/to/claude-home sh -s -- --harness claude
curl -fsSL https://github.com/holdmyspot-com/cat/releases/download/v1.0/cat.sh | CODEX_HOME=/path/to/codex-home sh -s -- --harness codex
```

```powershell
$env:CODEX_HOME = 'C:\path\to\codex-home'
& ([scriptblock]::Create((irm https://github.com/holdmyspot-com/cat/releases/download/v1.0/cat.ps1))) -Harness codex
```

| Variable | What it changes | Default |
| --- | --- | --- |
| `CLAUDE_CONFIG_DIR` (Claude Code), `CODEX_HOME` (Codex) | The harness home, an existing absolute folder. It holds CAT's plugin folders, plugin data and permission entries. | `.claude` or `.codex` in your home folder |
| `HOME` (`USERPROFILE` on Windows) | Your home folder, an absolute path. It holds the default harness home and `~/.cat/locks`. | Your account's home folder |
| `TMPDIR` (`cat.sh`), `TEMP` (`cat.ps1`) | Where the script downloads and unpacks the plugin, and deletes it afterwards. `TEMP` must be set. | `/tmp` |

A variable that your terminal already exports applies to the command. On Windows, `cat.ps1` takes the default harness
home from your user profile folder, so `USERPROFILE` moves `.cat\locks` but not the harness home; set `CLAUDE_CONFIG_DIR`
or `CODEX_HOME` for that.

## 2. Answer the Codex prompts (Codex only)

**Permission profile.** CAT's runners need Codex's sandbox to let commands write `~/.cat/locks`. What happens depends
on your `config.toml`:

| Your `config.toml` | What the installer does |
| --- | --- |
| Defines the permission profile that Codex uses | Adds `~/.cat/locks` to that profile without asking. Every other project that uses the profile can then write that folder too. |
| Uses a built-in profile (`:workspace` or `:read-only`) | Asks `Allow this change? [y/N]`. Answer `y`: it creates a CAT profile that extends yours, adds only write access to `~/.cat/locks`, and selects it. Uninstalling removes the profile and restores your selection. If you answer `n`, or it cannot ask, the install refuses. |
| Sets `sandbox_mode` to `workspace-write` or `read-only`, with no profile | Asks the same question. If you answer `n`, or it cannot ask, it installs anyway, adds `~/.cat/locks` to your writable folders, and leaves your shell startup files writable by sandboxed commands. |

When your agent ran the install, the refusal names the command that gives the permission: your agent asks you first,
and your harness then asks you to approve it.

**Hook trust.** Codex runs a plugin's hook only after it records that you trust it. The install records that trust for
CAT's hooks. If Codex asks you to review a CAT hook anyway, trust it, or CAT's runners and subagents lose that hook.

## 3. Restart the harness and check CAT

Start or restart Claude Code or Codex. Then either ask your agent "Which CAT version is active?", or run the
installer from a version folder. `status` reads the active version and changes nothing; add `--verbose` to also list
the entries that the install added.

Linux and macOS:

```bash
installer="$(ls -d "${CLAUDE_CONFIG_DIR:-$HOME/.claude}"/plugins/cat-marketplace/plugins/cat/*/bin/installer | head -n 1)"
"$installer" status --harness claude
```

```bash
installer="$(ls -d "${CODEX_HOME:-$HOME/.codex}"/plugins/cache/cat/cat/*/bin/installer | head -n 1)"
"$installer" status --harness codex
```

Windows, in PowerShell:

```powershell
$harnessHome = if ($env:CLAUDE_CONFIG_DIR) { $env:CLAUDE_CONFIG_DIR } else { "$HOME\.claude" }
$installer = (Get-ChildItem "$harnessHome\plugins\cat-marketplace\plugins\cat\*\bin\installer.ps1" | Select-Object -First 1).FullName
powershell.exe -NoProfile -NonInteractive -ExecutionPolicy Bypass -File $installer status --harness claude
```

```powershell
$harnessHome = if ($env:CODEX_HOME) { $env:CODEX_HOME } else { "$HOME\.codex" }
$installer = (Get-ChildItem "$harnessHome\plugins\cache\cat\cat\*\bin\installer.ps1" | Select-Object -First 1).FullName
powershell.exe -NoProfile -NonInteractive -ExecutionPolicy Bypass -File $installer status --harness codex
```

Each installer accepts only its own harness. Any version folder's installer reports the same active version.

## 4. Update or install another release

Ask your agent: "Update CAT" installs the latest release that your policy approves, "install CAT v1.1" installs the
release you name, and "Go back to CAT v1.0" installs that earlier release, unless CAT's data structures changed since
it. Each install goes into a new version folder; restart the harness afterwards.

Without an agent, use the `installer` variable from step 3 (on Windows, `powershell.exe -NoProfile -NonInteractive
-ExecutionPolicy Bypass -File $installer` in place of `"$installer"`):

```bash
"$installer" install --harness claude
"$installer" install --harness claude v1.1
```

The first command installs the latest release that your policy approves. The second installs release `v1.1`, which must
satisfy the policy. Your agent can install, update and check CAT without asking you, because those commands cannot name
a download source or change what the installation trusts. The [security model](security-model.md) lists what an agent
can and cannot do.

## Uninstall

Ask your agent to uninstall CAT in the harness you use. The `uninstall` skill first asks whether to also delete CAT's
plugin data, which is kept by default, and your harness then asks you to approve the command. Without an agent, use the
`installer` variable from step 3:

```bash
"$installer" uninstall --harness claude
"$installer" uninstall --harness claude --delete-data
```

The first command keeps CAT's plugin data; the second deletes it. Uninstalling removes only this harness's CAT
installation:

- the entries that the install added (in Codex also the profile and hook trust it recorded);
- the plugin's registration;
- the plugin files, with every version folder, and the Codex custom agents that CAT owns.

It never deletes unrelated settings, your own files, or your rules in `~/.cat/rules`. CAT's plugin data stays in
`plugins/data/cat-cat/` in the harness home unless you delete it. If the uninstall stops, run it again. When the
harness program that the install recorded is gone, the harness may still list the plugin, and the installer tells you
the commands that remove it. To install CAT again, repeat [step 1](#1-install); it reuses the plugin data that you kept.

## What the installer adds, and why

The installer adds entries to your harness's settings so that CAT's agents can work inside the harness's sandbox
without asking you for each step. Uninstalling removes them.

### Claude Code

These go in `settings.json` in the harness home.

- **Run `install` and `status` of CAT's installer outside the sandbox, without asking.** The sandbox blocks the writes
  an install needs, and each command would prompt you. With this entry your agent can update and check CAT on its own.
  `uninstall` also runs outside the sandbox but always asks you first.
- **Run CAT's `request-server` outside the sandbox, without asking (Linux and macOS).** CAT's runners start Claude Code
  and Codex sessions for your agent. A sandboxed command cannot start another sandboxed session itself, so the request
  server does it for the command. Without it, runners cannot start sessions.
- **Let sandboxed commands write `~/.cat/locks`.** Each session that a runner starts takes a capacity lock in that
  folder. Without write access, the runner cannot start a session.
- **Keep sandboxed commands from writing the other harness's CAT installation.** Otherwise a session in one harness
  could change the CAT files that the other harness runs.
- **Keep sessions from writing your shell startup files, such as `~/.bashrc` and `~/.zshrc` (Linux and macOS).** Your
  shell reads them whenever it starts a command, including CAT's installer and request server, which run outside the
  sandbox. A session that could edit them could plant code that later runs outside the sandbox.

### Codex

These go in `rules/cat-installer.rules` and `config.toml` in the harness home.

- **Run `install` and `status` of CAT's installer, and `request-server`, outside the sandbox without asking
  (`request-server` on Linux and macOS).** The reasons are the same as for Claude Code. `uninstall` asks you first.
- **Let sandboxed commands write `~/.cat/locks`**, through your permission profile or writable folders. The reason is
  the same as for Claude Code; [step 2](#2-answer-the-codex-prompts-codex-only) shows when the installer asks first.
- **Keep sandboxed commands from writing your shell startup files**, in the profile that the installer creates or
  extends. The reason is the same as for Claude Code. Without a profile that CAT can extend, the startup files stay
  writable.
- **Record that you trust CAT's hooks.** Without the record, Codex asks you to review each hook before it runs, and a
  hook you do not trust does not run.

## Let sessions run CAT's runners

CAT's `claude-runner` and `codex-runner` start an isolated Claude Code or Codex session with your login. They need no
configuration, but the session that starts them needs a login and a working sandbox:

- Log in to Claude Code with `/login`. A Claude session uses your login in place, and its commands cannot read the
  login file. When `CLAUDE_CODE_OAUTH_TOKEN`, `ANTHROPIC_API_KEY` or `ANTHROPIC_AUTH_TOKEN` is set, the runner uses that
  instead. On Linux and macOS it refuses to start with neither.
- A Codex session that a runner starts reaches its model, so Codex's sandbox needs network access on. On Linux,
  `codex-runner` also needs bubblewrap (`bwrap`) on your `PATH`.

### Claude Code sandbox requirements

Every Claude Code session that a runner starts must run inside Claude Code's sandbox. macOS needs no setup. On Linux,
install `bubblewrap` and `socat`:

```bash
sudo apt install bubblewrap socat
```

On Fedora and similar, use `sudo dnf install bubblewrap socat`.

On Ubuntu 24.04 and later, AppArmor keeps bubblewrap from creating the user namespaces that the sandbox needs. A
session that a runner starts then stops with "cannot start the session's sandbox" or "bubblewrap could not start its
sandbox". Save this AppArmor profile as `/etc/apparmor.d/bwrap` on Ubuntu 24.04, or as `/etc/apparmor.d/zz-bwrap` on
Ubuntu 26.04, where the file must sort after Ubuntu's own `bwrap-userns-restrict` profile, then reload AppArmor:

```bash
sudo tee /etc/apparmor.d/bwrap > /dev/null <<'EOF'
abi <abi/4.0>,
include <tunables/global>

profile bwrap /usr/bin/bwrap flags=(unconfined) {
  userns,
  include if exists <local/bwrap>
}
EOF
sudo systemctl reload apparmor
```
