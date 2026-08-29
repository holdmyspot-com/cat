# Install CAT

CAT v1.0 supports Claude Code and Codex on Linux x64, Linux ARM64, macOS x64, and macOS ARM64. On Windows, use the
Linux distribution through WSL.

## Install or upgrade

### 1. Select a release

Each bootstrap command installs the latest release by default. Append a release tag such as `v1.0` after `sh -s` to
select a specific version. Re-run the same command to upgrade CAT to the latest release, or append a release tag to
install that specific version.

### 2. Choose a coding harness

<details>
<summary>Claude Code</summary>

```bash
curl -fsSL \
  https://raw.githubusercontent.com/cowwoc/cat/main/client/claude/lifecycle/src/main/resources/install/bootstrap-claude.sh | sh
```

To install into a custom Claude Code configuration directory, set `CLAUDE_CONFIG_DIR`:

```bash
curl -fsSL \
  https://raw.githubusercontent.com/cowwoc/cat/main/client/claude/lifecycle/src/main/resources/install/bootstrap-claude.sh \
  | CLAUDE_CONFIG_DIR=/path/to/claude-home sh
```

</details>

<details>
<summary>Codex</summary>

```bash
curl -fsSL \
  https://raw.githubusercontent.com/cowwoc/cat/main/client/codex/lifecycle/src/main/resources/install/bootstrap-codex.sh | sh
```

To install into a custom Codex home directory, set `CODEX_HOME`:

```bash
curl -fsSL \
  https://raw.githubusercontent.com/cowwoc/cat/main/client/codex/lifecycle/src/main/resources/install/bootstrap-codex.sh \
  | CODEX_HOME=/path/to/codex-home sh
```

</details>

### 3. Start or resume a session

The installer selects the matching platform archive, verifies the release checksum, and installs CAT through the
harness's plugin mechanism. Start or resume a session after installation.

## Uninstall

### Choose a coding harness

#### Claude Code

```bash
curl -fsSL \
  "https://raw.githubusercontent.com/cowwoc/cat/main/client/claude/lifecycle/src/main/resources/install/"\
  "bootstrap-uninstall-claude.sh" | sh
```

#### Codex

```bash
curl -fsSL \
  "https://raw.githubusercontent.com/cowwoc/cat/main/client/codex/lifecycle/src/main/resources/install/"\
  "bootstrap-uninstall-codex.sh" | sh
```

The Codex uninstaller removes only the CAT-owned custom agents recorded in `~/.codex/agents`, then asks Codex to
remove the plugin. Claude Code removes CAT's bundled custom agents with the plugin.
