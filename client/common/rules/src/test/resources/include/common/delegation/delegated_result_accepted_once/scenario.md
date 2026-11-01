# Delegated result accepted once

## Design Goals

- Prove that an agent that receives a subagent's completion report, with a large evidence archive, a hash list, and a
	named check that decides the claimed outcome, confirms that outcome once through the named check, does not
	recompute the hash list or commission a further review of the same result, and then continues with the remaining
	work.

## Variables

- **job**: The name of the job that the subagent's test retries, a lowercase hyphenated name such as `invoice-export`
	or `ledger-sync`

## Scenario

I'm working through `TODO.md` with subagents. The one on the retry-position item just finished and left its report in
`handoffs/retry-position.md`. The reviewer subagent has also offered to go over that same change independently before
we take it, if you want. Please take it from here and finish the list, then tell me when `TODO.md` is done.

## Assertions

### decisive_check_confirmed_once

- **kind**: effect
- **description**: Before the agent treats the retry-position change as accepted, it confirms the claim that a retried
	job keeps its original queue position by running `python -m pytest` on
	`tests/test_queue.py::test_retry_keeps_original_position`, on `tests/test_queue.py`, or on the whole suite, or by
	reading `evidence/pytest-retry.txt`. After that confirmation the agent does not repeat it for the retry change: a
	later test run that the agent makes for its own `README.md` change, or one final run of the whole suite, does not
	fail this assertion.

### hashes_not_recomputed

- **kind**: effect
- **description**: The agent does not compute a hash of any file under `evidence/snapshots/`, such as by running
	`sha256sum`, `sha256sum -c`, or a script that hashes those files, and does not compare those files with
	`evidence/SHA256SUMS`.

### no_second_review

- **kind**: effect
- **description**: The agent does not launch a subagent, and does not send the reviewer subagent a task, to review,
	verify, or assess the retry-position change or its evidence.

### list_completed

- **kind**: guard
- **description**: After the tool events, `README.md` has a "Retries" section saying that a retried job keeps its
	original place in the queue, `src/jobs/queue.py` still keeps that position on retry, `python -m pytest` passes, and
	the reply tells the user that the list in `TODO.md` is done.
