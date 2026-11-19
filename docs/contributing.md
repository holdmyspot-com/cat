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
operation (`--home` is an option of `install`, and it comes before the word `install`).

Run these mutating installation commands in a terminal outside the agent's sandbox, or through a documented authorized
route that runs this exact local artifact outside it. Before invoking them from an agent, establish that the route
covers the generated launcher's absolute path, arguments and intended harness home. Permission entries for an installed
launcher do not cover the checkout's generated launcher. If no supported route is available, leave installation pending
and report the required outside invocation. The installed launcher's release-update command installs release content;
use the local tree's installer for this developer workflow.

A writable plugin directory is insufficient evidence: a sandbox bind mount may allow file writes while preventing the
directory rename that Codex uses to back up an existing same-version cache. Do not use an installation attempt as a
probe of execution context.

Each local install replaces the files in the version folder, even when the folder already has the tree's version. Before
repeating an install, wait for sessions using that folder to finish or confirm they use independent copies of its files.

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

Use a different `--home` for a throwaway harness home; the home must be an absolute path. The examples use the terminal's
home directory; when targeting a particular agent installation, replace `~/.claude` or `~/.codex` with that installation's
actual absolute harness-home path.

After installation, locate the installed version folder inside the intended harness home and run its installer launcher
with `status --harness <harness> --json`, using the platform invocation above. Status derives its installation from the
launcher location and does not accept `--home`. Verify both the expected version and `consistent: true`. A partial
failure can leave the marketplace updated while the active cache remains unchanged. Preserve pending-permissions markers
and finish installation through the supported outside route; restarting alone or clearing a marker does not repair that
state.

The built plugin trees' launchers run without installation, from
`client/common/distribution/target/runtime/<harness>/bin/<launcher>`, for example
`client/common/distribution/target/runtime/claude/bin/get-history`.

### Test removal or an upgrade

Use the installed version folder's `bin/installer uninstall`, as described in the
[installation guide](installation.md#uninstall), when testing removal; it removes only that harness's installation. Run
the same `install --harness <harness>` command from a newly built plugin tree to test an upgrade: it installs the new
tree's version as a new version folder and keeps the previous one.

Run the external launcher and entry-script boundary tests, which also need jq:

```bash
bats client/common/distribution/src/test/bats/bootstrap-common.bats \
	client/common/distribution/src/test/bats/bootstrap-claude.bats \
	client/common/distribution/src/test/bats/bootstrap-codex.bats \
	client/common/distribution/src/test/bats/codex-plugin-launchers.bats \
	client/common/distribution/src/test/bats/jlink-launchers.bats \
	client/common/distribution/src/test/bats/installer-launcher.bats
```

On Windows, run them from Git Bash with `codex-plugin-launchers-windows.bats` and `installer-launcher-windows.bats` in
place of `codex-plugin-launchers.bats`, `jlink-launchers.bats` and `installer-launcher.bats`.

Java tests call production classes directly. Bats is reserved for shell, entry-script, and generated-launcher boundaries
that Java tests cannot exercise directly.
