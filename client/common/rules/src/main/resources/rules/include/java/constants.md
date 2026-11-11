# Java Constants

## Design Goals

- Keep Java POSIX permission constants readable and converted only at the API boundary that needs mode bits.

## Guidance

Define POSIX permission constants as immutable `Set<PosixFilePermission>` values parsed from readable owner,
group, and other `rwx` strings with `PosixFilePermissions.fromString()`. When an API requires an integer POSIX mode,
convert the set to integer bits in one local helper at that API boundary. Keep integer permission-bit values inside that
converter. Do not apply this approach to unrelated numeric values.
