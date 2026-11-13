package com.holdmyspot.cat.codex.optimizer;

import java.nio.file.Path;
import com.holdmyspot.cat.common.CodeLoadingVariables;
import com.holdmyspot.cat.common.runner.policy.HarnessProcesses;
import com.holdmyspot.cat.common.runner.policy.ProcessDescription;
import com.holdmyspot.cat.common.runner.RequestServer;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Queues a plain completion note to the validated host thread, using the host's own executable rather than PATH.
 */
public final class CodexExitNotice
{
	/**
	 * Starts the bounded queue command. Tests provide an in-memory launcher.
	 */
	@FunctionalInterface
	public interface Launcher
	{
		/**
		 * Executes the queue command.
		 *
		 * @param arguments the command tokens
		 * @param environment the validated host environment
		 * @return the exit code
		 * @throws IOException if the command cannot run
		 * @throws InterruptedException if the wait is interrupted
		 */
		int run(List<String> arguments, Map<String, String> environment) throws IOException, InterruptedException;
	}

	/**
	 * Prevents instantiation.
	 */
	private CodexExitNotice()
	{
	}

	/**
	 * Queues one note; an unsuccessful write is a notification failure, never a campaign failure.
	 *
	 * @param executable the absolute executable of the host Codex process
	 * @param thread the thread validated against its protected rollout
	 * @param campaign the campaign identifier
	 * @param environment the validated environment
	 * @param launcher executes the command
	 * @throws IOException if the queue write fails
	 * @throws InterruptedException if waiting is interrupted
	 */
	public static void send(String executable, String thread, String campaign, Map<String, String> environment,
		Launcher launcher) throws IOException, InterruptedException
	{
		if (executable == null || thread == null || campaign == null ||
			!Path.of(executable).isAbsolute() ||
			!thread.matches("[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}") ||
			!campaign.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,63}"))
			throw new IOException("The exit notice has an invalid executable, thread or campaign identifier.");
		String note = "Campaign " + campaign + " background run ended. Call prompt-optimizer status " + campaign +
			" for the result.";
		int exit = launcher.run(List.of(executable, "queue", "--thread", thread, "--message", note), environment);
		if (exit != 0)
			throw new IOException("codex queue exited with status " + exit + ". Call `status` for the campaign result.");
	}

	/**
	 * Sends through the identified host process and a bounded real queue process.
	 *
	 * @param session the validated host
	 * @param environment its validated environment
	 * @param campaign the campaign identifier
	 * @throws IOException if queuing fails
	 * @throws InterruptedException if waiting is interrupted
	 */
	public static void send(RequestServer.Session session, Map<String, String> environment, String campaign)
		throws IOException, InterruptedException
	{
		String executable = HarnessProcesses.system().find(session.harness().pid()).flatMap(
			ProcessDescription::command).orElseThrow(() -> new IOException("The host executable is no longer available."));
		send(executable, environment.get("CODEX_THREAD_ID"), campaign, environment, (arguments, variables) ->
		{
			ProcessBuilder builder = new ProcessBuilder(arguments).redirectOutput(ProcessBuilder.Redirect.DISCARD).
				redirectError(ProcessBuilder.Redirect.DISCARD);
			builder.environment().clear();
			for (String name : List.of(CodexOptimizerCommandScope.HOME_VARIABLE, "CODEX_HOME", "SystemRoot"))
				if (variables.containsKey(name))
					builder.environment().put(name, variables.get(name));
			Process process = CodeLoadingVariables.start(builder);
			try
			{
				if (!process.waitFor(30, TimeUnit.SECONDS))
					throw new IOException("codex queue did not end within 30 seconds.");
				return process.exitValue();
			}
			finally
			{
				if (process.isAlive())
					process.destroyForcibly();
			}
		});
	}
}
