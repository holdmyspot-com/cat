package com.holdmyspot.cat.claude;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.HookScope;
import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.Waiting;
import com.holdmyspot.cat.common.Pause;
import com.holdmyspot.cat.common.runner.SessionLauncherChannel;
import com.holdmyspot.cat.common.runner.launcher.ProcessIdentity;
import com.holdmyspot.cat.common.runner.launcher.SessionLauncherStart;
import com.holdmyspot.cat.common.runner.policy.HarnessProcesses;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;

/**
 * Deletes the rules files of a session's CAT session directory after Claude ends the session. A resumed session's
 * SessionStart writes them again; the rest of the directory stays until the sweep removes it.
 * <p>
 * The hook also asks the session launcher of the Claude Code process that {@code CLAUDE_PID} names to stop, unless
 * the session ended because the same process goes on in another session, as it does when the user clears the
 * conversation or resumes another one.
 */
public final class ClaudeSessionEndHook
{
	/**
	 * The reasons for which a session ends while its Claude Code process goes on in another session.
	 */
	private static final Set<String> CONTINUING_REASONS = Set.of("clear", "resume");

	/**
	 * Runs the harness SessionEnd hook.
	 *
	 * @param args a non-null empty command-line argument array
	 * @throws NullPointerException if {@code args} is null
	 */
	public static void main(String[] args)
	{
		requireThat(args, "args").isNotNull();
		run(args, System.in, System.err, Platform.current().environment(System.getenv()),
			Path.of(System.getProperty("user.dir")), Pause::sleeping);
	}

	/**
	 * Processes one Claude SessionEnd event and deletes the rules files of its session.
	 *
	 * @param args a non-null empty command-line argument array
	 * @param input non-null harness hook input
	 * @param error non-null diagnostic output
	 * @param environment a non-null process environment
	 * @param workingDirectory a non-null process working directory
	 * @param waiting how the hook waits between the attempts of a retried operation
	 * @throws NullPointerException if any argument is null
	 */
	public static void run(String[] args, InputStream input, PrintStream error, Map<String, String> environment,
		Path workingDirectory, Waiting waiting)
	{
		run(args, input, error, environment, workingDirectory, waiting, HarnessProcesses.system());
	}

	/**
	 * Processes one Claude SessionEnd event: deletes the rules files of its session and stops the session launcher of
	 * its Claude Code process.
	 *
	 * @param args             a non-null empty command-line argument array
	 * @param input            non-null harness hook input
	 * @param error            non-null diagnostic output
	 * @param environment      a non-null process environment
	 * @param workingDirectory a non-null process working directory
	 * @param waiting          how the hook waits between the attempts of a retried operation
	 * @param processes        the operating system's processes, which report when Claude Code's process started
	 * @throws NullPointerException if any argument is null
	 */
	public static void run(String[] args, InputStream input, PrintStream error, Map<String, String> environment,
		Path workingDirectory, Waiting waiting, HarnessProcesses processes)
	{
		requireThat(args, "args").isNotNull();
		requireThat(input, "input").isNotNull();
		requireThat(error, "error").isNotNull();
		requireThat(environment, "environment").isNotNull();
		requireThat(workingDirectory, "workingDirectory").isNotNull();
		requireThat(waiting, "waiting").isNotNull();
		requireThat(processes, "processes").isNotNull();
		try (HookScope scope = ClaudeHookScope.open(environment, waiting))
		{
			if (args.length != 0)
				throw new IllegalArgumentException("SessionEnd accepts no arguments");
			JsonNode event = scope.readEvent("SessionEnd", input);
			if (!CONTINUING_REASONS.contains(event.path("reason").asString("")))
				stopLauncher(environment, processes, error);
			Path projectDir = ClaudeHookScope.projectDir(scope, event, environment, workingDirectory);
			scope.sessionDirectory(projectDir, event, "session_id").deleteRulesFiles();
		}
		catch (IOException | IllegalArgumentException | JacksonException e)
		{
			error.println("CAT SessionEnd: " + e.getMessage());
		}
	}

	/**
	 * Asks the session launcher of the Claude Code process that runs the hook to stop.
	 *
	 * @param environment the process environment
	 * @param processes   the operating system's processes
	 * @param error       the diagnostic output, which reports why the launcher could not be asked
	 */
	private static void stopLauncher(Map<String, String> environment, HarnessProcesses processes, PrintStream error)
	{
		try
		{
			Optional<ProcessIdentity> claude = ClaudeSessionStartHook.claudeProcess(environment, processes);
			if (claude.isEmpty())
				return;
			SessionLauncherStart.requestStop(new SessionLauncherChannel(Platform.current().homeDirectory(environment)),
				ClaudePluginInfo.HARNESS_ID, claude.get());
		}
		catch (IOException | IllegalArgumentException e)
		{
			error.println("CAT could not stop its session launcher: " + e.getMessage());
		}
	}

	/**
	 * Prevents instantiation.
	 */
	private ClaudeSessionEndHook()
	{
	}
}
