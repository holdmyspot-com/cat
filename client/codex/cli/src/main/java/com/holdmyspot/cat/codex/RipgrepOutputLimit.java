package com.holdmyspot.cat.codex;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Keeps the saved output of an {@code rg} search that {@code run-part} runs in PowerShell short, because the agent
 * reads that output and a search that matches long lines or too many lines would fill the agent's context.
 * <p>
 * In a POSIX shell, the plugin's {@code run-part-overrides/rg} script does this for every {@code rg} that writes
 * straight into the saved output. PowerShell cannot run that script, and cannot tell a program whether its output
 * reaches the saved output, so on Windows {@code run-part} does this itself, for a command that is one {@code rg}
 * search and nothing else, whose output is the whole saved output. Output that goes anywhere else, such as a pipe or a
 * file that the command names, is ripgrep's own. Keep the limits the same as the script's.
 */
public final class RipgrepOutputLimit
{
	/**
	 * The longest line, in bytes, that the agent receives whole; a longer line keeps its first 500 bytes.
	 */
	private static final int LINE_WIDTH = 500;
	/**
	 * The most lines that the agent receives from one search unless {@code CAT_RG_LINE_LIMIT} sets another limit.
	 */
	private static final int DEFAULT_LINE_LIMIT = 250;
	/**
	 * The environment variable that sets the most lines that the agent receives from one search; 0 means no limit.
	 */
	private static final String LINE_LIMIT_VARIABLE = "CAT_RG_LINE_LIMIT";
	/**
	 * The options that ask ripgrep for something other than a search, whose output is not search results.
	 */
	private static final Set<String> OTHER_OUTPUT = Set.of("-h", "--help", "-V", "--version", "--type-list");
	/**
	 * A whole number.
	 */
	private static final Pattern WHOLE_NUMBER = Pattern.compile("[0-9]+");
	/**
	 * The size of the buffer that counts the lines of the saved output.
	 */
	private static final int BUFFER_SIZE = 8192;

	/**
	 * Prevents construction.
	 */
	private RipgrepOutputLimit()
	{
	}

	/**
	 * Returns the PowerShell command that runs a search with each line of its output cut to its first 500 bytes.
	 *
	 * @param command the agent's PowerShell command
	 * @return the command with ripgrep's options to cut lines after {@code rg}, or empty if the command is not one
	 *   {@code rg} search and nothing else
	 * @throws NullPointerException if {@code command} is null
	 */
	public static Optional<String> limitedCommand(String command)
	{
		requireThat(command, "command").isNotNull();
		String stripped = command.strip();
		Optional<List<String>> words = CommandShell.POWERSHELL.simpleCommandWords(stripped);
		// PowerShell finds a program by its name in any letter case
		if (words.isEmpty() || !words.get().getFirst().equalsIgnoreCase("rg") ||
			!stripped.regionMatches(true, 0, "rg", 0, 2))
		{
			return Optional.empty();
		}
		for (String argument : words.get())
		{
			if (argument.equals("--"))
				break;
			if (OTHER_OUTPUT.contains(argument))
				return Optional.empty();
		}
		return Optional.of(stripped.substring(0, 2) + " --max-columns=" + LINE_WIDTH + " --max-columns-preview" +
			stripped.substring(2));
	}

	/**
	 * Returns the most lines that the agent receives from one search.
	 *
	 * @param environment the environment of {@code run-part}
	 * @return the value of {@code CAT_RG_LINE_LIMIT}, or 250 if it is unset; 0 means no limit
	 * @throws NullPointerException     if {@code environment} is null
	 * @throws IllegalArgumentException if {@code CAT_RG_LINE_LIMIT} is not a whole number
	 */
	public static int lineLimit(Map<String, String> environment)
	{
		requireThat(environment, "environment").isNotNull();
		String value = environment.get(LINE_LIMIT_VARIABLE);
		if (value == null)
			return DEFAULT_LINE_LIMIT;
		if (!WHOLE_NUMBER.matcher(value).matches())
		{
			throw new IllegalArgumentException(LINE_LIMIT_VARIABLE + " must be a whole number of lines, or 0 for no " +
				"limit, but was: " + value);
		}
		try
		{
			return Integer.parseInt(value);
		}
		catch (NumberFormatException _)
		{
			// A number too large for an int allows every search
			return 0;
		}
	}

	/**
	 * Replaces the saved output of a search that has more lines than the limit with a request to narrow the search.
	 *
	 * @param saved the file that saves the search's standard output and standard error
	 * @param status the search's exit status
	 * @param limit the most lines that the agent receives, or 0 for no limit
	 * @return {@code status}, or 2 if the output was replaced
	 * @throws NullPointerException if {@code saved} is null
	 * @throws IOException          if the file cannot be read or written
	 */
	public static int limitOutput(Path saved, int status, int limit) throws IOException
	{
		requireThat(saved, "saved").isNotNull();
		if (limit == 0 || lines(saved, limit) <= limit)
			return status;
		Files.writeString(saved, "rg: the search produced more than " + limit + " lines, so none are shown. Run it " +
			"again with a more specific pattern, a narrower path, or a --glob filter. To show every line, put " +
			"$env:" + LINE_LIMIT_VARIABLE + " = 0; before rg.\n");
		return 2;
	}

	/**
	 * Counts the lines of a file, up to one more than a limit.
	 *
	 * @param file the file
	 * @param limit the limit
	 * @return the number of lines, or {@code limit + 1} if the file has more than {@code limit} lines
	 * @throws IOException if the file cannot be read
	 */
	private static long lines(Path file, int limit) throws IOException
	{
		long lines = 0;
		byte last = '\n';
		byte[] buffer = new byte[BUFFER_SIZE];
		try (InputStream in = Files.newInputStream(file))
		{
			for (int count = in.read(buffer); count != -1; count = in.read(buffer))
			{
				for (int i = 0; i < count; ++i)
				{
					if (buffer[i] == '\n')
						++lines;
				}
				if (count > 0)
					last = buffer[count - 1];
				if (lines > limit)
					return lines;
			}
		}
		// A last line without a line break is a line too
		if (last != '\n')
			++lines;
		return lines;
	}
}
