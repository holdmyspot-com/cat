package com.holdmyspot.cat.common.installer.cli.internal.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.installer.cli.internal.ProcessTerminal;
import org.testng.annotations.Test;

/**
 * Tests how the installer tells whether a person reads its standard output.
 */
public final class ProcessTerminalTest
{
	/**
	 * Creates the test.
	 */
	public ProcessTerminalTest()
	{
	}

	/**
	 * The test runner collects the standard output of the process that runs this test through a pipe, so the process
	 * does not print to a terminal.
	 */
	@Test
	public void redirectedStdoutIsNotTerminal()
	{
		requireThat(new ProcessTerminal(Platform.current()).isStdoutTerminal(), "isStdoutTerminal").isFalse();
	}
}
