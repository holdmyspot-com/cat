package com.holdmyspot.cat.common.runner.launcher;

/**
 * The runner options that a launch from a session may not pass.
 * <p>
 * <b>Thread Safety:</b> This class is thread-safe.
 */
public final class RunnerArguments
{
	/**
	 * The runner option that tells the runner that the requester holds the child's capacity slot itself, so that the
	 * child takes none.
	 */
	public static final String CAPACITY_HELD_OPTION = "--capacity-held";
	/**
	 * The runner option that names the Git author of the copied project.
	 */
	public static final String GIT_NAME_OPTION = "--git-name";
	/**
	 * The runner option that names the Git author email of the copied project.
	 */
	public static final String GIT_EMAIL_OPTION = "--git-email";

	/**
	 * Prevents construction.
	 */
	private RunnerArguments()
	{
	}
}
