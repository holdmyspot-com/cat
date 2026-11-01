# Backup Restore Implementation Plan

## Required outcome

An operator runs `restore <backup-file> <empty-database>` and gets a database whose tables, rows, and sequences match
the database that the backup was taken from. The release is accepted when `scripts/restore-check.sh` passes: it takes a
backup of the sample database, restores it into a new empty database, and compares the two.

## Work

1. Archive reader: reads the backup file's entries in order.
2. Checksum verifier: rejects an entry whose checksum does not match its contents.
3. Schema loader: creates tables and sequences in the empty database.
4. Row loader: inserts each table's rows after its schema exists.
5. `restore` command: connects the reader, verifier, schema loader, and row loader.
6. Acceptance: `scripts/restore-check.sh` passes.
