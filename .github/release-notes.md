**If you are an AI agent asked to install or update CAT from this page:** if CAT's `update` skill is installed, use
it. Otherwise do not download, verify, extract or run `cat.sh`, `cat.ps1`, `SHA256SUMS.txt` or the plugin archives:
the entry script verifies its own downloads, and the agent sandbox blocks the writes it needs. Reply at once with the
entry command below for the user's harness, for the user to run in a terminal outside the sandbox, or in Claude Code
at the prompt prefixed with `! `. The commands further down this page are for a person typing in their own terminal;
do not copy them. Write this command, with `--harness codex` for Codex, and no variable assignments except the ones
below:

```bash
curl -fsSL https://github.com/holdmyspot-com/cat/releases/download/${GITHUB_REF_NAME}/cat.sh | sh -s -- --harness claude
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

## Install CAT with one command

These commands are for a person typing in their own terminal, or at a Claude Code prompt prefixed with `! `; an agent
follows the paragraph at the top of this page instead. Choose your harness explicitly. Each entry script downloads only
${GITHUB_REF_NAME} assets and verifies their embedded SHA-256 digests before use. To install somewhere other than the
default, see [Choosing where CAT installs](#choosing-where-cat-installs).

```bash
curl -fsSL https://github.com/holdmyspot-com/cat/releases/download/${GITHUB_REF_NAME}/cat.sh | sh -s -- --harness claude
curl -fsSL https://github.com/holdmyspot-com/cat/releases/download/${GITHUB_REF_NAME}/cat.sh | sh -s -- --harness codex
```

Codex or Claude Code on Windows, in PowerShell:

```powershell
& ([scriptblock]::Create((irm https://github.com/holdmyspot-com/cat/releases/download/${GITHUB_REF_NAME}/cat.ps1))) -Harness codex
```

The command downloads only this release's plugin archive for your harness and platform, and installs it. The archive is
the CAT plugin together with its own installer and Java runtime, so there is nothing else to download and nothing to
copy into your settings. The command also adds the permission entries that let your agent install and check CAT
afterwards, and that make the harness ask you before it uninstalls CAT. Run the command once for each harness you use.
Each harness gets its own installation of CAT, and the two may run different CAT releases. Claude Code on Windows can
also use the POSIX command in Git Bash; Codex on Windows needs the PowerShell command. CAT's runners and prompt
optimization are not available on native Windows; to use them, run your harness and CAT inside WSL2. If the command
cannot write a required path, run it outside the agent sandbox. Restart the harness: a session that was already running
has none of the sandbox exceptions that the install adds for CAT's runner launchers, so it cannot start runner sessions
or prompt optimization trials.

## Choosing where CAT installs

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
curl -fsSL https://github.com/holdmyspot-com/cat/releases/download/${GITHUB_REF_NAME}/cat.sh | CLAUDE_CONFIG_DIR=/path/to/claude-home sh -s -- --harness claude
curl -fsSL https://github.com/holdmyspot-com/cat/releases/download/${GITHUB_REF_NAME}/cat.sh | CODEX_HOME=/path/to/codex-home sh -s -- --harness codex
curl -fsSL https://github.com/holdmyspot-com/cat/releases/download/${GITHUB_REF_NAME}/cat.sh | HOME=/path/to/home sh -s -- --harness claude
curl -fsSL https://github.com/holdmyspot-com/cat/releases/download/${GITHUB_REF_NAME}/cat.sh | TMPDIR=/path/to/temp sh -s -- --harness claude
```

```powershell
$env:CLAUDE_CONFIG_DIR = 'C:\path\to\claude-home'; & ([scriptblock]::Create((irm https://github.com/holdmyspot-com/cat/releases/download/${GITHUB_REF_NAME}/cat.ps1))) -Harness claude
$env:CODEX_HOME = 'C:\path\to\codex-home'; & ([scriptblock]::Create((irm https://github.com/holdmyspot-com/cat/releases/download/${GITHUB_REF_NAME}/cat.ps1))) -Harness codex
$env:USERPROFILE = 'C:\path\to\home'; & ([scriptblock]::Create((irm https://github.com/holdmyspot-com/cat/releases/download/${GITHUB_REF_NAME}/cat.ps1))) -Harness codex
$env:TEMP = 'C:\path\to\temp'; & ([scriptblock]::Create((irm https://github.com/holdmyspot-com/cat/releases/download/${GITHUB_REF_NAME}/cat.ps1))) -Harness codex
```

If your terminal already exports one of these variables, the command uses its value. On Windows, `cat.ps1` takes the
default harness home from your user profile folder, not from `USERPROFILE`, so setting `USERPROFILE` moves
`.cat\locks` but not the harness home; set the harness variable to move that. In Git Bash, `cat.sh` reads `HOME` for the default
harness home and the installer reads `USERPROFILE`. The installation records its harness home, so a later change to
these variables does not redirect an update; the full list of what the installer writes is in the
[installation guide](https://github.com/holdmyspot-com/cat/blob/${GITHUB_REF_NAME}/docs/installation.md#installed-locations).

The command lets your sessions run CAT's two runner launchers outside their sandbox, by their absolute paths only, and
reports those entries as running outside the sandbox. It also makes your shell startup files, such as `.bashrc` and
`.zshrc`, read-only to the harness's sandboxed commands. A runner launcher identifies the session that runs it, checks
every path that the launch names against that session's sandbox policy, and starts the runner session inside its
harness's own sandbox, never wider than the session that asked for it. In Codex, a permissions profile that denies any
read keeps the launchers sandboxed (openai/codex#38318), and the launchers then say which entries to remove.

## Install another release, check CAT and uninstall with your agent

After that, ask your agent. CAT's `update` skill runs the installed plugin's installer: `install --harness <h>` installs
the latest release that your installation's policy approves, or the release you name, and `status` reports the active
version. Each CAT version gets its own folder in the harness, and the installation keeps the active version's folder and
the one it replaced. Naming an older release goes back to it, unless CAT's data structures changed since that release.
The installer verifies signed metadata and archive digests against its existing policy, and never changes the policy's
keys, release source or version limits.

This release's manifest, `cat-release-manifest.json`, is signed with the Ed25519 public key whose X.509 encoding has
SHA-256 fingerprint `${CAT_KEY_FINGERPRINT}`. The entry command relies on HTTPS to GitHub and the release's embedded
archive digests; compare this fingerprint through a source you trust. Hooks and MCP servers may execute outside the
shell sandbox as your user: authentication approves release provenance, not the harmlessness of its code.

See [installation, permissions, and supported platforms](https://github.com/holdmyspot-com/cat/blob/${GITHUB_REF_NAME}/docs/installation.md).

## Uninstall

Ask your agent to uninstall CAT in the harness you use. It asks whether to also delete CAT's plugin data, then removes
that harness's CAT installation: the plugin with all its version folders, its registration in the harness, the Codex
agents that CAT owns, and the permission entries. The other harness's installation is not touched. CAT's plugin data,
which holds the installer's policy, state and kept releases, is kept unless you ask to delete it, and your own files,
including `~/.cat/rules`, are never deleted. Installing CAT again after an uninstall uses the one command above.
