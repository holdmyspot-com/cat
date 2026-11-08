package com.holdmyspot.cat.common.runner;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * Reads how a shell command that a harness session recorded names a runner launcher. A harness runs a runner
 * launcher outside its sandbox only when the command names the launcher by the absolute path that the installed
 * entry holds, spelled exactly so, so a command that names it any other way stays sandboxed.
 * <p>
 * <b>Thread Safety:</b> This class is stateless and thread-safe.
 */
public final class LauncherSpelling
{
	/**
	 * The characters that end a shell word outside quotes, besides whitespace.
	 */
	private static final String OPERATORS = ";&|<>()";

	/**
	 * Prevents instantiation.
	 */
	private LauncherSpelling()
	{
	}

	/**
	 * Returns the word by which a shell command names a launcher, when it is not the launcher's absolute path. The
	 * command's words are read as a POSIX shell reads them, with their quotes and escapes removed, and the first word
	 * whose last path element is the launcher's file name is the one that names the launcher.
	 *
	 * @param command  the command
	 * @param launcher the launcher's absolute path, as the installed entry spells it
	 * @return the word, or empty if the command does not name the launcher or names it by {@code launcher}
	 * @throws NullPointerException     if any of the arguments are null
	 * @throws IllegalArgumentException if {@code launcher} is relative
	 */
	public static Optional<String> otherSpelling(String command, Path launcher)
	{
		requireThat(command, "command").isNotNull();
		requireThat(launcher, "launcher").isAbsolute();
		String name = launcher.getFileName().toString();
		for (String word : words(command))
		{
			if (!word.equals(name) && !word.endsWith("/" + name) && !word.endsWith("\\" + name))
				continue;
			if (word.equals(launcher.toString()))
				return Optional.empty();
			return Optional.of(word);
		}
		return Optional.empty();
	}

	/**
	 * Returns the words of a shell command without their quotes and escapes. Operators and whitespace outside quotes
	 * end a word.
	 *
	 * @param command the command
	 * @return the words, in order
	 */
	private static List<String> words(String command)
	{
		List<String> words = new ArrayList<>();
		StringBuilder word = new StringBuilder();
		boolean inWord = false;
		int i = 0;
		while (i < command.length())
		{
			char c = command.charAt(i);
			++i;
			if (Character.isWhitespace(c) || OPERATORS.indexOf(c) >= 0)
			{
				if (inWord)
					words.add(word.toString());
				word.setLength(0);
				inWord = false;
				continue;
			}
			inWord = true;
			switch (c)
			{
				case '\'' ->
				{
					int end = command.indexOf('\'', i);
					if (end < 0)
						end = command.length();
					word.append(command, i, end);
					i = Math.min(end + 1, command.length());
				}
				case '"' ->
				{
					while (i < command.length() && command.charAt(i) != '"')
					{
						char inner = command.charAt(i);
						++i;
						if (inner == '\\' && i < command.length() && "$`\"\\\n".indexOf(command.charAt(i)) >= 0)
						{
							inner = command.charAt(i);
							++i;
						}
						word.append(inner);
					}
					i = Math.min(i + 1, command.length());
				}
				case '\\' ->
				{
					if (i < command.length())
					{
						word.append(command.charAt(i));
						++i;
					}
				}
				default -> word.append(c);
			}
		}
		if (inWord)
			words.add(word.toString());
		return words;
	}
}
