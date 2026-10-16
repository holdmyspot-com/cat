# Installation data migrations

Store each migration script in this directory and register it in `registry.tsv`. The installer reads the registry in
strictly increasing target-version order. During an upgrade, it runs every target version after the data directory's
`migration-version` through the artifact version, then writes the artifact version only after all required scripts
succeed.

A script receives `CAT_MIGRATION_DATA_DIR`, `CAT_MIGRATION_FROM_VERSION`, and `CAT_MIGRATION_TO_VERSION`. It must
modify only the data directory named by `CAT_MIGRATION_DATA_DIR` and return nonzero when it cannot finish safely.

Register each script by its `.sh` name, and put a PowerShell script with the same name and the extension `.ps1` beside
it. The Codex installer on Windows, `install-codex.ps1`, runs the `.ps1` script instead, and refuses to install when it
is missing. It reads the same variables from `$env:`, must stay compatible with Windows PowerShell 5.1, and must throw
when it cannot finish safely.

When a release does not change persisted data, leave the registry unchanged; the installer still advances the version
marker. A first-time install creates that marker without running migration scripts.

Installer-boundary tests belong in `client/common/distribution/src/test/bats/`: shared behavior is in
`bootstrap-common.bats`, and harness-specific behavior is in `bootstrap-claude.bats` and `bootstrap-codex.bats`.
`CodexWindowsInstallerTest` tests the PowerShell installer.
