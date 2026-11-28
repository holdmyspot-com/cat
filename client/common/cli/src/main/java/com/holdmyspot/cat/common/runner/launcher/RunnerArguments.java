package com.holdmyspot.cat.common.runner.launcher;

/**
 * The runner options that a launch from a session may not pass.
 * <p>
 * <b>Thread Safety:</b> This class is thread-safe.
 */
public final class RunnerArguments
{
	/**
	 * The runner option with which CAT starts the runner of a requested conversation, so that a turn that fails while
	 * the harness keeps running does not end the session.
	 */
	public static final String CONTINUE_AFTER_FAILED_TURN_OPTION = "--continue-after-failed-turn";
	/**
	 * The runner option that gives the nested session the identifier, and so the directory, of a reusable slot.
	 */
	public static final String SLOT_ID_OPTION = "--slot-id";
	/**
	 * The runner option with which a server starts the runner of a launch that resumes a harness session, in the
	 * retained nested session that records the session, which the runner continues rather than start a new one.
	 */
	public static final String RESUME_SLOT_OPTION = "--resume-slot";
	/**
	 * The broker-only per-spawn startup permit presented before a runner mutates a managed slot.
	 */
	public static final String STARTUP_PERMIT_OPTION = "--startup-permit";
	/**
	 * The broker-only option that adds a current parent read restriction when continuing a retained session.
	 */
	public static final String INHERITED_DENY_READ_OPTION = "--inherited-deny-read";
	/**
	 * The launch option that resumes a harness session that an interrupted nested session of the launch's server
	 * recorded.
	 */
	public static final String RESUME_OPTION = "--resume";
	/**
	 * The launch option that states that the session belongs to the held-out part, so that a request server or run
	 * runs it in a slot of the held-out part.
	 */
	public static final String HELD_OUT_OPTION = "--held-out";
	/**
	 * The launch option that states that a prompt optimizer requests the session as a trial, judge or other session of
	 * a campaign, which a request server or a nested session's runner refuses while the requesting session has no
	 * sandbox.
	 */
	public static final String OPTIMIZER_SESSION_OPTION = "--optimizer-session";
	/**
	 * The launch option that selects one project directory whose link declarations are returned as result data.
	 */
	public static final String RESULT_PROJECT_LINKS_OPTION = "--result-project-links";
	/**
	 * The runner option that a nested session's runner passes to the runner of each session that its session requests,
	 * beside its own launch's source home and gateway login class. The requested session's runner then takes them from
	 * its parent runner rather than from a requester, so it does not check them against its bound: that bound is the
	 * nested session's policy, which denies the nested session's commands the source home's secret files by design.
	 */
	public static final String INHERITED_SOURCE_HOME_OPTION = "--inherited-source-home";

	/**
	 * Prevents construction.
	 */
	private RunnerArguments()
	{
	}
}
