package com.holdmyspot.cat.common.updater.cli.internal.test;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Stands in for a harness program: reports its arguments, working directory, two environment variables and whether
 * its standard input is closed, writes a diagnostic to standard error and exits with status 4. It is compiled with the
 * tests, so running it starts a JVM without compiling a source file first.
 */
public final class FakeHarnessProgram
{
	/**
	 * Prevents construction.
	 */
	private FakeHarnessProgram()
	{
	}

	/**
	 * Reports what the program received and exits with status 4.
	 *
	 * @param args the program's arguments
	 * @throws IOException if standard input cannot be read
	 */
	public static void main(String[] args) throws IOException
	{
		for (String argument : args)
			System.out.println("arg=" + argument);
		System.out.println("directory=" + Path.of("").toAbsolutePath());
		System.out.println("value=" + System.getenv("CAT_TEST_VALUE"));
		System.out.println("path=" + System.getenv("PATH"));
		System.out.println("input=" + System.in.read());
		System.err.println("diagnostic");
		System.exit(4);
	}
}
