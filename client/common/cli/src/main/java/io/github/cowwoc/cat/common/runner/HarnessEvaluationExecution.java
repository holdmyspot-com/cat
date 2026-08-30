package io.github.cowwoc.cat.common.runner;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * Identifies retained evidence and the native-process timing for one completed harness evaluation.
 *
 * @param result the evidence retained by the harness
 * @param startedAtNanos the monotonic instant immediately after the harness process starts
 * @param completedAtNanos the monotonic instant immediately after the harness process exits
 */
public record HarnessEvaluationExecution(HarnessEvaluationResult result, long startedAtNanos, long completedAtNanos)
{
	/**
	 * Validates the result and its monotonic process timing.
	 *
	 * @param result the evidence retained by the harness
	 * @param startedAtNanos the monotonic instant immediately after the harness process starts
	 * @param completedAtNanos the monotonic instant immediately after the harness process exits
	 */
	public HarnessEvaluationExecution
	{
		requireThat(result, "result").isNotNull();
		if (completedAtNanos < startedAtNanos)
			throw new IllegalArgumentException("completedAtNanos must not precede startedAtNanos");
	}
}
