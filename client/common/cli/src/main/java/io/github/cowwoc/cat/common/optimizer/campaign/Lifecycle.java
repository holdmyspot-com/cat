package io.github.cowwoc.cat.common.optimizer.campaign;

/**
 * Where a campaign is in its life.
 */
public enum Lifecycle
{
	/**
	 * The campaign may schedule work.
	 */
	ACTIVE,
	/**
	 * The user cancelled the campaign. It schedules nothing, keeps its history and spent allowance, and can resume.
	 */
	CANCELLED,
	/**
	 * The user closed the campaign without a terminal outcome, for example to start a new campaign.
	 */
	CLOSED,
	/**
	 * The campaign reached a terminal outcome.
	 */
	FINISHED;

	/**
	 * Indicates whether a campaign in this state can continue.
	 *
	 * @return {@code true} for {@link #ACTIVE} and {@link #CANCELLED}
	 */
	public boolean isResumable()
	{
		return switch (this)
		{
			case ACTIVE, CANCELLED -> true;
			case CLOSED, FINISHED -> false;
		};
	}
}
