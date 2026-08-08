# Java Constants

## Design Goals

- Keep Java POSIX permission constants readable and converted only at the API boundary that needs mode bits.

## Guidance

Define POSIX permission constants as immutable `Set<PosixFilePermission>` values parsed from readable owner,
group, and other `rwx` strings with `PosixFilePermissions.fromString()`. When an API requires an integer POSIX mode,
convert the set to integer bits in one local helper at that API boundary. Keep integer permission-bit values inside that
converter. Use separate constants when equal permissions represent different concepts, such as a directory and an
executable file. Do not apply this approach to unrelated numeric values.
