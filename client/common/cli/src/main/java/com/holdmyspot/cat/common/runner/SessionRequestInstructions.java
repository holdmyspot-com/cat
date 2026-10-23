package com.holdmyspot.cat.common.runner;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * The instructions that tell a session how to request a session, the same in every harness. A runner adds them to the
 * session's instructions when the session may request any.
 */
public final class SessionRequestInstructions
{
	/**
	 * Returns the instructions.
	 *
	 * @param sessionRequests how many sessions the session may request over its run
	 * @return the instructions
	 * @throws IllegalArgumentException if {@code sessionRequests} is not positive
	 */
	public static String text(int sessionRequests)
	{
		requireThat(sessionRequests, "sessionRequests").isPositive();
		String sessions;
		if (sessionRequests == 1)
			sessions = "1 session";
		else
			sessions = sessionRequests + " sessions";
		return "You may request up to " + sessions + " over this run. A requested session is a new session " +
			"that the runner starts outside your sandbox, in a copy of a project directory that you name. Request one " +
			"when work needs checking in a new session, such as a plugin that you wrote: your commands cannot start " +
			"Claude Code or Codex themselves.\n" +
			"\n" +
			"Write each prompt into a file inside your project, then run:\n" +
			"\n" +
			"\"$CAT_REQUEST_SESSION\" --harness <claude|codex> --project <directory> --prompt <file> " +
			"[--prompt <file>]... [--plugin <directory>]... [--model <name>] [--effort <level>] " +
			"[--session-transcripts]\n" +
			"\n" +
			"Every input is a file path inside your project: the project to copy, one prompt file per turn, and each " +
			"plugin directory to install. The command waits up to 300 seconds. When the session ends, it prints the " +
			"file path of the response, a JSON file whose status is completed, failed, refused, capacity or stopped, " +
			"with a reason, and whose fields name the file paths of the session's transcript, receipt and, with " +
			"--session-transcripts, its native transcripts. Read those files for the results; you may read them " +
			"although they lie under the run root. If the session is still running when the wait ends, the command " +
			"prints the request's id and the command that waits again, \"$CAT_REQUEST_SESSION\" --await <id>.";
	}

	/**
	 * Prevents construction of utility class instances.
	 */
	private SessionRequestInstructions()
	{
	}
}
