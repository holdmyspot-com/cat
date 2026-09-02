# Install CAT

CAT v1.0 supports Claude Code and Codex on Linux x64, Linux ARM64, macOS x64, and macOS ARM64. On Windows, use the
Linux distribution through WSL.

## Install or upgrade

### 1. Select a release

Choose a numeric release tag such as `1.0`. Clone that exact tag before running the bootstrap script. The bootstrap
script fetches the same tag, verifies the release manifest and every listed file, and installs the selected target.

### 2. Choose a coding harness

<details>
<summary>Claude Code</summary>

```bash
git clone --depth 1 --branch 1.0 https://github.com/catsforbots/cat.git cat-1.0
CAT_BOOTSTRAP_RELEASE_TREE="$PWD/cat-1.0" \
  sh cat-1.0/claude/linux-x86_64/install/bootstrap-claude.sh
```

To install into a custom Claude Code configuration directory, set `CLAUDE_CONFIG_DIR`:

```bash
CLAUDE_CONFIG_DIR=/path/to/claude-home \
  CAT_BOOTSTRAP_RELEASE_TREE="$PWD/cat-1.0" sh cat-1.0/claude/linux-x86_64/install/bootstrap-claude.sh
```

</details>

<details>
<summary>Codex</summary>

```bash
git clone --depth 1 --branch 1.0 https://github.com/catsforbots/cat.git cat-1.0
CAT_BOOTSTRAP_RELEASE_TREE="$PWD/cat-1.0" \
  sh cat-1.0/codex/linux-x86_64/install/bootstrap-codex.sh
```

To install into a custom Codex home directory, set `CODEX_HOME`:

```bash
CODEX_HOME=/path/to/codex-home \
  CAT_BOOTSTRAP_RELEASE_TREE="$PWD/cat-1.0" sh cat-1.0/codex/linux-x86_64/install/bootstrap-codex.sh
```

</details>

### 3. Start or resume a session

Use the matching directory: `linux-x86_64`, `linux-arm64`, `macos-x86_64`, or `macos-arm64`. On Windows, use the Linux
directory from WSL. The installer verifies the manifest and installs CAT through the harness's plugin mechanism. Start
or resume a session after installation.

## Uninstall

### Choose a coding harness

#### Claude Code

```bash
CAT_BOOTSTRAP_RELEASE_TREE="$PWD/cat-1.0" \
  sh cat-1.0/claude/linux-x86_64/install/bootstrap-uninstall-claude.sh
```

#### Codex

```bash
CAT_BOOTSTRAP_RELEASE_TREE="$PWD/cat-1.0" \
  sh cat-1.0/codex/linux-x86_64/install/bootstrap-uninstall-codex.sh
```

The Codex uninstaller removes only the CAT-owned custom agents recorded in `~/.codex/agents`, then asks Codex to
remove the plugin. Claude Code removes CAT's bundled custom agents with the plugin.
