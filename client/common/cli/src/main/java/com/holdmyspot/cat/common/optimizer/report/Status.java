package com.holdmyspot.cat.common.optimizer.report;

/**
 * What the host agent does next, as the JSON status reports it. A campaign that ended reports its terminal outcome
 * label instead of a value of this type.
 */
public enum Status
{
	/**
	 * The campaign can schedule work, so the host calls {@code run} again.
	 */
	RUNNING("running"),
	/**
	 * The campaign waits for the user: to confirm a contract, to answer a pending decision, or to resume.
	 */
	AWAITING_USER("awaiting_user"),
	/**
	 * Another driver holds the campaign. The driver reports this; the report never derives it from campaign state.
	 */
	BUSY("busy");

	private final String label;

	/**
	 * Creates a status.
	 *
	 * @param label the text that the JSON status holds
	 */
	Status(String label)
	{
		this.label = label;
	}

	/**
	 * Returns the text that the JSON status holds.
	 *
	 * @return the label
	 */
	public String label()
	{
		return label;
	}
}
