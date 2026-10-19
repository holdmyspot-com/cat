package com.holdmyspot.cat.codex;

import com.holdmyspot.cat.common.CommandLineArguments;
import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.WaitUntil;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Runs {@code wait-until} (see {@link WaitUntil}) with its commands in the shell in which Codex runs an agent's
 * commands: {@code bash} on Linux and macOS, and PowerShell on Windows.
 */
public final class CodexWaitUntil
{
	/**
	 * Waits until the first operation finishes or the deadline passes, then exits with the wait's status.
	 *
	 * @param args the command-line arguments that {@link WaitUntil} describes
	 */
	public static void main(String[] args)
	{
		args = CommandLineArguments.recover(args);
		CommandShell shell = CommandShell.current();
		Map<String, String> environment = Platform.current().environment(System.getenv());
		WaitUntil wait = new WaitUntil(WaitUntil.shellStarter(command -> shell.processArguments(command, environment),
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
	private CodexWaitUntil()
	{
	}
}
