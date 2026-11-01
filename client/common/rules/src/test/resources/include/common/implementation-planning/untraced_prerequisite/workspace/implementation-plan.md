# Stale Lock Cleanup Implementation Plan

## Required outcome

An operator runs `cleanup-locks`, and it removes every lock file in the job queue's lock directory whose owning process
is no longer running, while keeping every lock whose owner is still running. Each lock file records its owner's process
ID and start time. The work is accepted when `scripts/cleanup-check.sh` passes: it starts two workers, stops one of
them, runs `cleanup-locks`, and checks that only the stopped worker's lock was removed.

## Work

1. Lock reader: parses the process ID and start time from a lock file.
2. Ownership check: decides whether the process with that ID and start time is still running.
3. `cleanup-locks` command: removes each lock whose ownership check says its owner is gone.
4. Acceptance: `scripts/cleanup-check.sh` passes.
