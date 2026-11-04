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

The one command installs everything for that harness: the Java runtime that CAT keeps for itself, the trusted updater,
the CAT plugin, and the permission entries that let your agent install, update and uninstall CAT afterwards. There
are no settings to copy. Run the command once for each harness you use. Each harness gets its own separate installation,
with its own Java runtime and updater, so using both puts two Java runtimes on disk and lets the harnesses run different
CAT releases. Claude Code on Windows can also use the POSIX command in Git Bash. If the command cannot write a required
path, run it outside the agent sandbox. Restart the harness.

## Install, update and uninstall with your agent

After that, ask your agent. CAT's `update` skill installs, updates and rolls back CAT through the installed trusted
updater, and installs the plugin again when you removed it by other means. An update moves the installation's Java
runtime, updater and plugin to the new release together, and the previous bundle stays until the next successful update;
a rollback restores all three together, and the harness asks you to approve it. The updater verifies signed metadata and
archive digests against its existing policy, and refuses a release whose Java requirement the runtime that would run it
does not meet; in that case, run the newer release's command above again. An update never changes the policy's keys,
release source or version limits.

This release's manifest, `cat-release-manifest.json`, is signed with the Ed25519 public key whose X.509 encoding has
SHA-256 fingerprint `${CAT_KEY_FINGERPRINT}`. Initial setup relies on HTTPS to GitHub and the release's embedded
archive digests; compare this fingerprint through a source you trust. Hooks and MCP servers may execute outside the
shell sandbox as your user: authentication approves release provenance, not the harmlessness of its code.

See [installation, permissions, supported platforms, and rollback](https://github.com/holdmyspot-com/cat/blob/${GITHUB_REF_NAME}/docs/installation.md).

## Uninstall

Ask your agent to uninstall CAT in the harness you use. It asks whether to also delete CAT's plugin data, then removes
that harness's whole CAT installation: the plugin and its registration, the Codex agents that CAT owns, the trusted
updater, the Java runtime, the previous bundle and the permission entries. It also deletes the runner artifacts in
`~/.cat/bundles`; the other harness's installation is otherwise not touched and downloads them again when it next needs
them. CAT's plugin data is kept unless you ask to delete it, and your own files, including `~/.cat/rules`, are never
deleted. Installing CAT again after an uninstall is a new installation: run the one command above again.
