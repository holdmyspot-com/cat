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

The package build writes the updater to `client/common/distribution/target/runtime/updater/`, and each harness's plugin
to `client/common/distribution/target/runtime/claude/` and `.../codex/`. The updater directory holds a Java runtime in
`runtime/` and the updater application in `<V>/`, where `<V>` is the CAT version declared in `client/pom.xml`, currently
`1.0`; the directory's name shows it.

Install through the updater's `setup`, which takes the local plugin tree in place of a signed release. Each harness gets
its own installation, so run `setup` once for each harness you test. Setup copies the Java runtime and the updater into
that harness's installation directory, `${XDG_DATA_HOME:-$HOME/.local/share}/cat-updater/<harness>` (on Windows,
`%LOCALAPPDATA%\cat-updater\<harness>`), and records the policy in
`${XDG_CONFIG_HOME:-$HOME/.config}/cat-updater/<harness>/policy.json` (on Windows, `policy.json` in that directory).
Your authority as the person running the command replaces the release signature; setup still validates the tree, checks
the installation's Java runtime against the plugin's Java requirement, and binds the plugin to that runtime. Pass the
harness home, and the absolute path to the plugin tree:

```bash
client/common/distribution/target/runtime/updater/<V>/bin/cat-updater setup --harness claude --home ~/.claude \
	--plugin "$PWD/client/common/distribution/target/runtime/claude"
client/common/distribution/target/runtime/updater/<V>/bin/cat-updater setup --harness codex --home ~/.codex \
	--plugin "$PWD/client/common/distribution/target/runtime/codex"
```

On Windows, the updater's launcher is
`client\common\distribution\target\runtime\updater\<V>\bin\cat-updater.ps1`. Run it as
`powershell.exe -NoProfile -NonInteractive -ExecutionPolicy Bypass -File "<absolute path to cat-updater.ps1>" setup ...`
with the same arguments. Use a different `--home` for a throwaway harness home; the home must be an absolute path.

The source checkout's launchers run without installation, because the build binds each plugin tree to the built
runtime: `client/common/distribution/target/runtime/<harness>/client/bin/<launcher>`, for example
`client/common/distribution/target/runtime/claude/client/bin/get-history`.

### Test removal or an upgrade

Use the harness's installed updater's `uninstall` operation, as described in the
[installation guide](installation.md#uninstall), when testing removal; it removes only that harness's installation. Run
`setup` again with the same `--plugin` to test an upgrade.

Run the external launcher and entry-script boundary tests:

```bash
bats client/common/distribution/src/test/bats/bootstrap-common.bats \
	client/common/distribution/src/test/bats/bootstrap-claude.bats \
	client/common/distribution/src/test/bats/bootstrap-codex.bats \
	client/common/distribution/src/test/bats/codex-plugin-launchers.bats \
	client/common/distribution/src/test/bats/jlink-launchers.bats \
	client/common/distribution/src/test/bats/updater-launcher.bats
```

Java tests call production classes directly. Bats is reserved for shell, entry-script, and generated-launcher boundaries
that Java tests cannot exercise directly.
