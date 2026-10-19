package com.holdmyspot.cat.common;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.util.regex.Pattern;

/**
 * Quotes a word so that a shell passes it to a program unchanged.
 */
public final class ShellQuoting
{
	/**
	 * The characters that PowerShell reads as a single quote, each of which a single-quoted string doubles.
	 */
	public static final String POWERSHELL_SINGLE_QUOTES = "'‘’‚‛";
	/**
	 * A word that a POSIX shell passes unchanged without quotes.
	 */
	private static final Pattern POSIX_PLAIN_WORD = Pattern.compile("[A-Za-z0-9_@%+=:,./-]+");
	/**
	 * A word that PowerShell passes to a program unchanged without quotes.
	 */
	private static final Pattern POWERSHELL_PLAIN_WORD = Pattern.compile("[A-Za-z0-9_+=:./\\\\-]+");

	/**
	 * Prevents instantiation.
	 */
	private ShellQuoting()
	{
	}

	/**
	 * Quotes a word so that a POSIX shell passes it to a program unchanged.
	 *
	 * @param word the word
	 * @return the word, in single quotes if the shell would change it otherwise
	 * @throws NullPointerException if {@code word} is null
	 */
	public static String posix(String word)
	{
		requireThat(word, "word").isNotNull();
		if (POSIX_PLAIN_WORD.matcher(word).matches())
			return word;
		return "'" + word.replace("'", "'\"'\"'") + "'";
	}

	/**
	 * Quotes a word so that PowerShell passes it to a program unchanged.
	 *
	 * @param word the word
	 * @return the word, in single quotes if PowerShell would change it otherwise
	 * @throws NullPointerException if {@code word} is null
	 */
	public static String powerShell(String word)
	{
		if (isPlainPowerShellWord(word))
			return word;
		StringBuilder quoted = new StringBuilder("'");
		for (int i = 0; i < word.length(); ++i)
		{
			char c = word.charAt(i);
			if (POWERSHELL_SINGLE_QUOTES.indexOf(c) >= 0)
				quoted.append(c);
			quoted.append(c);
		}
		return quoted.append('\'').toString();
	}

	/**
	 * Indicates whether PowerShell passes a word to a program unchanged without quotes.
	 *
	 * @param word the word
	 * @return {@code true} if the word holds only letters, digits and the characters {@code _+=:./\-}
	 * @throws NullPointerException if {@code word} is null
	 */
	public static boolean isPlainPowerShellWord(String word)
	{
		requireThat(word, "word").isNotNull();
		return POWERSHELL_PLAIN_WORD.matcher(word).matches();
	}
}
