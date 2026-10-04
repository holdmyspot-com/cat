package io.github.cowwoc.cat.common.optimizer.contract;

/**
 * One of the two halves into which the confirmed contract splits each test definition's instances, by their key
 * values, before any trial.
 */
public enum Part
{
	/**
	 * The instances that development trials, targeted runs, triage, diagnosis and author sessions draw or receive.
	 */
	DEVELOPMENT,
	/**
	 * The instances that formal trials and the incumbent's baseline arm draw. No session that shapes a candidate
	 * receives one.
	 */
	HELD_OUT;

	/**
	 * Returns the part's name as a message shows it.
	 *
	 * @return {@code development} or {@code held-out}
	 */
	@Override
	public String toString()
	{
		return switch (this)
		{
			case DEVELOPMENT -> "development";
			case HELD_OUT -> "held-out";
		};
	}
}
