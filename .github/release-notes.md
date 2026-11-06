## Install CAT with one command

Run the command yourself in a terminal, or at a Claude Code prompt prefixed with `! `. Choose your harness explicitly.
Each entry script downloads only ${GITHUB_REF_NAME} assets and verifies their embedded SHA-256 digests before use.

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
has no CAT session launcher, so it cannot start runner sessions or prompt optimization trials.

CAT's runners need no sandbox exception: each session's CAT session launcher starts runner sessions inside their
harness's own sandbox, never wider than the session that asked for them. The command removes the runner exceptions that
earlier installation instructions asked you to add, and reports each removal.

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
