# Installation data migrations

Store each migration script in this directory and register it in `registry.tsv`, one line per script: its target
version, a TAB, and the script's name relative to this directory. Keep target versions strictly increasing.

A script receives `CAT_MIGRATION_DATA_DIR`, `CAT_MIGRATION_FROM_VERSION`, and `CAT_MIGRATION_TO_VERSION`. It must
modify only the data directory named by `CAT_MIGRATION_DATA_DIR` and return nonzero when it cannot finish safely.

Register each script by its `.sh` name, and put a PowerShell script with the same name and the extension `.ps1` beside
it. The `.ps1` script reads the same variables from `$env:`, must stay compatible with Windows PowerShell 5.1, and must
throw when it cannot finish safely.

When install installs a release, it runs the migrations that the release registers after the plugin data's recorded
`migration-version`, up to the release's version, in registry order and from the release's installed plugin folder.
After each script succeeds, install records that script's target version in `migration-version`; the first script
that fails stops install, which names the script, and running the same install again resumes from it. A first install
with no earlier data runs no script. Install refuses an older release when the active release registers a migration
after that older release.

When a release does not change persisted data, leave the registry unchanged.

The installer's handling of the registry is tested by `StagedReleaseTest` and `MigrationTest` in
`client/common/installer`, and this registry by `MigrationRegistryTest` in `client/common/lifecycle`.
