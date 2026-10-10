package io.github.cowwoc.cat.common.optimizer.contract;

/**
 * One of the two halves into which the confirmed contract splits each prompt test's test cases, by their expected
 * outcomes, before any trial.
 */
public enum Part
{
	/**
	 * The test cases that development trials, targeted runs, triage, diagnosis and prompt author sessions draw or
	 * receive.
	 */
	DEVELOPMENT,
	/**
	 * The test cases that formal trials and the incumbent's baseline trials draw. No session that shapes a candidate
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
