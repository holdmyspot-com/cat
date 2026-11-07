package com.holdmyspot.cat.claude.runner;

import static com.holdmyspot.cat.claude.ClaudePluginInfo.HARNESS_ID;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.holdmyspot.cat.common.CommandLineArguments;
import com.holdmyspot.cat.common.ExecutableFiles;
import com.holdmyspot.cat.common.Pause;
import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.runner.HarnessRunnerOptions;
import com.holdmyspot.cat.common.runner.RunnerRelay;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.util.Map;

/**
 * Runs {@code codex-runner} from the Claude plugin, so that a session can run Codex through the same launcher and
 * options as in the Codex plugin. Each launch goes along the route that
 * {@link com.holdmyspot.cat.common.runner.LaunchRoute} gives it: inside a run that may request sessions, it requests a
 * Codex session from the run's runner; when Claude Code runs the command, it sends the launch to the Claude Code
 * process's session launcher; when only Codex runs it, it refuses the launch and names the Codex plugin's runner;
 * and otherwise it runs the runner of the Codex CAT artifact that {@code CodexArtifact} selects.
 */
public final class CodexRunnerRelay
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
			System.out.println(HarnessRunnerOptions.usage("codex-runner"));
			return;
		}
		Map<String, String> environment = Platform.current().environment(System.getenv());
		System.exit(RunnerRelay.relay("codex", HARNESS_ID, ClaudeRunnerCli.runningHarnesses(environment),
			args, environment, () -> CodexArtifact.execute(environment),
			(command, runnerEnvironment) ->
			{
				ProcessBuilder builder = new ProcessBuilder(command).inheritIO();
				builder.environment().clear();
				builder.environment().putAll(runnerEnvironment);
				try
				{
					return ExecutableFiles.retrying(Pause::sleeping).run(builder).waitFor();
				}
				catch (InterruptedException e)
				{
					Thread.currentThread().interrupt();
					throw new IOException("Interrupted while waiting for the runner", e);
				}
			}, new InputStreamReader(System.in, UTF_8), Pause::sleeping, System.out,
			new PrintWriter(System.err, true, UTF_8)));
	}

	/**
	 * Prevents construction.
	 */
	private CodexRunnerRelay()
	{
	}
}
