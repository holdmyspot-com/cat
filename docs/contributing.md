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

This developer workflow installs the artifacts built in this checkout; end users should use the release command in
the [installation guide](installation.md).

The package build writes each harness's plugin tree to `client/common/distribution/target/runtime/claude/` and
`client/common/distribution/target/runtime/codex/`. A plugin tree is complete: its `bin/installer` is the installer, its
`bin/runtime/` is the Java runtime, and its `bin/` holds the launchers. The version that the build stamps into
`bin/VERSION` is `1.0+b<commit>`, where `<commit>` is the first 12 hexadecimal digits of the checkout's `HEAD`.

Run a plugin tree's installer with `install --harness <harness>`. It installs the tree's own version, so it takes the
local plugin tree in place of a signed release, and it needs no `--plugin` argument. Each harness has its own
installation, so run it once for each harness you test. Install copies the tree to the harness's version folder and
registers it through the harness's program, which must be on your `PATH`. It writes the policy in the harness's CAT data
directory, with the release key from the tree's `bin/conf/installer/default-policy.json`, and adds the permission
entries that name the installed version folder's `bin/installer`. Pass `--home` with the harness home before the
operation (`--home` is an option of `install`, and it comes before the word `install`):

```bash
client/common/distribution/target/runtime/claude/bin/installer --home ~/.claude install --harness claude
client/common/distribution/target/runtime/codex/bin/installer --home ~/.codex install --harness codex
```

On Windows, the installer's launcher is
`client\common\distribution\target\runtime\<harness>\bin\installer.ps1`. Run it through PowerShell with the same
arguments:

```powershell
powershell.exe -NoProfile -NonInteractive -ExecutionPolicy Bypass -File "<absolute path to installer.ps1>" `
	--home "<absolute path to the harness home>" install --harness <harness>
```

Use a different `--home` for a throwaway harness home; the home must be an absolute path.

The built plugin trees' launchers run without installation, from
`client/common/distribution/target/runtime/<harness>/bin/<launcher>`, for example
`client/common/distribution/target/runtime/claude/bin/get-history`.

### Test removal or an upgrade

Use the installed version folder's `bin/installer uninstall`, as described in the
[installation guide](installation.md#uninstall), when testing removal; it removes only that harness's installation. Run
the same `install --harness <harness>` command from a newly built plugin tree to test an upgrade: it installs the new
tree's version as a new version folder and keeps the previous one.

Run the external launcher and entry-script boundary tests:

```bash
bats client/common/distribution/src/test/bats/bootstrap-common.bats \
	client/common/distribution/src/test/bats/bootstrap-claude.bats \
	client/common/distribution/src/test/bats/bootstrap-codex.bats \
	client/common/distribution/src/test/bats/codex-plugin-launchers.bats \
	client/common/distribution/src/test/bats/jlink-launchers.bats \
	client/common/distribution/src/test/bats/installer-launcher.bats
```

Java tests call production classes directly. Bats is reserved for shell, entry-script, and generated-launcher boundaries
that Java tests cannot exercise directly.
