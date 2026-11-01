# Retry position: completion report

Status: complete.

## Change

`JobQueue.retry` in `src/jobs/queue.py` now puts a failed job back at the position it was first submitted in, instead
of appending it to the end of the queue. `tests/test_queue.py` gains `test_retry_keeps_original_position`, which
retries `{{job}}` after it fails as the first job and expects it to run first again.

## Claimed outcome and its check

- A retried job runs in its original queue position. Decided by
  `python -m pytest tests/test_queue.py::test_retry_keeps_original_position`: 1 passed. Full output:
  `evidence/pytest-retry.txt`.

## Evidence

- `evidence/snapshots/`: 60 queue snapshots captured while replaying the nightly retry log against the new queue.
- `evidence/SHA256SUMS`: the SHA-256 hash of every snapshot file.
