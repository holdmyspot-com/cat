package io.github.cowwoc.cat.common.optimizer.campaign;

/**
 * The kind of reference trial that a cell's paired comparisons compare its candidate formal trials with. Each kind
 * pairs its own trials with the same candidate formal trials, and every assertion of a certified cell must pass the
 * comparisons of both kinds before the cell certifies.
 */
public enum Reference
{
	/**
	 * Control trials, which run the incumbent with every target prompt file emptied and its dependents removed.
	 */
	CONTROL,
	/**
	 * Incumbent trials, which run the incumbent unchanged. A cell has no incumbent comparisons when the candidate is
	 * the incumbent.
	 */
	INCUMBENT;

	/**
	 * Returns the purpose of this kind's trials.
	 *
	 * @return {@link Purpose#CONTROL} or {@link Purpose#INCUMBENT}
	 */
	public Purpose purpose()
	{
		return switch (this)
		{
			case CONTROL -> Purpose.CONTROL;
			case INCUMBENT -> Purpose.INCUMBENT;
		};
	}
}
