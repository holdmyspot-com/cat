# Installation data migrations

Store each migration script in this directory and register it in `registry.tsv`, one line per script: its target
version, a TAB, and the script's name relative to this directory. Keep target versions strictly increasing.

A script receives `CAT_MIGRATION_DATA_DIR`, `CAT_MIGRATION_FROM_VERSION`, and `CAT_MIGRATION_TO_VERSION`. It must
modify only the data directory named by `CAT_MIGRATION_DATA_DIR` and return nonzero when it cannot finish safely.

Register each script by its `.sh` name, and put a PowerShell script with the same name and the extension `.ps1` beside
it. The `.ps1` script reads the same variables from `$env:`, must stay compatible with Windows PowerShell 5.1, and must
throw when it cannot finish safely.

The trusted updater refuses a routine update to a release that registers a migration after the plugin data's recorded
`migration-version`, up to the release's version, and directs the user to the release's explicit installation command.
No part of CAT runs migration scripts yet, so a release that registers one must also add the code that runs it.

When a release does not change persisted data, leave the registry unchanged.

The line for `1.0.0` registers `per-harness-layout.sh`, which changes no plugin data. It marks the move from one
installation that every harness shares to one installation per harness. The updater of the shared installation recorded
`1.0`, so it refuses every later release as a routine update and directs the user to the release's setup, which makes
the move. The per-harness updater records at least `1.0.0` in every harness that it activates a plugin in, and its
setup accepts a release whose only pending migration is this one. Give every later migration a version after `1.0.0`.

The updater's handling of the registry is tested by `StagedReleaseTest` in `client/common/updater`, and this registry by
`MigrationRegistryTest` in `client/common/lifecycle`.
