# Installation data migrations

Store each migration script in this directory and register it in `registry.tsv`, one line per script: its target
version, a TAB, and the script's name relative to this directory. Keep target versions strictly increasing.

A script receives `CAT_MIGRATION_DATA_DIR`, `CAT_MIGRATION_FROM_VERSION`, and `CAT_MIGRATION_TO_VERSION`. It must
modify only the data directory named by `CAT_MIGRATION_DATA_DIR` and return nonzero when it cannot finish safely.

Register each script by its `.sh` name, and put a PowerShell script with the same name and the extension `.ps1` beside
it. The `.ps1` script reads the same variables from `$env:`, must stay compatible with Windows PowerShell 5.1, and must
throw when it cannot finish safely.

The plugin data's `migration-version` file records the data's schema version: the target version of the newest
migration that has shaped the data. It does not record a release.

When install installs a release, it runs the migrations that the release registers after the recorded
`migration-version`, up to the release's version, in registry order and from the release's installed plugin folder.
After each script succeeds, install records that script's target version in `migration-version`; the first script
that fails stops install, which names the script, and running the same install again resumes from it. An install that
runs no script leaves `migration-version` unchanged.

A first install with no earlier data runs no script. It records the newest target version that the release registers
up to its own version, because new data already has those data structures. When the release registers no migration,
install writes no `migration-version` file; an absent file means that no migration has shaped the data.

Because `migration-version` names a migration's target, a recorded version newer than a release means that a
migration registered after that release has shaped the data. An install from a plugin archive refuses such a release
before it changes anything, and names the recorded version, the release and installing a release at least as new as
the recorded version. An install of an older release from an installed version folder is refused when the active
release registers a migration after that older release.

When a release does not change persisted data, leave the registry unchanged.

The installer's handling of the registry is tested by `StagedReleaseTest` and `MigrationTest` in
`client/common/installer`, and this registry by `MigrationRegistryTest` in `client/common/lifecycle`.
