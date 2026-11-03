## Install, update or uninstall

Ask your agent in Claude Code or Codex to install CAT from https://github.com/holdmyspot-com/cat/releases, or have it
run one of these commands. Each downloads only this release's files and checks each one against the SHA-256 digest
that it holds before it runs anything from it. Inside an agent session, `--harness` may be left out.

Install or update to ${GITHUB_REF_NAME}, with CAT's updater:

```bash
curl -fsSL https://github.com/holdmyspot-com/cat/releases/download/${GITHUB_REF_NAME}/cat.sh | sh -s -- --harness claude
curl -fsSL https://github.com/holdmyspot-com/cat/releases/download/${GITHUB_REF_NAME}/cat.sh | sh -s -- --harness codex
```

Install or update only CAT's updater:

```bash
curl -fsSL https://github.com/holdmyspot-com/cat/releases/download/${GITHUB_REF_NAME}/cat.sh | sh -s -- --harness claude --updater-only
curl -fsSL https://github.com/holdmyspot-com/cat/releases/download/${GITHUB_REF_NAME}/cat.sh | sh -s -- --harness codex --updater-only
```

Uninstall, keeping CAT's updater (in Codex, add `--delete-data` to delete CAT's data too):

```bash
curl -fsSL https://github.com/holdmyspot-com/cat/releases/download/${GITHUB_REF_NAME}/cat.sh | sh -s -- --harness claude --uninstall
curl -fsSL https://github.com/holdmyspot-com/cat/releases/download/${GITHUB_REF_NAME}/cat.sh | sh -s -- --harness codex --uninstall
```

Codex on Windows, in PowerShell, install or update to ${GITHUB_REF_NAME}:

```powershell
& ([scriptblock]::Create((irm https://raw.githubusercontent.com/holdmyspot-com/cat/${GITHUB_REF_NAME}/client/codex/lifecycle/src/main/resources/install/bootstrap-codex.ps1))) -Version ${GITHUB_REF_NAME}
```

Codex on Windows, in PowerShell, uninstall (add `-DeleteData` to delete CAT's data too):

```powershell
& ([scriptblock]::Create((irm https://raw.githubusercontent.com/holdmyspot-com/cat/${GITHUB_REF_NAME}/client/codex/lifecycle/src/main/resources/install/bootstrap-codex.ps1))) -Uninstall
```

If the command reports that it cannot write a path, it changed nothing: run the command that it prints outside the
agent's sandbox, such as in a terminal or, in Claude Code, at the prompt prefixed with `! `.

The release's signed manifest, `cat-release-manifest.json`, is signed with the Ed25519 key whose public key has the
SHA-256 fingerprint `${CAT_KEY_FINGERPRINT}`.
