package io.github.cowwoc.cat.common;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Creates one named invocation directory below the platform temporary directory.
 */
public final class InvocationDirectory
{
	/**
	 * Creates one invocation directory and prints its path as a machine-readable value.
	 *
	 * @param args one workflow-name argument
	 */
	public static void main(String[] args)
	{
		try
		{
			if (args.length != 1 || !args[0].matches("[a-z][a-z0-9-]*"))
				throw new IllegalArgumentException("Usage: workflow-temp <lowercase-workflow-name>");
			Path directory = Files.createTempDirectory(Path.of(System.getProperty("java.io.tmpdir")),
				"cat-" + args[0] + "-");
			System.out.print("directory=" + directory.toAbsolutePath().normalize() + "\n");
		}
		catch (IllegalArgumentException | IOException e)
		{
			System.err.println(e.getMessage());
			System.exit(1);
		}
	}

	/**
	 * Prevents instantiation.
	 */
	private InvocationDirectory()
	{
	}
}
