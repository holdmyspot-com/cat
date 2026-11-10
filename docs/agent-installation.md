# Install CAT: instructions for AI agents

If you are a person, follow the [getting-started guide](installation.md) instead.

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
