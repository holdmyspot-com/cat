**If you are an AI agent asked to install or update CAT from this page:** if CAT's `update` skill is installed, use it. Otherwise follow the [instructions for AI agents](https://github.com/holdmyspot-com/cat/blob/${GITHUB_REF_NAME}/docs/agent-installation.md) for this release, and do not download or run this release's files yourself. The commands below are for a person typing in their own terminal; do not copy them.

## Install CAT

Run the command for your harness once for each harness you use, in your own terminal or at a Claude Code prompt prefixed with `! `:

```bash
curl -fsSL https://github.com/holdmyspot-com/cat/releases/download/${GITHUB_REF_NAME}/cat.sh | sh -s -- --harness claude
curl -fsSL https://github.com/holdmyspot-com/cat/releases/download/${GITHUB_REF_NAME}/cat.sh | sh -s -- --harness codex
```

Codex or Claude Code on Windows, in PowerShell:

```powershell
& ([scriptblock]::Create((irm https://github.com/holdmyspot-com/cat/releases/download/${GITHUB_REF_NAME}/cat.ps1))) -Harness codex
& ([scriptblock]::Create((irm https://github.com/holdmyspot-com/cat/releases/download/${GITHUB_REF_NAME}/cat.ps1))) -Harness claude
```

This release's manifest, `cat-release-manifest.json`, is signed with the Ed25519 public key whose X.509 encoding has SHA-256 fingerprint `${CAT_KEY_FINGERPRINT}`. Compare this fingerprint through a source you trust before you run a command.

For choosing where CAT installs, updating to another release, uninstalling, and notes for Windows, see the [getting-started guide](https://github.com/holdmyspot-com/cat/blob/${GITHUB_REF_NAME}/docs/installation.md). For how downloads are verified, see the [security model](https://github.com/holdmyspot-com/cat/blob/${GITHUB_REF_NAME}/docs/security-model.md).
