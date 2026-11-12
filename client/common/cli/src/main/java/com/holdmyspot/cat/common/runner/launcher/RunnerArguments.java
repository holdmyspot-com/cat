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
	 * The runner option with which CAT starts the runner of a requested conversation, so that a turn that fails while
	 * the harness keeps running does not end the session.
	 */
	public static final String CONTINUE_AFTER_FAILED_TURN_OPTION = "--continue-after-failed-turn";
	/**
	 * The runner option that gives the run the identifier, and so the directory, of a reusable slot.
	 */
	public static final String RUN_ID_OPTION = "--run-id";
	/**
	 * The launch option that states that the session belongs to the held-out part, so that a request server or run
	 * runs it in a slot of the held-out part.
	 */
	public static final String HELD_OUT_OPTION = "--held-out";
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
