package com.holdmyspot.cat.codex;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.codex.layout.CodexInstallation;
import com.holdmyspot.cat.codex.secrets.CodexSessionStartHookSecrets;
import com.holdmyspot.cat.common.AbstractHookScope;
import com.holdmyspot.cat.common.FileLocks;
import com.holdmyspot.cat.common.HookScope;
import com.holdmyspot.cat.common.Pause;
import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.SessionIgnore;
import com.holdmyspot.cat.common.Waiting;
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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Implements the Codex SessionStart hook protocol for CAT project rules.
 * <p>
 * Before it injects the rules, the hook starts the session launcher of the nearest Codex process among its
 * ancestors, as {@link SessionLauncherStart} describes, and does not wait for it.
 */
public final class CodexSessionStartHook
{
	static
	{
		CodexSessionStartHookSecrets.setCodexSessionStartHookAccess((args, input, output, error, environment,
			workingDirectory, waiting, git, platform, ancestry, starter) ->
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
			requireThat(ancestry, "ancestry").isNotNull();
			requireThat(starter, "starter").isNotNull();
			run(args, input, output, error, environment, workingDirectory, waiting, git, platform, ancestry, starter);
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
	 * Processes one Codex SessionStart event: starts the session launcher of the Codex process, then writes CAT's
	 * hook response.
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
			Platform.current(), () -> HarnessProcesses.ancestorsOf(ProcessHandle.current()),
			SessionLauncherStart.PROCESSES);
	}

	/**
	 * Processes one Codex SessionStart event: starts the session launcher of the Codex process, then writes CAT's
	 * hook response.
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
	 * @param ancestry         returns the hook's ancestor processes, nearest first
	 * @param starter          starts the session launcher
	 */
	private static void run(String[] args, InputStream input, OutputStream output, PrintStream error,
		Map<String, String> environment, Path workingDirectory, Waiting waiting, SessionIgnore.GitQueries git,
		Platform platform, Supplier<List<ProcessDescription>> ancestry, DetachedStarter starter)
	{
		byte[] bytes;
		try
		{
			bytes = input.readNBytes(AbstractHookScope.MAXIMUM_EVENT_BYTES + 1);
		}
		catch (IOException e)
		{
			error.println("CAT could not start its session launcher: " + e.getMessage());
			CodexRuleInjectionHook.run("SessionStart", args, input, output, error, environment, workingDirectory, waiting,
				git);
			return;
		}
		startLauncher(bytes, environment, workingDirectory, waiting, platform, ancestry, starter, error);
		CodexRuleInjectionHook.run("SessionStart", args, new ByteArrayInputStream(bytes), output, error, environment,
			workingDirectory, waiting, git);
	}

	/**
	 * Starts the session launcher of the Codex process that runs the hook. A hook without a Codex ancestor, one
	 * whose input is too large, or one whose environment names no usable plugin root starts none; the hook's rule
	 * injection reports the input and the environment.
	 *
	 * @param event            the hook input
	 * @param environment      the process environment
	 * @param workingDirectory the process working directory
	 * @param waiting          how the hook waits between the attempts of a retried operation
	 * @param platform         the platform that the hook runs on
	 * @param ancestry         returns the hook's ancestor processes, nearest first
	 * @param starter          starts the session launcher
	 * @param error            the diagnostic output, which reports why a launcher could not start
	 */
	private static void startLauncher(byte[] event, Map<String, String> environment, Path workingDirectory,
		Waiting waiting, Platform platform, Supplier<List<ProcessDescription>> ancestry, DetachedStarter starter,
		PrintStream error)
	{
		if (event.length > AbstractHookScope.MAXIMUM_EVENT_BYTES || !SessionLauncherStart.isWanted(platform,
			environment))
		{
			return;
		}
		Path pluginRoot;
		try (HookScope scope = CodexHookScope.open(environment, waiting))
		{
			pluginRoot = scope.pluginRoot();
		}
		catch (IOException _)
		{
			return;
		}
		try
		{
			Optional<ProcessDescription> codex = nearestCodex(ancestry.get());
			if (codex.isEmpty())
				return;
			Path codexHome = CodexInstallation.resolveHome(environment, platform.homeDirectory(environment),
				workingDirectory.toAbsolutePath());
			SessionLauncherStart.startFromHook(event, CodexPluginInfo.HARNESS_ID,
				SessionLauncherStart.identity(codex.get()), codexHome, pluginRoot, platform, environment,
				new FileLocks(), starter);
		}
		catch (IOException | IllegalArgumentException | UncheckedIOException e)
		{
			error.println("CAT could not start its session launcher: " + e.getMessage());
		}
	}

	/**
	 * Returns the Codex process that runs the hook: the nearest of the hook's ancestors whose executable is Codex's
	 * native binary, as Codex's policy reader also finds it.
	 *
	 * @param ancestors the hook's ancestor processes, nearest first
	 * @return the first process whose executable is named {@code codex} or {@code codex.exe}, or empty if none is
	 */
	private static Optional<ProcessDescription> nearestCodex(List<ProcessDescription> ancestors)
	{
		for (ProcessDescription process : ancestors)
		{
			String name = process.command().orElse("").replaceAll("^.*[/\\\\]", "");
			if (name.equals("codex") || name.equals("codex.exe"))
				return Optional.of(process);
		}
		return Optional.empty();
	}

	/**
	 * Prevents instantiation.
	 */
	private CodexSessionStartHook()
	{
	}
}
