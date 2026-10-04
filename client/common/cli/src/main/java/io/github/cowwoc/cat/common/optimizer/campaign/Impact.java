package io.github.cowwoc.cat.common.optimizer.campaign;

/**
 * What a failed development trial cost the user, which decides whether development edits at once. Both kinds count
 * as failures in every count, estimate and claim.
 */
public enum Impact
{
	/**
	 * The reply leaves the user without the turn's correct and complete result unless the user acts. Development
	 * edits at once.
	 */
	BLOCKING,
	/**
	 * The reply still delivers the turn's correct and complete result and leaves nothing to undo, so the failure cost
	 * only extra work. Development reruns the failing instance before it decides whether to edit.
	 */
	SELF_RECOVERING;

	/**
	 * Returns the label that the triage's output and the author's brief use.
	 *
	 * @return the label
	 */
	public String label()
	{
		return switch (this)
		{
			case BLOCKING -> "blocking";
			case SELF_RECOVERING -> "self-recovering";
		};
	}
}
