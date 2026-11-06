# Performance Changes

## Design Goals

- A change that is meant to improve the performance of a program or build is applied only when a minimal benchmark,
	which compares the program or build before and after the change on the same machine and input, shows that the
	improvement is meaningful.
- When the benchmark shows only a small improvement, the user decides whether the change proceeds, with the measured
	numbers in front of them.
- When the benchmark shows that performance degrades, the change is not made, and the user can see the measured numbers
	that led to that outcome.

## Guidance

This rule applies before you apply a change, or add functionality, that is meant to improve the performance of a
program or build, such as its run time, memory use, or build time. It does not apply to a change that has another
purpose and only happens to affect performance.

Measure first. Do not rely on the expectation that the change is faster, even when the reason seems obvious.

1. Choose the metric the change is meant to improve, and the smallest benchmark that exercises the code or build step
	the change affects, on an input that represents how it is used. A timed command or a short script is enough; do not
	build a benchmark suite.
2. Run the benchmark on the unchanged program or build. Then run it on the changed one, which you apply only in a
	scratch copy or as an uncommitted edit that you can discard. Use the same machine, the same input, and the same
	conditions for both runs, and run each version enough times to show how much its result varies from run to run.
3. Decide from the two results, comparing the difference with that variation:
	- If the changed version performs worse than the unchanged one by more than the variation, performance degrades.
		Do not make the change. Discard it, and report the measured numbers of both versions.
	- If the changed version performs better by more than the variation, and by enough to justify the cost of the
		change, such as the code it adds, the complexity it brings, and the risk it carries, the improvement is
		meaningful. Make the change, and state the measured numbers when you report it.
	- In every other case the improvement is small: the difference is within the variation, or the improvement is too
		small to justify the cost of the change. Do not make the change yet. Ask the user whether to proceed, reporting
		the measured numbers of both versions, the variation you saw, and the cost of the change. Make the change only
		if the user says to proceed.
