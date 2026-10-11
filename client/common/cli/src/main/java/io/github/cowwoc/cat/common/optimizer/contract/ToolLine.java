package io.github.cowwoc.cat.common.optimizer.contract;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Parses the {@code tool} of an assertion into a {@link BuiltInTool}.
 * <p>
 * Arguments are separated by whitespace. An argument that starts with a double quote extends to the next unescaped
 * double quote and may hold whitespace; inside it, {@code \"} stands for a double quote and {@code \\} for a backslash,
 * and every other backslash is kept, so that {@code "\d"} is the regular expression {@code \d}. An unquoted argument is
 * taken as written, backslashes included, and may not hold a double quote.
 */
final class ToolLine
{
	/**
	 * The prefix of the optional first argument of a reply tool that limits it to one turn.
	 */
	private static final String TURN_PREFIX = "turn=";
	/**
	 * The largest percentage that {@code read-at-most} accepts.
	 */
	private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
	/**
	 * The parser of each tool's arguments, by the tool's name.
	 */
	private static final Map<String, Parser> PARSERS = Map.ofEntries(
		Map.entry("exact-text", new Parser(true, 1, line -> new BuiltInTool.ExactText(line.turn(), line.text(0)))),
		Map.entry("forbidden-text", new Parser(true, 1,
			line -> new BuiltInTool.ForbiddenText(line.turn(), line.text(0)))),
		Map.entry("pattern", new Parser(true, 1, line -> new BuiltInTool.PatternMatch(line.turn(), line.text(0)))),
		Map.entry("count", new Parser(true, 3, ToolLine::count)),
		Map.entry("file-exists", new Parser(false, 1, line -> new BuiltInTool.FileExists(line.text(0)))),
		Map.entry("file-absent", new Parser(false, 1, line -> new BuiltInTool.FileAbsent(line.text(0)))),
		Map.entry("file-content", new Parser(false, 2,
			line -> new BuiltInTool.FileContent(line.text(0), line.text(1)))),
		Map.entry("exit-status", new Parser(false, 2,
			line -> new BuiltInTool.ExitStatus(line.text(0), line.wholeNumber(1, "status")))),
		Map.entry("tool-used", new Parser(false, 1, line -> new BuiltInTool.ToolUsed(line.text(0)))),
		Map.entry("tool-unused", new Parser(false, 1, line -> new BuiltInTool.ToolUnused(line.text(0)))),
		Map.entry("no-tool-call", new Parser(false, 0, _ -> new BuiltInTool.NoToolCall())),
		Map.entry("read-in-full", new Parser(false, 1, line -> new BuiltInTool.ReadInFull(line.text(0)))),
		Map.entry("read-at-most", new Parser(false, 2, ToolLine::readAtMost)));

	/**
	 * How to parse the arguments of one tool.
	 *
	 * @param replies {@code true} if the tool examines replies and accepts a leading {@code turn=<n>}
	 * @param arguments the number of arguments after the name and any {@code turn=<n>}
	 * @param create creates the tool from its arguments
	 */
	private record Parser(boolean replies, int arguments, Function<Parsed, BuiltInTool> create)
	{
	}

	/**
	 * One argument of a line.
	 *
	 * @param text the argument, with its quotes removed and its escapes replaced
	 * @param quoted {@code true} if the argument was written in double quotes
	 */
	private record Token(String text, boolean quoted)
	{
	}

	/**
	 * The arguments of a tool after its name and any {@code turn=<n>}.
	 *
	 * @param name the tool's name
	 * @param turn the turn that a reply tool examines, or {@code null} for every turn
	 * @param arguments the remaining arguments
	 */
	private record Parsed(String name, Integer turn, List<String> arguments)
	{
		/**
		 * Returns an argument.
		 *
		 * @param index the argument's index
		 * @return the argument
		 */
		String text(int index)
		{
			return arguments.get(index);
		}

		/**
		 * Returns an argument that must be a whole number.
		 *
		 * @param index the argument's index
		 * @param parameter the parameter's name
		 * @return the number
		 * @throws IllegalArgumentException if the argument is not a whole number
		 */
		int wholeNumber(int index, String parameter)
		{
			String text = arguments.get(index);
			try
			{
				return Integer.parseInt(text);
			}
			catch (NumberFormatException e)
			{
				throw new IllegalArgumentException(name + "'s " + parameter + " must be a whole number, but it is \"" +
					text + "\"", e);
			}
		}
	}

	/**
	 * Splits a line into its arguments.
	 *
	 * @param line the line
	 * @return the arguments, with quotes removed and escapes replaced
	 * @throws NullPointerException if {@code line} is null
	 * @throws IllegalArgumentException if a quoted argument is unterminated or is followed by other text, or if an
	 *                                  unquoted argument holds a double quote
	 */
	static List<String> arguments(String line)
	{
		return tokens(line).stream().map(Token::text).toList();
	}

	/**
	 * Parses a line.
	 *
	 * @param line the line
	 * @return the tool
	 * @throws NullPointerException if {@code line} is null
	 * @throws IllegalArgumentException if the line is not a valid built-in tool
	 */
	static BuiltInTool parse(String line)
	{
		List<Token> tokens = tokens(line);
		if (tokens.isEmpty())
			throw new IllegalArgumentException("The line names no built-in tool");
		String name = tokens.getFirst().text();
		Parser parser = PARSERS.get(name);
		if (parser == null || tokens.getFirst().quoted())
		{
			throw new IllegalArgumentException("There is no built-in tool named " + name + "; the built-in tools are " +
				String.join(", ", PARSERS.keySet().stream().sorted().toList()));
		}
		List<Token> remaining = tokens.subList(1, tokens.size());
		Integer turn = null;
		if (!remaining.isEmpty() && !remaining.getFirst().quoted() &&
			remaining.getFirst().text().startsWith(TURN_PREFIX))
		{
			if (!parser.replies())
			{
				throw new IllegalArgumentException(TURN_PREFIX + "<n> applies only to the built-in tools that examine " +
					"replies, not to " + name);
			}
			turn = turn(remaining.getFirst().text());
			remaining = remaining.subList(1, remaining.size());
		}
		if (remaining.size() != parser.arguments())
		{
			String noun = "arguments";
			if (parser.arguments() == 1)
				noun = "argument";
			throw new IllegalArgumentException(name + " takes " + parser.arguments() + " " + noun +
				" after its name, but the line has " + remaining.size());
		}
		return parser.create().apply(new Parsed(name, turn, remaining.stream().map(Token::text).toList()));
	}

	/**
	 * Parses the turn of {@code turn=<n>}.
	 *
	 * @param argument the argument
	 * @return the turn
	 * @throws IllegalArgumentException if the turn is not a positive whole number
	 */
	private static int turn(String argument)
	{
		String text = argument.substring(TURN_PREFIX.length());
		try
		{
			int turn = Integer.parseInt(text);
			if (turn >= 1)
				return turn;
		}
		catch (NumberFormatException _)
		{
			// Reported below
		}
		throw new IllegalArgumentException("The turn of " + argument + " must be a positive whole number");
	}

	/**
	 * Creates a {@code count} tool.
	 *
	 * @param line the arguments
	 * @return the tool
	 * @throws IllegalArgumentException if a bound is not a non-negative whole number, or the minimum exceeds the
	 *                                  maximum
	 */
	private static BuiltInTool count(Parsed line)
	{
		int minimum = line.wholeNumber(1, "minimum");
		int maximum = line.wholeNumber(2, "maximum");
		return new BuiltInTool.Count(line.turn(), line.text(0), minimum, maximum);
	}

	/**
	 * Creates a {@code read-at-most} tool.
	 *
	 * @param line the arguments
	 * @return the tool
	 * @throws IllegalArgumentException if the percent is not a number from 0 to 100
	 */
	private static BuiltInTool readAtMost(Parsed line)
	{
		String text = line.text(1);
		BigDecimal percent;
		try
		{
			percent = new BigDecimal(text);
		}
		catch (NumberFormatException e)
		{
			throw new IllegalArgumentException("read-at-most's percent must be a number from 0 to 100, but it is \"" +
				text + "\"", e);
		}
		return new BuiltInTool.ReadAtMost(line.text(0), percent);
	}

	/**
	 * Rejects a percentage outside 0 to 100.
	 *
	 * @param percent the percentage
	 * @throws IllegalArgumentException if {@code percent} is below 0 or above 100
	 */
	static void requirePercent(BigDecimal percent)
	{
		if (percent.signum() < 0 || percent.compareTo(HUNDRED) > 0)
		{
			throw new IllegalArgumentException("read-at-most's percent must be a number from 0 to 100, but it is " +
				percent.toPlainString());
		}
	}

	/**
	 * Splits a line into its arguments.
	 *
	 * @param line the line
	 * @return the arguments
	 * @throws NullPointerException if {@code line} is null
	 * @throws IllegalArgumentException if a quoted argument is unterminated or is followed by other text, or if an
	 *                                  unquoted argument holds a double quote
	 */
	private static List<Token> tokens(String line)
	{
		List<Token> tokens = new ArrayList<>();
		int length = line.length();
		int index = 0;
		while (index < length)
		{
			if (Character.isWhitespace(line.charAt(index)))
			{
				++index;
				continue;
			}
			if (line.charAt(index) == '"')
				index = readQuoted(line, index, tokens);
			else
				index = readUnquoted(line, index, tokens);
		}
		return tokens;
	}

	/**
	 * Reads a quoted argument.
	 *
	 * @param line the line
	 * @param start the index of the opening quote
	 * @param tokens the arguments read so far, which receive this one
	 * @return the index after the closing quote
	 * @throws IllegalArgumentException if the argument is unterminated or other text follows its closing quote
	 */
	private static int readQuoted(String line, int start, List<Token> tokens)
	{
		StringBuilder text = new StringBuilder();
		int index = start + 1;
		while (true)
		{
			if (index >= line.length())
			{
				throw new IllegalArgumentException("The quoted argument at character " + (start + 1) +
					" is unterminated");
			}
			char next = line.charAt(index);
			if (next == '"')
				break;
			if (next == '\\' && index + 1 < line.length() && (line.charAt(index + 1) == '"' ||
				line.charAt(index + 1) == '\\'))
			{
				text.append(line.charAt(index + 1));
				index += 2;
				continue;
			}
			text.append(next);
			++index;
		}
		++index;
		if (index < line.length() && !Character.isWhitespace(line.charAt(index)))
		{
			throw new IllegalArgumentException("Text follows the closing quote of the argument at character " +
				(start + 1) + "; separate arguments with a space");
		}
		tokens.add(new Token(text.toString(), true));
		return index;
	}

	/**
	 * Reads an unquoted argument.
	 *
	 * @param line the line
	 * @param start the index of its first character
	 * @param tokens the arguments read so far, which receive this one
	 * @return the index after the argument
	 * @throws IllegalArgumentException if the argument holds a double quote
	 */
	private static int readUnquoted(String line, int start, List<Token> tokens)
	{
		int index = start;
		while (index < line.length() && !Character.isWhitespace(line.charAt(index)))
		{
			if (line.charAt(index) == '"')
			{
				throw new IllegalArgumentException("The unquoted argument at character " + (start + 1) +
					" holds a double quote; quote the whole argument and write \\\" for the quote");
			}
			++index;
		}
		tokens.add(new Token(line.substring(start, index), false));
		return index;
	}

	/**
	 * Prevents instantiation.
	 */
	private ToolLine()
	{
	}
}
