package com.holdmyspot.cat.common;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.util.ArrayList;
import java.util.List;

/**
 * Splits the command line of a Windows process into arguments as Rust's standard library does, which also follows the
 * rules of Microsoft's C runtime for every argument after the first, and which is the program that Codex is built with.
 * <p>
 * The first argument, which names the program, takes no escapes: a quote starts or ends a quoted part, and a space or
 * tab outside quotes ends it. In every later argument:
 * <ul>
 * <li>A space or tab outside quotes ends the argument, and a run of them is one separator.</li>
 * <li>A quote starts or ends a quoted part, and two quotes in a row inside a quoted part are one literal quote.</li>
 * <li>Backslashes that precede a quote are halved, rounding down, and an odd number makes the quote literal.</li>
 * <li>Backslashes that no quote follows stay as they are.</li>
 * </ul>
 * <p>
 * <b>Thread Safety:</b> This class is stateless and thread-safe.
 */
public final class WindowsCommandLine
{
	private static final char QUOTE = '"';
	private static final char BACKSLASH = '\\';

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
		List<String> arguments = new ArrayList<>();
		if (commandLine.isEmpty())
			return arguments;
		int length = commandLine.length();
		StringBuilder current = new StringBuilder();
		boolean inQuotes = false;
		int index = 0;
		while (index < length)
		{
			char next = commandLine.charAt(index);
			++index;
			if (next == QUOTE)
				inQuotes = !inQuotes;
			else if (isSeparator(next) && !inQuotes)
				break;
			else
				current.append(next);
		}
		index = skipSeparators(commandLine, index);
		arguments.add(current.toString());

		current.setLength(0);
		inQuotes = false;
		while (index < length)
		{
			char next = commandLine.charAt(index);
			++index;
			if (isSeparator(next) && !inQuotes)
			{
				arguments.add(current.toString());
				current.setLength(0);
				index = skipSeparators(commandLine, index);
			}
			else if (next == BACKSLASH)
			{
				int backslashes = 1;
				while (index < length && commandLine.charAt(index) == BACKSLASH)
				{
					++backslashes;
					++index;
				}
				boolean beforeQuote = index < length && commandLine.charAt(index) == QUOTE;
				if (beforeQuote)
				{
					current.append(String.valueOf(BACKSLASH).repeat(backslashes / 2));
					boolean escapesQuote = backslashes % 2 == 1;
					if (escapesQuote)
					{
						current.append(QUOTE);
						++index;
					}
				}
				else
					current.append(String.valueOf(BACKSLASH).repeat(backslashes));
			}
			else if (next == QUOTE)
			{
				if (!inQuotes)
					inQuotes = true;
				else if (index < length && commandLine.charAt(index) == QUOTE)
				{
					current.append(QUOTE);
					++index;
				}
				else if (index < length)
					inQuotes = false;
				else
				{
					// The line ends after the closing quote, so an empty quoted argument is still an argument
					break;
				}
			}
			else
				current.append(next);
		}
		if (current.length() > 0 || inQuotes)
			arguments.add(current.toString());
		return arguments;
	}

	/**
	 * Indicates whether a character separates arguments when it is outside quotes.
	 *
	 * @param character the character
	 * @return {@code true} for a space or tab
	 */
	private static boolean isSeparator(char character)
	{
		return character == ' ' || character == '\t';
	}

	/**
	 * Skips spaces and tabs.
	 *
	 * @param commandLine the command line
	 * @param start       the index to start at
	 * @return the index of the first character at or after {@code start} that is not a space or tab
	 */
	private static int skipSeparators(String commandLine, int start)
	{
		int index = start;
		while (index < commandLine.length() && isSeparator(commandLine.charAt(index)))
			++index;
		return index;
	}
}
