package com.holdmyspot.cat.common.runner;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.ShellQuoting;

import java.nio.file.Path;
import java.util.List;

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
	 * @param launcher        the absolute path of the request-session launcher
	 * @param powerShell      {@code true} to write PowerShell commands, {@code false} for POSIX shell commands
	 * @return the instructions
	 * @throws NullPointerException     if {@code launcher} is null
	 * @throws IllegalArgumentException if {@code sessionRequests} is not positive or {@code launcher} is relative
	 */
	public static String text(int sessionRequests, Path launcher, boolean powerShell)
	{
		requireThat(sessionRequests, "sessionRequests").isPositive();
		requireThat(launcher, "launcher").isAbsolute();
		String command = command(launcher, powerShell);
		List<Path> runners = SessionRequestAccess.runners(launcher);
		String claudeRunner = command(runners.get(0), powerShell);
		String codexRunner = command(runners.get(1), powerShell);

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
			command + " --harness <claude|codex> --project <directory> --prompt <file> " +
			"[--prompt <file>]... [--plugin <directory>]... [--model <name>] [--effort <level>] [--install-cat] " +
			"[--session-transcripts]\n" +
			"\n" +
			"Every input is a file path you can read: the project to copy, one prompt file per turn, and each " +
			"plugin directory to install. --install-cat also installs CAT in the session. Inputs must contain no " +
			"symbolic links. The command waits up to 300 seconds. " +
			"When the session ends, it prints the " +
			"file path of the response, a JSON file whose status is completed, failed, refused, capacity or stopped, " +
			"with a reason, and whose fields name the file paths of the session's transcript, receipt and, with " +
			"--session-transcripts, its native transcripts. Read those files for the results; you may read them " +
			"although they lie under the run root. If the session is still running when the wait ends, the command " +
			"prints the request's id and the command that waits again, " + command + " --await <id>.\n" +
			"\n" +
			"The runner launchers " + claudeRunner + " and " + codexRunner + " request a session of their harness " +
			"in the same way, from the same count, and wait until it ends. Write the session's turns into a file, one " +
			"{\"type\":\"turn\",\"prompt\":\"<task>\"} line per turn followed by a {\"type\":\"complete\"} line, " +
			"then run:\n" +
			"\n" +
			codexRunner + " --project <directory> --input <file> [--plugin <directory>]... [--model <name>] " +
			"[--effort <level>] [--install-cat] [--session-transcripts] [--output <file>] [--receipt <file>]\n" +
			"\n" +
			"It writes the session's transcript to --output, or prints it, copies its receipt to --receipt, and exits " +
			"with 0 when the session completed, 75 when no capacity was free, and 1 otherwise, with the reason.";
	}

	/**
	 * Returns the command that runs a launcher.
	 *
	 * @param launcher   the launcher's absolute path
	 * @param powerShell {@code true} for a PowerShell command, {@code false} for a POSIX shell command
	 * @return the quoted command
	 */
	private static String command(Path launcher, boolean powerShell)
	{
		if (powerShell)
			return "& " + ShellQuoting.powerShell(launcher.toString());
		return ShellQuoting.posix(launcher.toString());
	}

	/**
	 * Prevents construction of utility class instances.
	 */
	private SessionRequestInstructions()
	{
	}
}
