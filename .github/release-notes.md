## Install, update or uninstall

Ask your agent in Claude Code or Codex to install CAT from https://github.com/holdmyspot-com/cat/releases, or have it
run one of these commands. Each detects whether it runs in Claude Code or Codex; in a terminal, add
`--harness claude` or `--harness codex` after `sh -s --`.

Install or update to ${GITHUB_REF_NAME}:

```bash
curl -fsSL https://raw.githubusercontent.com/holdmyspot-com/cat/${GITHUB_REF_NAME}/client/common/lifecycle/src/main/resources/install/cat.sh | sh -s -- ${GITHUB_REF_NAME}
```

Uninstall (in Codex, add `--delete-data` to delete CAT's data too):

```bash
curl -fsSL https://raw.githubusercontent.com/holdmyspot-com/cat/${GITHUB_REF_NAME}/client/common/lifecycle/src/main/resources/install/cat.sh | sh -s -- --uninstall
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
