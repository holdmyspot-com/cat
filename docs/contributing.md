# Build and Test CAT

CAT requires JDK 27 and ShellCheck.

Run the complete Java, TestNG, and ShellCheck verification:

```bash
client/mvnw -q -Dmaven.build.cache.enabled=false -f client/pom.xml verify
```

Build the artifacts for the current platform:

```bash
client/mvnw -q -Dmaven.build.cache.enabled=false -f client/pom.xml package
```

## Install a locally built artifact

This developer workflow installs the artifact built in this checkout; end users should use the release bootstrap in
the [installation guide](installation.md).

### Choose a coding harness

#### Claude Code

```bash
client/common/distribution/target/runtime/claude/install/install-claude.sh client/common/distribution/target/runtime/claude
```

#### Codex

```bash
client/common/distribution/target/runtime/codex/install/install-codex.sh client/common/distribution/target/runtime/codex
```

### Test removal or an upgrade

Use the matching [bootstrap uninstaller](installation.md#uninstall) when testing removal. Re-run the same local
installer to test an upgrade.

Run the external launcher and installer boundary tests:

```bash
bats client/common/distribution/src/test/bats/bootstrap-common.bats \
  client/common/distribution/src/test/bats/bootstrap-claude.bats \
  client/common/distribution/src/test/bats/bootstrap-codex.bats \
  client/common/distribution/src/test/bats/prompt-optimizer.bats
```

Java tests call production classes directly. Bats is reserved for shell, installer, and generated-launcher boundaries
that Java tests cannot exercise directly.
