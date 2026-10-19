package com.holdmyspot.cat.claude;

import com.holdmyspot.cat.common.CommandLineArguments;
import com.holdmyspot.cat.common.WaitUntil;
import java.nio.file.Path;
import java.util.List;

/**
 * Runs {@code wait-until} (see {@link WaitUntil}) with its commands in {@code bash}, the shell in which Claude Code
 * runs an agent's commands on every platform.
 */
public final class ClaudeWaitUntil
{
	/**
	 * Waits until the first operation finishes or the deadline passes, then exits with the wait's status.
	 *
	 * @param args the command-line arguments that {@link WaitUntil} describes
	 */
	public static void main(String[] args)
	{
		args = CommandLineArguments.recover(args);
		WaitUntil wait = new WaitUntil(WaitUntil.shellStarter(command -> List.of("bash", "-c", command),
			Path.of("").toAbsolutePath()), System.out, System.err);
		Runtime.getRuntime().addShutdownHook(new Thread(wait::terminate));
		int status;
		try
		{
			status = wait.run(List.of(args));
		}
		catch (InterruptedException _)
		{
			status = 130;
		}
		System.out.flush();
		System.exit(status);
	}

	/**
	 * Prevents instantiation.
	 */
	private ClaudeWaitUntil()
	{
	}
}
