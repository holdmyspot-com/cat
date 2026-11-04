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

The one command installs everything: the Java runtime that CAT keeps for itself, the trusted updater, the CAT plugin
for your harness, and the permission entries that let your agent install, update and uninstall CAT afterwards. There
are no settings to copy. Claude Code on Windows can also use the POSIX command in Git Bash. If the command cannot write
a required path, run it outside the agent sandbox. Restart the harness.

## Install, update and uninstall with your agent

After that, ask your agent. CAT's `update` skill installs, updates and rolls back CAT through the installed trusted
updater, and installs the plugin again when you removed it by other means. The updater verifies signed metadata and
archive digests, and refuses a release whose Java requirement the installed runtime does not meet; in that case, run
the newer release's command above again. It never changes the updater or its policy. Only the most recent successful
activation has a rollback record, and the harness asks you to approve a rollback.

This release's manifest, `cat-release-manifest.json`, is signed with the Ed25519 public key whose X.509 encoding has
SHA-256 fingerprint `${CAT_KEY_FINGERPRINT}`. Initial setup relies on HTTPS to GitHub and the release's embedded
archive digests; compare this fingerprint through a source you trust. Hooks and MCP servers may execute outside the
shell sandbox as your user: authentication approves release provenance, not the harmlessness of its code.

See [installation, permissions, supported platforms, and rollback](https://github.com/holdmyspot-com/cat/blob/${GITHUB_REF_NAME}/docs/installation.md).

## Uninstall

Ask your agent to uninstall CAT. It asks whether to also delete CAT's plugin data, then removes all of CAT: the plugin
and its registration in every harness, the Codex agents that CAT owns, the trusted updater, the Java runtime and the
permission entries. CAT's plugin data is kept unless you ask to delete it, and your own files, including
`~/.cat/rules`, are never deleted. Installing CAT again after an uninstall is a new installation: run the one command
above again.
