# Implementation plan

Workspace mode: `exclusive_shared`; no other write-capable agent is active.

Write allowlist: `result.txt`.

1. Create `result.txt` containing exactly `implemented` followed by a newline.
2. Run `test -f result.txt`.
