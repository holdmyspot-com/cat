package com.holdmyspot.cat.distribution.bundle;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.List;
import java.util.spi.ToolProvider;

/**
 * Runs a JDK tool, such as {@code jlink} or {@code javac}, in this process and reports its failure.
 */
public final class ToolInvocation
{
	/**
	 * Prevents instantiation.
	 */
	private ToolInvocation()
	{
	}

	/**
	 * Runs a JDK tool.
	 *
	 * @param tool      the tool
	 * @param name      the tool's name, which a failure names
	 * @param arguments the tool's arguments
	 * @throws NullPointerException     if any argument is null
	 * @throws IllegalArgumentException if {@code name} is blank
	 * @throws IOException              if the tool exits with a status other than 0, with the message that
	 *                                  {@link #failureMessage(String, int, String, String)} gives
	 */
	public static void run(ToolProvider tool, String name, List<String> arguments) throws IOException
	{
		requireThat(tool, "tool").isNotNull();
		requireThat(name, "name").isNotBlank();
		requireThat(arguments, "arguments").isNotNull();

		StringWriter standardOutput = new StringWriter();
		StringWriter standardError = new StringWriter();
		int exitCode;
		try (PrintWriter out = new PrintWriter(standardOutput);
			PrintWriter err = new PrintWriter(standardError))
		{
			exitCode = tool.run(out, err, arguments.toArray(String[]::new));
		}
		if (exitCode != 0)
			throw new IOException(failureMessage(name, exitCode, standardError.toString(), standardOutput.toString()));
	}

	/**
	 * Returns the message that reports a tool's failure.
	 *
	 * @param name           the tool's name
	 * @param exitCode       the status that the tool exited with
	 * @param standardError  what the tool wrote to its standard error
	 * @param standardOutput what the tool wrote to its standard output
	 * @return the message, which names the tool and its status and, unless the tool wrote nothing, ends with its
	 *         standard error followed by its standard output, without the whitespace around them
	 * @throws NullPointerException if any argument is null
	 */
	public static String failureMessage(String name, int exitCode, String standardError, String standardOutput)
	{
		requireThat(name, "name").isNotNull();
		requireThat(standardError, "standardError").isNotNull();
		requireThat(standardOutput, "standardOutput").isNotNull();

		String diagnostics = (standardError + System.lineSeparator() + standardOutput).trim();
		String details = "";
		if (!diagnostics.isEmpty())
			details = ": " + diagnostics;
		return name + " failed with exit code " + exitCode + details;
	}
}
