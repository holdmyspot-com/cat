## Scenario

Write API documentation for `exportReport(Path destination)`. It replaces an existing file at `destination`, returns
the resulting path, and throws `IOException` if the replacement cannot be completed. The current implementation uses a
SQLite cache before writing the report, but it may use another storage mechanism later. Document what a caller needs to
use the operation correctly.

## Assertions

### caller-contract

The response documents the destination replacement, returned path, and observable failure.

### replaceable-storage

The response does not mention SQLite, caching, another possible storage mechanism, or disclaim whether the storage
mechanism is guaranteed.
