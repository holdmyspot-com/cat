package io.github.cowwoc.cat.common.evaluation;

/**
 * Captures one worker's queue, executor, validator, and merge timing boundaries.
 *
 * @param queuedAtNanos the monotonic time when the worker entered the executor queue
 * @param executionStartedAtNanos the monotonic time when local executor setup began
 * @param commandStartedAtNanos the monotonic time when the executor process started
 * @param commandCompletedAtNanos the monotonic time when the executor process completed
 * @param validationStartedAtNanos the monotonic time when validator processing began
 * @param validationCompletedAtNanos the monotonic time when validator processing completed
 * @param mergedAtNanos the monotonic time when the terminal verdict was retained
 */
public record WorkerTiming(long queuedAtNanos, long executionStartedAtNanos, long commandStartedAtNanos,
	long commandCompletedAtNanos, long validationStartedAtNanos, long validationCompletedAtNanos, long mergedAtNanos)
{
	/**
	 * Creates executor-only timing before independent validation begins.
	 *
	 * @param queuedAtNanos the monotonic time when the worker entered the executor queue
	 * @param executionStartedAtNanos the monotonic time when local executor setup began
 * @param commandStartedAtNanos the monotonic time when the executor process started
 * @param commandCompletedAtNanos the monotonic time when the executor process completed
	 */
	public WorkerTiming(long queuedAtNanos, long executionStartedAtNanos, long commandStartedAtNanos,
		long commandCompletedAtNanos)
	{
		this(queuedAtNanos, executionStartedAtNanos, commandStartedAtNanos, commandCompletedAtNanos, -1, -1, -1);
	}

	/**
	 * Adds independent-validator and merge timing boundaries.
	 *
	 * @param validationStartedAtNanos the monotonic time when validator processing began
	 * @param validationCompletedAtNanos the monotonic time when validator processing completed
	 * @param mergedAtNanos the monotonic time when the terminal verdict was retained
	 * @return the completed worker timing
	 */
	public WorkerTiming completed(long validationStartedAtNanos, long validationCompletedAtNanos, long mergedAtNanos)
	{
		return new WorkerTiming(queuedAtNanos, executionStartedAtNanos, commandStartedAtNanos, commandCompletedAtNanos,
			validationStartedAtNanos, validationCompletedAtNanos, mergedAtNanos);
	}
}
