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
		return "Request a session only when the work needs a separate, isolated session: running the other harness, " +
			"checking a plugin that you wrote or a fresh CAT installation, starting from a fresh copy of the project, or " +
			"keeping the work out of your own context. Other work that you hand off goes to a subagent; when you cannot " +
			"start one, that work stays with you, and you do not request a session for it.\n" +
			"\n" +
			"You may request up to " + sessions + " over this run. A requested session is a new session that the " +
			"runner starts outside your sandbox, in a copy of a project directory that you name; your commands cannot " +
			"start Claude Code or Codex themselves.\n" +
			"\n" +
			"Write each prompt into a file inside your project, then run:\n" +
			"\n" +
			command + " --harness <claude|codex> --project <directory> --prompt <file> " +
			"[--prompt <file>]... [--plugin <directory>]... --model <name> --effort <level> [--install-cat] " +
			"[--session-transcripts] [--extra-permissions <file>]\n" +
			"\n" +
			"Every input is a file path you can read: the project to copy, one prompt file per turn, and each " +
			"plugin directory to install. Every request names the session's model and reasoning effort, and a request " +
			"without either is refused: use the ones that your task names, and when it names none, choose them from the " +
			"values that the harness lists, or ones you know it accepts if you cannot list them, and state the model " +
			"and the effort that you chose in your reply. " +
			"--install-cat also installs CAT in the " +
			"session. --extra-permissions names a JSON file of what the session's commands need beyond its own: " +
			"{\"format_version\":1,\"writes\":[\"<absolute path>\"],\"hosts\":[\"<host name>\"]}. The session gets " +
			"them only if this session's sandbox allows them; otherwise the request is refused, naming the settings to " +
			"add. Without the option the session asks for nothing extra. A prompt file must not be a symbolic link, " +
			"and a plugin must contain none. In the project, a symbolic link whose target lies inside the project " +
			"stays a link, and the session gets a copy of the file or folder that any other link points to; a link " +
			"whose target is missing or that you may not read is refused. The command waits up to 300 seconds. " +
			"When the session ends, it prints the " +
			"file path of the response, a JSON file whose status is completed, failed, refused, capacity or stopped, " +
			"with a reason, and whose fields name the file paths of the session's transcript, receipt and, with " +
			"--session-transcripts, its native transcripts. Read those files for the results; you may read them " +
			"although they lie under the run root. If the session is still running when the wait ends, the command " +
			"prints the request's id and the command that waits again, " + command + " --await <id>. It exits with 0 " +
			"when the session completed, 2 when the run refused the request, 3 when the wait ended first, and 1 " +
			"otherwise.\n" +
			"\n" +
			"The runner launchers " + claudeRunner + " and " + codexRunner + " request a session of their harness " +
			"in the same way, from the same count, and wait until it ends. Write the session's turns into a file, one " +
			"{\"type\":\"turn\",\"prompt\":\"<task>\"} line per turn followed by a {\"type\":\"complete\"} line, " +
			"then run:\n" +
			"\n" +
			codexRunner + " --project <directory> --input <file> [--plugin <directory>]... --model <name> " +
			"--effort <level> [--install-cat] [--session-transcripts] [--extra-permissions <file>] [--output <file>] " +
			"[--receipt <file>]\n" +
			"\n" +
			"It writes the session's transcript to --output, or prints it, copies its receipt to --receipt, and exits " +
			"with 0 when the session completed, 75 when no capacity was free, 2 when the run refused the launch, and 1 " +
			"otherwise, with the reason.\n" +
			"\n" +
			"To hold a conversation with a requested session, which keeps running between its turns, run either " +
			"runner launcher with --conversation <folder> in place of --input, or " + command + " with " +
			"--conversation <folder> in place of --prompt and --wait. <folder> is a new or empty folder that your " +
			"commands may write. Run the command so that it keeps running across your turns: in Claude Code, as a " +
			"background command, and in Codex, as a command that keeps running. " +
			"Append your messages to <folder>/to-child.jsonl, one JSON object per line: " +
			"{\"turn\": \"<text>\"} asks for the session's next turn, which starts when the previous one has ended; " +
			"{\"interrupt\": true} ends the turn in progress and the conversation goes on; and {\"end\": true} ends " +
			"the conversation. A " +
			"line that is not one of these is refused in <folder>/from-child.jsonl and changes nothing. The " +
			"session's records go to <folder>/from-child.jsonl as they arrive, and a line " +
			"{\"turn_end\": <n>, \"status\": \"<status>\"} follows them when turn n ends, with the status " +
			"completed, interrupted or failed, so wait for that line " +
			"before you read the turn's reply. The last line that the command writes there, however the session ends, " +
			"is {\"ended\": \"<status>\"}, with the status of the response, so a turn_end that you wait for does " +
			"not come once that line is there. The session ends when the conversation ends or when the command ends, " +
			"and the command then ends as it does without --conversation.";
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
