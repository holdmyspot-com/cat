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
	public static final String SLOT_ID_OPTION = "--slot-id";
	/**
	 * The runner option with which a server starts the runner of a launch that resumes a harness session, in the
	 * retained run that records the session, which the runner continues rather than start a new one.
	 */
	public static final String RESUME_SLOT_OPTION = "--resume-slot";
	/**
	 * The launch option that resumes a harness session that an interrupted run of the launch's server recorded.
	 */
	public static final String RESUME_OPTION = "--resume";
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
