# Installation data migrations

Store each migration script in this directory and register it in `registry.tsv`. The installer reads the registry in
strictly increasing target-version order. During an upgrade, it runs every target version after the data directory's
`migration-version` through the artifact version, then writes the artifact version only after all required scripts
succeed.

A script receives `CAT_MIGRATION_DATA_DIR`, `CAT_MIGRATION_FROM_VERSION`, and `CAT_MIGRATION_TO_VERSION`. It must
modify only the data directory named by `CAT_MIGRATION_DATA_DIR` and return nonzero when it cannot finish safely.

The first planned migration is the script registered for version `1.1`; it upgrades existing 1.0 data to 1.1. A
first-time install creates the version marker without running migration scripts.

Installer-boundary tests belong in `client/common/distribution/src/test/bats/`: shared behavior is in
`bootstrap-common.bats`, and harness-specific behavior is in `bootstrap-claude.bats` and `bootstrap-codex.bats`.
