package io.github.cowwoc.cat.codex;

import java.util.regex.Pattern;

/**
 * Quotes words for the shell.
 */
final class ShellWords
{
	/**
	 * A word that the shell passes unchanged without quotes.
	 */
	private static final Pattern PLAIN_WORD = Pattern.compile("[A-Za-z0-9_@%+=:,./-]+");

	/**
	 * Quotes a word for the shell so that the shell passes it unchanged.
	 *
	 * @param word the word
	 * @return the quoted word
	 */
	static String quote(String word)
	{
		if (PLAIN_WORD.matcher(word).matches())
			return word;
		return "'" + word.replace("'", "'\"'\"'") + "'";
	}

	/**
	 * Prevents instantiation.
	 */
	private ShellWords()
	{
	}
}
