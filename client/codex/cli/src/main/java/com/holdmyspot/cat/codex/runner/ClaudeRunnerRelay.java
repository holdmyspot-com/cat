package com.holdmyspot.cat.codex.runner;

import static com.holdmyspot.cat.codex.CodexPluginInfo.HARNESS_ID;
import static com.holdmyspot.cat.codex.runner.CodexRunnerCli.CLAUDE_ID;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.holdmyspot.cat.common.CommandLineArguments;
import com.holdmyspot.cat.common.JvmScope;
import com.holdmyspot.cat.common.MainJvmScope;
import com.holdmyspot.cat.common.Pause;
import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.runner.DirectLaunch;
import com.holdmyspot.cat.common.runner.HarnessRunnerOptions;
import com.holdmyspot.cat.common.runner.PluginDirectory;
import com.holdmyspot.cat.common.runner.ProcessTreeStop;
import com.holdmyspot.cat.common.runner.RequestedRunner;
import com.holdmyspot.cat.common.runner.RequestedSessionStarter;
import com.holdmyspot.cat.common.runner.RunnerRelay;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Runs {@code claude-runner} from the Codex plugin, so that a session can run Claude through the same launcher and
 * options as in the Claude plugin. Each launch goes along the route that
 * {@link com.holdmyspot.cat.common.runner.LaunchRoute} gives it: inside a run that may request sessions, it requests a
 * Claude session from the run's runner; when Codex runs the command, which it runs outside the session's sandbox, it
 * bounds the launch by that session's policy and has the runner of the Claude CAT artifact that
 * {@code ClaudeArtifact} selects serve the session within that bound; when only Claude Code runs it, it refuses the
 * launch and names the Claude Code plugin's runner; and otherwise it runs that runner with the caller's options.
 */
public final class ClaudeRunnerRelay
{
	/**
	 * Prints the usage, or runs one launch and exits with its status.
	 *
	 * @param args the runner options, which do not name {@code --artifact}
	 * @throws IOException if no artifact can be selected, or the runner cannot be run or the launch forwarded
	 */
	public static void main(String[] args) throws IOException
	{
		args = CommandLineArguments.recover(args);
		if (args.length == 1 && args[0].equals("--help"))
		{
			System.out.println(HarnessRunnerOptions.usage("claude-runner"));
			return;
		}
		Map<String, String> environment = Platform.current().environment(System.getenv());
		ProcessTreeStop.onExit();
		int status;
		try (JvmScope scope = new MainJvmScope(environment, Pause::sleeping))
		{
			status = RunnerRelay.relay(CLAUDE_ID, HARNESS_ID, CodexRunnerCli.MARKERS, args, environment,
				() -> ClaudeArtifact.execute(environment), (command, runnerEnvironment) ->
				{
					ProcessBuilder builder = new ProcessBuilder(command).inheritIO();
					builder.environment().clear();
					builder.environment().putAll(runnerEnvironment);
					try
					{
						return scope.executableFiles().run(builder).waitFor();
					}
					catch (InterruptedException e)
					{
						Thread.currentThread().interrupt();
						throw new IOException("Interrupted while waiting for the runner", e);
					}
				}, bound(scope, environment), new InputStreamReader(System.in, UTF_8), scope.waiting(), System.out,
				new PrintWriter(System.err, true, UTF_8));
		}
		System.exit(status);
	}

	/**
	 * Returns how the relay bounds a launch that a Codex session runs: by that session's policy, as
	 * {@link CodexDirectLaunch} identifies the session, and by what a Claude session needs, as the Claude artifact's
	 * runner reports it.
	 *
	 * @param scope       the process scope, which starts the runner that reports the needs
	 * @param environment the relay's environment
	 * @return the bound
	 */
	private static RunnerRelay.LaunchBound bound(JvmScope scope, Map<String, String> environment)
	{
		Path plugin = PluginDirectory.of(ClaudeRunnerRelay.class);
		return (runner, args, cleanup) ->
		{
			RequestedSessionStarter starter = (_, arguments, directory) -> new RequestedRunner(scope, runner,
				List.of()).start(arguments, directory, CodexRunnerCli.MARKERS.without(environment));
			DirectLaunch.SessionFinder sessions = DirectLaunch.refusingHomeProject(
				CodexDirectLaunch.sessions(environment), args, Path.of("").toAbsolutePath(), environment, Platform.current());
			return DirectLaunch.boundArguments(CLAUDE_ID, args, plugin, sessions,
				project -> CodexRunnerCli.toolScope(project, environment, scope.waiting()),
				DirectLaunch.runnerNeeds(starter, cleanup), () -> CodexDirectLaunch.sandboxed(
					plugin.resolve(PluginDirectory.BIN).resolve("claude-runner"), environment), cleanup);
		};
	}

	/**
	 * Prevents construction.
	 */
	private ClaudeRunnerRelay()
	{
	}
}
