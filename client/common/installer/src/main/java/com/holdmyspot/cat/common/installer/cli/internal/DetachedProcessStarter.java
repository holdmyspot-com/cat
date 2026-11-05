package com.holdmyspot.cat.common.installer.cli.internal;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.Platform;
import java.io.File;
import java.io.IOException;
import java.lang.ProcessBuilder.Redirect;
import java.util.List;

/**
 * Starts a program that outlives the installer's process. The program gets no input and its output is discarded, so
 * that its standard streams are none of those through which the installer's caller reads the installer's result. On
 * Windows the program still inherits the other handles that the installer may pass on, which {@link ExitDeletion}
 * accounts for.
 */
public final class DetachedProcessStarter implements ProcessStarter
{
	/**
	 * Creates the starter.
	 */
	public DetachedProcessStarter()
	{
	}

	@Override
	public void start(List<String> command) throws IOException
	{
		requireThat(command, "command").isNotEmpty();
		// Closing the process would wait for it to exit, and the program waits for the installer's process to exit
		new ProcessBuilder(command).
			redirectInput(Redirect.from(nullDevice())).
			redirectOutput(Redirect.DISCARD).
			redirectError(Redirect.DISCARD).
			start();
	}

	/**
	 * Returns the platform's file that reads as empty.
	 *
	 * @return the file
	 */
	private static File nullDevice()
	{
		if (Platform.current().isWindows())
			return new File("NUL");
		return new File("/dev/null");
	}
}
