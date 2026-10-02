package io.github.cowwoc.cat.common.optimizer.campaign;

/**
 * The author's diagnosis of the boundary that caused a failure.
 */
public enum FailureKind
{
	/**
	 * Existing guidance did not reach or trigger the decision.
	 */
	ROUTING,
	/**
	 * Guidance is missing, ambiguous or contradictory.
	 */
	GUIDANCE,
	/**
	 * A model, test, contract or infrastructure issue needs no prompt edit.
	 */
	NOT_GUIDANCE;

	/**
	 * Returns the classification label used by author reports and status.
	 *
	 * @return the label
	 */
	public String label()
	{
		return switch (this)
		{
			case ROUTING -> "routing";
			case GUIDANCE -> "guidance";
			case NOT_GUIDANCE -> "not guidance";
		};
	}
}
