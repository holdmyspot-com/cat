package com.holdmyspot.cat.common.runner.policy;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * Thrown when CAT cannot establish a session's sandbox policy safely, so that it starts no child session for that
 * session. The caller shows the user {@link #messageFor(String)}, which says what failed and how to fix it.
 */
public final class PolicyRefusal extends Exception
{
	private static final long serialVersionUID = 1L;
	/**
	 * What failed, as a clause without a final period.
	 */
	private final String reason;
	/**
	 * What the user does to fix it, as one or more sentences without the last one's final period.
	 */
	private final String fix;

	/**
	 * Creates a refusal.
	 *
	 * @param reason what failed, as a clause without a final period, such as "the session's settings file
	 *               {@code /p/settings.json} cannot be parsed"
	 * @param fix    what the user does to fix it, as one or more sentences without the last one's final period
	 * @throws NullPointerException     if any of the arguments are null
	 * @throws IllegalArgumentException if any of the arguments are blank
	 */
	public PolicyRefusal(String reason, String fix)
	{
		super(reason + ". " + fix + ".");
		requireThat(reason, "reason").isNotBlank();
		requireThat(fix, "fix").isNotBlank();
		this.reason = reason;
		this.fix = fix;
	}

	/**
	 * Returns what failed.
	 *
	 * @return a clause without a final period
	 */
	public String reason()
	{
		return reason;
	}

	/**
	 * Returns what the user does to fix it.
	 *
	 * @return one or more sentences without the last one's final period
	 */
	public String fix()
	{
		return fix;
	}

	/**
	 * Returns the message that tells the user why CAT starts no child session and how to fix it.
	 *
	 * @param harnessName the name of the parent session's harness as the user knows it, such as a product name
	 * @return the message
	 * @throws NullPointerException     if {@code harnessName} is null
	 * @throws IllegalArgumentException if {@code harnessName} is blank
	 */
	public String messageFor(String harnessName)
	{
		requireThat(harnessName, "harnessName").isNotBlank();
		return "CAT cannot start the session: it cannot read this " + harnessName + " session's sandbox policy " +
			"safely: " + reason + ". " + fix + ".";
	}
}
