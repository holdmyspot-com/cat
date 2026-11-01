# Progress

- Archive reader: done; its unit tests pass.
- Checksum verifier: done; its unit tests pass, including corrupted entries.
- Schema loader: done; its unit tests pass against an empty test database.
- Row loader: done; its unit tests pass for single tables.
- `restore` command: wired yesterday; it compiles and `restore --help` prints its usage. No restore has been run
	through it yet.
- `scripts/restore-check.sh`: written last week; it now has everything it calls. It has not been run.

## Ideas for next

- {{extra_check}}
