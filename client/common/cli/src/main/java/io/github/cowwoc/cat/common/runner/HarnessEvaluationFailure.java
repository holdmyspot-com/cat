package io.github.cowwoc.cat.common.runner;

import java.io.IOException;
import java.io.Serial;

/**
 * Reports a harness evaluation failure while preserving whether its owner requested interruption during execution.
 */
public final class HarnessEvaluationFailure extends IOException
{
	@Serial
	private static final long serialVersionUID = 0L;
	private final boolean interrupted;

	/**
	 * Creates one harness evaluation failure.
	 *
	 * @param message the precise failed condition
	 * @param cause the underlying harness or evidence failure
	 * @param interrupted whether the operation owner requested interruption while the harness process was active
	 */
	public HarnessEvaluationFailure(String message, IOException cause, boolean interrupted)
	{
		super(message, cause);
		this.interrupted = interrupted;
	}

	/**
	 * Indicates whether the operation owner requested interruption while the failed harness process was active.
	 *
	 * @return {@code true} when later orchestration must pause before another harness evaluation
	 */
	public boolean interrupted()
	{
		return interrupted;
	}
}
