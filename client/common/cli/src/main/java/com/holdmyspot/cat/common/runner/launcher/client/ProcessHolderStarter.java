package com.holdmyspot.cat.common.runner.launcher.client;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.ExecutableFiles.ProcessLauncher;
import com.holdmyspot.cat.common.runner.SessionLauncherChannel;
import com.holdmyspot.cat.common.secrets.ProcessHolderStarterSecrets;
import java.io.IOException;
import java.lang.ProcessBuilder.Redirect;
import java.util.ArrayList;
import java.util.List;

/**
 * Starts holders as processes of the holder shell, which read their standard input until it closes.
 * <p>
 * <b>Thread Safety:</b> This class is immutable and thread-safe.
 */
public final class ProcessHolderStarter implements HolderStarter
{
	static
	{
		ProcessHolderStarterSecrets.setProcessHolderStarterAccess(ProcessHolderStarter::new);
	}

	/**
	 * Starts each configured holder process.
	 */
	private final ProcessLauncher launcher;

	/**
	 * Creates a starter of operating-system processes.
	 */
	ProcessHolderStarter()
	{
		this(ProcessBuilder::start);
	}

	/**
	 * Creates a starter that starts each configured holder process through the supplied launcher.
	 *
	 * @param launcher starts the configured holder process
	 * @throws NullPointerException if {@code launcher} is null
	 */
	private ProcessHolderStarter(ProcessLauncher launcher)
	{
		requireThat(launcher, "launcher").isNotNull();
		this.launcher = launcher;
	}

	@Override
	public Holder start(List<String> arguments) throws IOException
	{
		requireThat(arguments, "arguments").isNotNull();
		List<String> command = new ArrayList<>();
		command.add(SessionLauncherChannel.HOLDER_SHELL.toString());
		command.addAll(arguments);
		return new ProcessHolder(launcher.start(new ProcessBuilder(command).redirectOutput(Redirect.DISCARD).
			redirectError(Redirect.DISCARD)));
	}

	/**
	 * A holder process, which ends when its standard input closes.
	 * <p>
	 * <b>Thread Safety:</b> This class is thread-safe.
	 */
	private static final class ProcessHolder implements Holder
	{
		/**
		 * The holder's process.
		 */
		private final Process process;

		/**
		 * Wraps a started holder process.
		 *
		 * @param process the process
		 */
		ProcessHolder(Process process)
		{
			this.process = process;
		}

		@Override
		public void close()
		{
			try
			{
				process.getOutputStream().close();
			}
			catch (IOException _)
			{
				// A holder whose input cannot be closed has already ended
				process.destroy();
			}
		}
	}
}
