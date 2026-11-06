package com.holdmyspot.cat.distribution.bundle.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.distribution.bundle.ToolInvocation;
import org.testng.annotations.Test;

/**
 * Tests {@link ToolInvocation}.
 */
public final class ToolInvocationTest
{
	/** Creates a test instance. */
	public ToolInvocationTest()
	{
	}

	/**
	 * Verifies that a failure names the tool and its status, and holds the tool's diagnostics: its standard error and
	 * then its standard output.
	 */
	@Test
	public void failureHoldsDiagnostics()
	{
		String message = ToolInvocation.failureMessage("jlink", 7, "bad module", "see the manual\n");

		requireThat(message, "message").isEqualTo("jlink failed with exit code 7: bad module" +
			System.lineSeparator() + "see the manual");
	}

	/**
	 * Verifies that a failure of a tool that wrote nothing names only the tool and its status.
	 */
	@Test
	public void failureWithoutDiagnosticsNamesStatus()
	{
		requireThat(ToolInvocation.failureMessage("javac", 2, "", ""), "message").
			isEqualTo("javac failed with exit code 2");
	}
}
