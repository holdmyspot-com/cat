package com.holdmyspot.cat.common.installer.activation;

/**
 * What a group of the entries that install records in a harness's configuration lets the harness do. The installer's
 * report to a person lists the groups in the order of these constants.
 */
public enum EntryEffect
{
	/**
	 * The harness runs the installer operations and the request server that the entries name without asking the user.
	 */
	RUNS_WITHOUT_ASKING("Runs without asking", Items.OPERATIONS),
	/**
	 * The harness runs the installer operations and the request server that the entries name outside its sandbox.
	 */
	RUNS_OUTSIDE_SANDBOX("Runs outside the sandbox", Items.OPERATIONS),
	/**
	 * Sandboxed commands may write the paths that the entries name.
	 */
	SANDBOX_MAY_WRITE("The sandbox may write to", Items.PATHS),
	/**
	 * Sandboxed commands may not write the paths that the entries name.
	 */
	SANDBOX_MAY_NOT_WRITE("The sandbox may not write to", Items.PATHS),
	/**
	 * The harness runs the hooks that the entries name without asking the user to review them.
	 */
	HOOKS_RUN_WITHOUT_REVIEW("Hooks run without review", Items.NAMES),
	/**
	 * The entries set a setting to the value that they name.
	 */
	SETS_VALUE("Sets", Items.NAMES);

	private final String heading;
	private final Items items;

	/**
	 * Creates an effect.
	 *
	 * @param heading the plain-language heading of the effect's groups
	 * @param items   what each item of the effect's groups names
	 */
	EntryEffect(String heading, Items items)
	{
		this.heading = heading;
		this.items = items;
	}

	/**
	 * Returns the heading under which the installer's report to a person lists the effect's groups.
	 *
	 * @return the heading
	 */
	public String heading()
	{
		return heading;
	}

	/**
	 * Returns what each item of the effect's groups names.
	 *
	 * @return the kind of the items
	 */
	public Items items()
	{
		return items;
	}

	/**
	 * What each item of an {@link EntryGroup} names.
	 */
	public enum Items
	{
		/**
		 * A CAT command: an installer operation, such as {@code installer install}, or the request server's file name.
		 */
		OPERATIONS,
		/**
		 * An absolute path.
		 */
		PATHS,
		/**
		 * A name or a value, shown as it is.
		 */
		NAMES
	}
}
