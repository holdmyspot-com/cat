package io.github.cowwoc.cat.common.optimizer.contract;

import java.util.ArrayList;
import java.util.List;

/**
 * Writes and reads Markdown code spans, following CommonMark: a span opens with a run of backticks and closes with the
 * next run of the same length, and one space is removed from each end of content that both starts and ends with one.
 */
final class CodeSpan
{
	/**
	 * Prevents instantiation.
	 */
	private CodeSpan()
	{
	}

	/**
	 * Returns a code span that reads back as a text.
	 *
	 * @param content the text, on one line
	 * @return the code span, delimited by one backtick more than the longest run of backticks in {@code content}
	 */
	static String render(String content)
	{
		int longest = 0;
		int run = 0;
		for (int i = 0; i < content.length(); ++i)
		{
			if (content.charAt(i) == '`')
			{
				++run;
				longest = Math.max(longest, run);
			}
			else
				run = 0;
		}
		String delimiter = "`".repeat(longest + 1);
		boolean padded = content.startsWith("`") || content.endsWith("`") ||
			(content.startsWith(" ") && content.endsWith(" ") && !content.isBlank());
		if (padded)
			return delimiter + " " + content + " " + delimiter;
		return delimiter + content + delimiter;
	}

	/**
	 * Reads code spans separated by commas, such as {@code `a`, `b`}.
	 *
	 * @param text the text
	 * @return the content of each span, in order
	 * @throws IllegalArgumentException if {@code text} holds anything other than code spans separated by commas, or no
	 *                                  code span
	 */
	static List<String> parseList(String text)
	{
		List<String> spans = new ArrayList<>();
		int i = skipWhitespace(text, 0);
		while (true)
		{
			if (i == text.length() || text.charAt(i) != '`')
			{
				throw new IllegalArgumentException("'" + text + "' must be code spans separated by commas, such as " +
					"`fixtures/**`, `../fixtures/AGENTS.md`");
			}
			int open = i;
			while (i < text.length() && text.charAt(i) == '`')
				++i;
			int length = i - open;
			int close = closing(text, i, length);
			if (close == -1)
				throw new IllegalArgumentException("'" + text + "' has a code span that is not closed");
			spans.add(content(text.substring(i, close)));
			i = skipWhitespace(text, close + length);
			if (i == text.length())
				return spans;
			if (text.charAt(i) != ',')
			{
				throw new IllegalArgumentException("'" + text + "' must be code spans separated by commas, such as " +
					"`fixtures/**`, `../fixtures/AGENTS.md`");
			}
			i = skipWhitespace(text, i + 1);
		}
	}

	/**
	 * Returns the index after the whitespace that starts at an index.
	 *
	 * @param text  the text
	 * @param start the index
	 * @return the index of the first character that is not whitespace, or the text's length
	 */
	private static int skipWhitespace(String text, int start)
	{
		int i = start;
		while (i < text.length() && Character.isWhitespace(text.charAt(i)))
			++i;
		return i;
	}

	/**
	 * Finds the run of backticks that closes a code span.
	 *
	 * @param text   the text
	 * @param start  the index after the opening run
	 * @param length the length of the opening run
	 * @return the index of the closing run, or {@code -1} if no run of that length follows
	 */
	private static int closing(String text, int start, int length)
	{
		int i = start;
		while (i < text.length())
		{
			if (text.charAt(i) != '`')
			{
				++i;
				continue;
			}
			int run = i;
			while (i < text.length() && text.charAt(i) == '`')
				++i;
			if (i - run == length)
				return run;
		}
		return -1;
	}

	/**
	 * Returns the content of a code span from the text between its delimiters.
	 *
	 * @param between the text between the delimiters
	 * @return the text without one space at each end if it both starts and ends with one and is not only spaces
	 */
	private static String content(String between)
	{
		if (between.length() >= 2 && between.startsWith(" ") && between.endsWith(" ") && !between.isBlank())
			return between.substring(1, between.length() - 1);
		return between;
	}
}
