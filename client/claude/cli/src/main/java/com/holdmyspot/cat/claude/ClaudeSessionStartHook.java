package com.holdmyspot.cat.claude;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import com.holdmyspot.cat.claude.secrets.ClaudeSessionStartHookSecrets;
import com.holdmyspot.cat.common.AbstractHookScope;
import com.holdmyspot.cat.common.FileLocks;
import com.holdmyspot.cat.common.HookScope;
import com.holdmyspot.cat.common.Pause;
import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.SessionIgnore;
import com.holdmyspot.cat.common.Waiting;
import com.holdmyspot.cat.common.runner.launcher.ProcessIdentity;
import com.holdmyspot.cat.common.runner.launcher.SessionLauncherStart;
import com.holdmyspot.cat.common.runner.launcher.SessionLauncherStart.DetachedStarter;
import com.holdmyspot.cat.common.runner.policy.HarnessProcesses;
import com.holdmyspot.cat.common.runner.policy.ProcessDescription;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

/**
 * Injects CAT rules into a Claude SessionStart context.
 * <p>
 * Claude's rule-injection hooks store the complete context in a CAT-owned file and inject only its location. When
 * Claude Code receives more than 10,000 characters of hook context, it writes that context to a long internal path and
 * asks the model to {@code Read} it. In a runner project, the generated path includes a sanitized
 * {@code -runs-claude-...} segment. The model can change that segment to {@code -runs/claude-...} in its
 * {@code Read} request, making the requested file nonexistent and leaving the complete rules unread. The short
 * CAT-owned path avoids Claude Code's spill mechanism and lets the model read the complete rules before acting.
 * <p>
 * Before it injects the rules, the hook starts the session launcher of the Claude Code process that
 * {@code CLAUDE_PID} names, as {@link SessionLauncherStart} describes, and does not wait for it.
 */
public final class ClaudeSessionStartHook
{
	/**
	 * The variable in which Claude Code names its process to the hooks that it runs.
	 */
	static final String PROCESS_VARIABLE = "CLAUDE_PID";

	static
	{
		ClaudeSessionStartHookSecrets.setClaudeSessionStartHookAccess((args, input, output, error, environment,
			workingDirectory, waiting, git, platform, processes, starter) ->
		{
			requireThat(args, "args").isNotNull();
			requireThat(input, "input").isNotNull();
			requireThat(output, "output").isNotNull();
			requireThat(error, "error").isNotNull();
			requireThat(environment, "environment").isNotNull();
			requireThat(workingDirectory, "workingDirectory").isNotNull();
			requireThat(waiting, "waiting").isNotNull();
			requireThat(git, "git").isNotNull();
			requireThat(platform, "platform").isNotNull();
			requireThat(processes, "processes").isNotNull();
			requireThat(starter, "starter").isNotNull();
			run(args, input, output, error, environment, workingDirectory, waiting, git, platform, processes, starter);
		});
	}

	/**
	 * Runs the harness SessionStart hook.
	 *
	 * @param args a non-null empty command-line argument array
	 * @throws NullPointerException if {@code args} is null
	 */
	public static void main(String[] args)
	{
		requireThat(args, "args").isNotNull();
		run(args, System.in, System.out, System.err, Platform.current().environment(System.getenv()),
			Path.of(System.getProperty("user.dir")), Pause::sleeping);
	}

	/**
	 * Processes one Claude SessionStart event: starts the session launcher of the Claude Code process, then writes
	 * CAT's hook response.
	 *
	 * @param args a non-null empty command-line argument array
	 * @param input non-null harness hook input
	 * @param output non-null harness hook output
	 * @param error non-null diagnostic output
	 * @param environment a non-null process environment
	 * @param workingDirectory a non-null process working directory
	 * @param waiting how the hook waits between the attempts of a retried operation
	 * @throws NullPointerException if {@code args}, {@code input}, {@code output}, {@code error},
	 *         {@code environment}, {@code workingDirectory} or {@code waiting} are null
	 */
	public static void run(String[] args, InputStream input, OutputStream output, PrintStream error,
		Map<String, String> environment, Path workingDirectory, Waiting waiting)
	{
		requireThat(args, "args").isNotNull();
		requireThat(input, "input").isNotNull();
		requireThat(output, "output").isNotNull();
		requireThat(error, "error").isNotNull();
		requireThat(environment, "environment").isNotNull();
		requireThat(workingDirectory, "workingDirectory").isNotNull();
		requireThat(waiting, "waiting").isNotNull();
		run(args, input, output, error, environment, workingDirectory, waiting, SessionIgnore.GIT_PROCESS,
			Platform.current(), HarnessProcesses.system(), SessionLauncherStart.PROCESSES);
	}

