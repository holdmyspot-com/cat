# Performance Changes

## Design Goals

- A change that is meant to improve the performance of a program or build is applied only when a minimal benchmark,
	which compares the program or build before and after the change on the same machine and input, shows that the
	improvement is meaningful.
- The cause of a slow build or workflow is located in its own timing output before a change is chosen, so that the
	change targets where the time goes and not an assumed cause.
- When the benchmark shows only a small improvement, the user decides whether the change proceeds, with the measured
	numbers in front of them.
- When the benchmark shows that performance degrades, the change is not made, and the user can see the measured numbers
	that led to that outcome.

## Guidance

This rule applies before you choose or apply a change, or add functionality, that is meant to improve the performance
of a program or build, such as its run time, memory use, or build time. It does not apply to a change that has another
purpose and only happens to affect performance.

Measure first. Do not rely on the expectation that the change is faster, even when the reason seems obvious.

When the change is meant to speed up a slow build or workflow, locate where its time goes before you choose the change:

- Read the build's own profile or timing output: the total elapsed time against the critical path, how much work is
	ready and how much is running, and which steps or targets take the most time.
- Elapsed time far above the critical path, with much work ready and little running, means the job is short of CPU. More
	parallel machines help; tuning one machine does not.
- Count how many times each expensive dependency is built across the whole workflow: every shard, platform and build
	configuration, such as a build tool's separate configurations for host tools and for target code.
- Find which target pulls the expensive dependency in. Do not assume the obvious consumer: a default, such as a switch
	between a prebuilt and a source dependency, can mean that the expected path never builds it.

1. Choose the metric the change is meant to improve, and the smallest benchmark that exercises the code or build step
	the change affects, on an input that represents how it is used. A timed command or a short script is enough; do not
	build a benchmark suite.
2. Run the benchmark on the unchanged program or build. Then run it on the changed one, which you apply only in a
	scratch copy or as an uncommitted edit that you can discard. Use the same machine, the same input, and the same
	conditions for both runs, and run each version enough times to show how much its result varies from run to run. For a
	change to a cache, the baseline is a run on the same revision, and each version's numbers are its wall clock time, its
	cache hit counts from the build's own summary and its cache size, from a cold run (empty cache) and a warm run.
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
