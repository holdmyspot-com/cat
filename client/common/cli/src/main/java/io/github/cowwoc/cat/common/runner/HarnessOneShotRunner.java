package io.github.cowwoc.cat.common.runner;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Starts an isolated harness process, sends one prompt, writes its response, and closes the process.
 */
public final class HarnessOneShotRunner
{
	/**
	 * Sends one prompt, durably persists its terminal output, and then cleans up the successful run.
	 *
	 * @param starter starts the isolated persistent session
	 * @param manager the owned-run lifecycle manager
	 * @param prompt the non-blank prompt
	 * @param output the durable output file outside the run directory
	 * @throws IOException if startup, execution, persistence, or cleanup fails
	 */
	public static void run(HarnessRunStarter starter, HarnessRunManager manager, String prompt, Path output)
		throws IOException
	{
		run(starter, manager, prompt, output, false);
	}

	/**
	 * Sends one prompt and conditionally retains its completed isolated run.
	 *
	 * @param starter starts the isolated persistent session
	 * @param manager the owned-run lifecycle manager
	 * @param prompt the non-blank prompt
	 * @param output the durable output file outside the run directory
	 * @param keepRun whether to retain a successful completed run
	 * @throws IOException if startup, execution, persistence, or cleanup fails
	 */
	public static void run(HarnessRunStarter starter, HarnessRunManager manager, String prompt, Path output,
		boolean keepRun) throws IOException
	{
		requireThat(starter, "starter").isNotNull();
		requireThat(manager, "manager").isNotNull();
		requireThat(prompt, "prompt").isNotBlank();
		requireThat(output, "output").isNotNull();
		HarnessRun run = null;
		try
		{
			HarnessProcessResult result;
			try (HarnessRunSession session = starter.start())
			{
				run = session.run();
				HarnessRun sessionRun = run;
				manager.markIdle(sessionRun);
				session.reserveTurn();
				manager.markWorking(sessionRun);
				result = session.sendTurn(prompt, event -> manager.appendTurnEvent(sessionRun, 1, event));
				manager.writeTurnOutput(sessionRun, 1, result);
			}
			if (result.exitCode() != 0)
				throw new IOException("Harness exited with status " + result.exitCode() + ": " + result.standardError());
			manager.complete(run, output, result.standardOutput(), keepRun);
		}
		catch (IOException e)
		{
			if (run != null)
			{
				String diagnostic = e.getMessage();
				if (diagnostic == null || diagnostic.isBlank())
					diagnostic = e.getClass().getSimpleName();
				manager.fail(run, diagnostic);
			}
			throw e;
		}
	}

	/**
	 * Prevents utility-class construction.
	 */
	private HarnessOneShotRunner()
	{
	}
}
