package com.holdmyspot.cat.codex.runner;

import static java.nio.charset.StandardCharsets.UTF_8;

import com.holdmyspot.cat.codex.policy.CodexParentPolicy;
import com.holdmyspot.cat.codex.policy.CodexPolicyReader;
import com.holdmyspot.cat.codex.policy.CodexSystemFiles;
import com.holdmyspot.cat.common.CommandLineArguments;
import com.holdmyspot.cat.common.ExecutableFiles;
import com.holdmyspot.cat.common.Pause;
import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.runner.HarnessRunnerOptions;
import com.holdmyspot.cat.common.runner.RunnerRelay;
import com.holdmyspot.cat.common.runner.policy.ParentPolicySource;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.nio.file.Path;
import java.util.Map;

/**
 * Runs {@code claude-runner} from the Codex plugin, so that a session can run Claude through the same launcher and
 * options as in the Claude plugin. Outside a run it starts the session in place, inside the sandbox of the Codex
 * session whose command runs the launcher, through the runner of the Claude CAT artifact that {@code ClaudeArtifact}
 * selects; inside a run that may request sessions, it requests a Claude session from the run's runner.
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
		ParentPolicySource parent = new CodexParentPolicy(new CodexPolicyReader(CodexSystemFiles.ofThisMachine(
			System.getProperty("user.name", ""))), environment, Path.of(System.getProperty("user.home")).
			toAbsolutePath(), Path.of("").toAbsolutePath());
		String[] launch = args;
		System.exit(HarnessRunnerOptions.refusingInvalidArguments(() -> RunnerRelay.relay("claude", launch, environment,
			parent, () -> ClaudeArtifact.execute(environment), (command, runnerEnvironment) ->
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
			}, new InputStreamReader(System.in, UTF_8), Pause::sleeping, System.out, errors()), errors()));
	}

	/**
	 * Returns a writer for the launcher's standard error that flushes each line.
	 *
	 * @return the writer
	 */
	private static PrintWriter errors()
	{
		return new PrintWriter(System.err, true, UTF_8);
	}

	/**
	 * Prevents construction.
	 */
	private ClaudeRunnerRelay()
	{
	}
}
