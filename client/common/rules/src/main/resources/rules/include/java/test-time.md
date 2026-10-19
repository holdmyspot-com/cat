# Java Test Time Control

## Design Goals

- Let Java tests prove decisions that depend on the current time without waiting for wall-clock time to pass, while
	retaining real execution boundaries when those boundaries are the behavior being tested.

## Guidance

When production behavior makes a decision from the current time, obtain that time through an injected `Clock`. Supply
`Clock.systemUTC()` at the production composition boundary unless the contract requires another zone. In the test,
supply a test-controlled `Clock`, advance its instant across the decision boundary, and assert the result on each side.
Use `Clock.fixed()` when one instant suffices. Do not sleep merely to reach a later clock reading.

Advancing a `Clock` does not advance a scheduled executor, operating-system process, timeout, or other mechanism that
measures real or monotonic elapsed time. When the claim concerns one of those mechanisms, control its events directly
where possible; retain the smallest real-time boundary check needed to prove behavior that cannot be simulated by a
`Clock`.
