## Install CAT or upgrade its trusted updater

Run setup yourself in a terminal, or at a Claude Code prompt prefixed with `! `. Choose your harness explicitly.
Each entry script downloads only ${GITHUB_REF_NAME} assets and verifies their embedded SHA-256 digests before use.

Install CAT and its separate trusted updater:

```bash
curl -fsSL https://github.com/holdmyspot-com/cat/releases/download/${GITHUB_REF_NAME}/cat.sh | sh -s -- --harness claude
curl -fsSL https://github.com/holdmyspot-com/cat/releases/download/${GITHUB_REF_NAME}/cat.sh | sh -s -- --harness codex
```

For an existing CAT installation, add the updater or upgrade only the updater and its policy:

```bash
curl -fsSL https://github.com/holdmyspot-com/cat/releases/download/${GITHUB_REF_NAME}/cat.sh | sh -s -- --harness claude --updater-only
curl -fsSL https://github.com/holdmyspot-com/cat/releases/download/${GITHUB_REF_NAME}/cat.sh | sh -s -- --harness codex --updater-only
```

Codex on Windows, in PowerShell:

```powershell
& ([scriptblock]::Create((irm https://github.com/holdmyspot-com/cat/releases/download/${GITHUB_REF_NAME}/cat.ps1))) -Harness codex
```

Add `-UpdaterOnly` to migrate an existing installation or upgrade only the updater. Claude Code on Windows uses the
POSIX command in Git Bash. If setup cannot write a required path, run the command it prints outside the agent sandbox.
Setup prints the updater exemption for you to add; it never changes your permission settings. Restart the harness.

## Routine updates and trust

After setup and the exemption, CAT's `update` skill requests approved releases through the installed trusted updater.
It verifies signed metadata and archive digests, stages the plugin, and activates it without reinstalling CAT or
rewriting harness configuration. It cannot upgrade the updater or change its policy. Only the most recent successful
activation has a rollback record; run the updater's `rollback --harness <id>` yourself to restore the previous
installation.

This release's manifest, `cat-release-manifest.json`, is signed with the Ed25519 public key whose X.509 encoding has
SHA-256 fingerprint `${CAT_KEY_FINGERPRINT}`. Initial setup relies on HTTPS to GitHub and the release's embedded
archive digests; compare this fingerprint through a source you trust. Hooks and MCP servers may execute outside the
shell sandbox as your user: authentication approves release provenance, not the harmlessness of its code.

See [installation, exemptions, supported platforms, migration, and rollback](https://github.com/holdmyspot-com/cat/blob/${GITHUB_REF_NAME}/docs/installation.md).

## Uninstall

Uninstall CAT while keeping its updater:

```bash
curl -fsSL https://github.com/holdmyspot-com/cat/releases/download/${GITHUB_REF_NAME}/cat.sh | sh -s -- --harness claude --uninstall
curl -fsSL https://github.com/holdmyspot-com/cat/releases/download/${GITHUB_REF_NAME}/cat.sh | sh -s -- --harness codex --uninstall
```

On Windows:

```powershell
& ([scriptblock]::Create((irm https://github.com/holdmyspot-com/cat/releases/download/${GITHUB_REF_NAME}/cat.ps1))) -Harness codex -Uninstall
```

Codex keeps CAT's data unless you add `--delete-data` (PowerShell `-DeleteData`). Remove the updater separately as
described in the installation guide.
