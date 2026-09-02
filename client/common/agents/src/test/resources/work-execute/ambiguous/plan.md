# Implementation plan

Workspace mode: `exclusive_shared`; no other write-capable agent is active.

Write allowlist: `result.txt`.

1. Create `result.txt` containing exactly `first` followed by a newline.
2. Create `result.txt` containing exactly `second` followed by a newline.
