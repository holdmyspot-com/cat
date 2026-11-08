package com.holdmyspot.cat.common.installer.cli.internal.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.Platform;

/**
 * The entry command that an installer message tells the user to run, as it names the harness on the platform that runs
 * the tests.
 */
final class EntryCommands
{
	/**
	 * Prevents instantiation.
	 */
	private EntryCommands()
	{
	}

	/**
	 * Returns the entry command that an installer running on this platform names for a harness, without the variables
	 * that the command may set before it: the PowerShell script on Windows and the POSIX shell script elsewhere.
	 *
	 * @param harness the harness
	 * @return the command
	 * @throws NullPointerException     if {@code harness} is null
	 * @throws IllegalArgumentException if {@code harness} is blank
	 */
	static String forHarness(String harness)
	{
		requireThat(harness, "harness").isNotBlank();
		if (Platform.current().isWindows())
			return "cat.ps1 -Harness " + harness;
		return "cat.sh --harness " + harness;
	}
}
