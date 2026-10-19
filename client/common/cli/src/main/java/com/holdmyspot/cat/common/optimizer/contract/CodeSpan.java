package com.holdmyspot.cat.common.optimizer.contract;

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
	 * Reads one code span, such as {@code `a`}, with optional whitespace around it.
	 *
	 * @param text the text
	 * @return the span's content
	 * @throws IllegalArgumentException if {@code text} holds anything other than one code span
	 */
	static String parse(String text)
	{
		int i = skipWhitespace(text, 0);
		if (i == text.length() || text.charAt(i) != '`')
			throw new IllegalArgumentException("'" + text + "' must be one code span");
		int open = i;
		while (i < text.length() && text.charAt(i) == '`')
			++i;
		int length = i - open;
		int close = closing(text, i, length);
		if (close == -1)
			throw new IllegalArgumentException("'" + text + "' has a code span that is not closed");
		if (skipWhitespace(text, close + length) != text.length())
			throw new IllegalArgumentException("'" + text + "' must be one code span");
		return content(text.substring(i, close));
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
