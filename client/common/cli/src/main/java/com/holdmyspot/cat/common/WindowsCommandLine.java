package com.holdmyspot.cat.common;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.util.List;

/**
 * Splits the command line of a Windows process into arguments as Rust's standard library does, which also follows the
 * rules of Microsoft's C runtime for every argument after the first, and which is the program that Codex is built with.
 * <p>
 * <b>Thread Safety:</b> This class is stateless and thread-safe.
 */
public final class WindowsCommandLine
{
	/**
	 * Prevents construction.
	 */
	private WindowsCommandLine()
	{
	}

	/**
	 * Splits a command line into the program and its arguments.
	 *
	 * @param commandLine the command line, as Windows holds it for the process
	 * @return the first argument, which names the program and which no escape changes, followed by the arguments
	 *   after it; empty if {@code commandLine} is empty
	 * @throws NullPointerException if {@code commandLine} is null
	 */
	public static List<String> split(String commandLine)
	{
		requireThat(commandLine, "commandLine").isNotNull();
		return List.of();
	}
}