	/**
	 * Processes one Claude SessionStart event: starts the session launcher of the Claude Code process, then writes
	 * CAT's hook response.
	 *
	 * @param args             the command-line arguments
	 * @param input            the hook input
	 * @param output           the hook output
	 * @param error            the diagnostic output
	 * @param environment      the process environment
	 * @param workingDirectory the process working directory
	 * @param waiting          how the hook waits between the attempts of a retried operation
	 * @param git              answers what Git ignores in the project
	 * @param platform         the platform that the hook runs on
	 * @param processes        the operating system's processes
	 * @param starter          starts the session launcher
	 */
	private static void run(String[] args, InputStream input, OutputStream output, PrintStream error,
		Map<String, String> environment, Path workingDirectory, Waiting waiting, SessionIgnore.GitQueries git,
		Platform platform, HarnessProcesses processes, DetachedStarter starter)
	{
		byte[] bytes;
		try
		{
			bytes = input.readNBytes(AbstractHookScope.MAXIMUM_EVENT_BYTES + 1);
		}
		catch (IOException e)
		{
			error.println("CAT could not start its session launcher: " + e.getMessage());
			ClaudeRuleInjectionHook.run("SessionStart", args, input, output, error, environment, workingDirectory, waiting,
				git);
			return;
		}
		startLauncher(bytes, environment, waiting, platform, processes, starter, error);
		ClaudeRuleInjectionHook.run("SessionStart", args, new ByteArrayInputStream(bytes), output, error, environment,
			workingDirectory, waiting, git);
	}

	/**
	 * Starts the session launcher of the Claude Code process that runs the hook. A hook without {@code CLAUDE_PID},
	 * one whose input is too large, or one whose environment names no usable plugin root starts none; the hook's rule
	 * injection reports the input and the environment.
	 *
	 * @param event       the hook input
	 * @param environment the process environment
	 * @param waiting     how the hook waits between the attempts of a retried operation
	 * @param platform    the platform that the hook runs on
	 * @param processes   the operating system's processes
	 * @param starter     starts the session launcher
	 * @param error       the diagnostic output, which reports why a launcher could not start
	 */
	private static void startLauncher(byte[] event, Map<String, String> environment, Waiting waiting,
		Platform platform, HarnessProcesses processes, DetachedStarter starter, PrintStream error)
	{
		if (event.length > AbstractHookScope.MAXIMUM_EVENT_BYTES || !SessionLauncherStart.isWanted(platform,
			environment))
		{
			return;
		}
		Path pluginRoot;
		try (HookScope scope = ClaudeHookScope.open(environment, waiting))
		{
			pluginRoot = scope.pluginRoot();
		}
		catch (IOException _)
		{
			return;
		}
		try
		{
			Optional<ProcessIdentity> claude = claudeProcess(environment, processes);
			if (claude.isEmpty())
				return;
			Path harnessHome = ClaudeConfiguration.resolveDirectory(platform.homeDirectory(environment),
				environment.get("CLAUDE_CONFIG_DIR"));
			SessionLauncherStart.startFromHook(event, ClaudePluginInfo.HARNESS_ID, claude.get(), harnessHome,
				pluginRoot, platform, environment, new FileLocks(), starter);
		}
		catch (IOException | IllegalArgumentException | UncheckedIOException e)
		{
			error.println("CAT could not start its session launcher: " + e.getMessage());
		}
	}

	/**
	 * Returns the Claude Code process that runs a hook.
	 *
	 * @param environment the hook's environment
	 * @param processes   the operating system's processes
	 * @return the process that {@code CLAUDE_PID} names, or empty if the variable is unset or blank
	 * @throws IllegalArgumentException if {@code CLAUDE_PID} is not a process identifier
	 * @throws IOException              if the process does not exist, or the operating system does not report when it
	 *                                  started
	 */
	static Optional<ProcessIdentity> claudeProcess(Map<String, String> environment, HarnessProcesses processes)
		throws IOException
	{
		String value = environment.get(PROCESS_VARIABLE);
		if (value == null || value.isBlank())
			return Optional.empty();
		long pid;
		try
		{
			pid = Long.parseLong(value.strip());
		}
		catch (NumberFormatException _)
		{
			throw new IllegalArgumentException(PROCESS_VARIABLE + " is not a process identifier: " + value);
		}
		Optional<ProcessDescription> process = processes.find(pid);
		if (process.isEmpty())
			throw new IOException("The Claude Code process " + pid + " that " + PROCESS_VARIABLE + " names has ended");
		return Optional.of(SessionLauncherStart.identity(process.get()));
	}

	/**
	 * Prevents instantiation.
	 */
	private ClaudeSessionStartHook()
	{
	}
}
