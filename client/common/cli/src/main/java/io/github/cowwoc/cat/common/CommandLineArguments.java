package io.github.cowwoc.cat.common;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_16LE;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.util.ArrayList;
import java.util.List;

/**
 * Recovers the characters of command-line arguments that the Java launcher replaced.
 * <p>
 * On Windows, the {@code java} launcher reads its command line in the system's ANSI code page, which replaces each
 * character that the code page lacks, such as {@code ✓}, with {@code ?} or a similar character. The process's
 * command line still holds every character, so the arguments can be read from it again.
 */
public final class CommandLineArguments
{
	/**
	 * Returns the arguments that started this process. On Windows, they are read from the process's command line, and
	 * elsewhere they are the launcher's arguments.
	 * <p>
	 * On Windows, the module that holds this class needs native access, which {@code --enable-native-access} grants.
	 *
	 * @param launcherArguments the arguments that the launcher passed to {@code main}
	 * @return the arguments
	 * @throws NullPointerException if {@code launcherArguments} is null
	 */
	public static String[] recover(String[] launcherArguments)
	{
		requireThat(launcherArguments, "launcherArguments").isNotNull();
		if (launcherArguments.length == 0 || !Platform.current().isWindows())
			return launcherArguments;
		return recover(launcherArguments, commandLine());
	}

	/**
	 * Returns the arguments that a command line passed to a program that the Java launcher started.
	 *
	 * @param launcherArguments the arguments that the launcher passed to {@code main}
	 * @param commandLine       the process's command line, which Windows keeps in UTF-16
	 * @return the arguments with the characters that the launcher replaced, or {@code launcherArguments} if the
	 *   command line's last arguments do not match them
	 * @throws NullPointerException if any argument is null
	 */
	public static String[] recover(String[] launcherArguments, String commandLine)
	{
		requireThat(launcherArguments, "launcherArguments").isNotNull();
		requireThat(commandLine, "commandLine").isNotNull();
		List<String> words = words(commandLine);
		// The launcher's own options and the main class come first, so the program's arguments are the last words
		int first = words.size() - launcherArguments.length;
		if (first < 1)
			return launcherArguments;
		String[] arguments = words.subList(first, words.size()).toArray(String[]::new);
		for (int i = 0; i < arguments.length; ++i)
		{
			if (!sameArgument(launcherArguments[i], arguments[i]))
				return launcherArguments;
		}
		return arguments;
	}

	/**
	 * Splits a Windows command line into words the way that programs built with the Microsoft C runtime, including the
	 * Java launcher, split it.
	 * <p>
	 * The first word, the program, ends at the first space or tab outside quotes, and backslashes in it are plain
	 * characters. In the other words, quotes group spaces and tabs into a word, two quotes inside quotes are one quote,
	 * and backslashes before a quote escape it in pairs; other backslashes are plain characters.
	 *
	 * @param commandLine the command line
	 * @return the words, starting with the program
	 */
	private static List<String> words(String commandLine)
	{
		List<String> words = new ArrayList<>();
		StringBuilder word = new StringBuilder();
		boolean quoted = false;
		int i = 0;
		while (i < commandLine.length())
		{
			char c = commandLine.charAt(i);
			if (!quoted && isBlank(c))
				break;
			if (c == '"')
				quoted = !quoted;
			else
				word.append(c);
			++i;
		}
		words.add(word.toString());

		while (true)
		{
			while (i < commandLine.length() && isBlank(commandLine.charAt(i)))
				++i;
			if (i == commandLine.length())
				return words;
			word.setLength(0);
			quoted = false;
			while (i < commandLine.length())
			{
				char c = commandLine.charAt(i);
				if (c == '\\')
				{
					int start = i;
					while (i < commandLine.length() && commandLine.charAt(i) == '\\')
						++i;
					int backslashes = i - start;
					if (i < commandLine.length() && commandLine.charAt(i) == '"')
					{
						word.repeat('\\', backslashes / 2);
						// An odd backslash escapes the quote; after an even number, the quote opens or closes quotes
						if (backslashes % 2 == 1)
						{
							word.append('"');
							++i;
						}
					}
					else
						word.repeat('\\', backslashes);
					continue;
				}
				if (c == '"')
				{
					if (quoted && i + 1 < commandLine.length() && commandLine.charAt(i + 1) == '"')
					{
						word.append('"');
						i += 2;
						continue;
					}
					quoted = !quoted;
					++i;
					continue;
				}
				if (!quoted && isBlank(c))
					break;
				word.append(c);
				++i;
			}
			words.add(word.toString());
		}
	}

	/**
	 * Indicates whether a character separates the words of a Windows command line.
	 *
	 * @param character a character
	 * @return {@code true} for a space or tab
	 */
	private static boolean isBlank(char character)
	{
		return character == ' ' || character == '\t';
	}

	/**
	 * Indicates whether an argument that the launcher passed can be the same argument as a word of the command line.
	 * Converting the command line to an ANSI code page keeps each ASCII character and replaces or approximates each
	 * other character, so the word's ASCII characters must appear, in order, in the launcher's argument, and a word that
	 * holds only ASCII characters must equal it.
	 *
	 * @param launcherArgument the argument that the launcher passed
	 * @param word             the word of the command line
	 * @return {@code true} if the two can be the same argument
	 */
	private static boolean sameArgument(String launcherArgument, String word)
	{
		boolean ascii = true;
		int position = 0;
		for (int i = 0; i < word.length(); ++i)
		{
			char c = word.charAt(i);
			if (c > 0x7F)
			{
				ascii = false;
				continue;
			}
			position = launcherArgument.indexOf(c, position);
			if (position == -1)
				return false;
			++position;
		}
		return !ascii || launcherArgument.equals(word);
	}

	/**
	 * Returns this process's Windows command line.
	 *
	 * @return the command line
	 */
	// Only native code can read the command line: ProcessHandle.Info returns no arguments on Windows
	@SuppressWarnings("restricted")
	private static String commandLine()
	{
		Linker linker = Linker.nativeLinker();
		SymbolLookup kernel32 = SymbolLookup.libraryLookup("kernel32", Arena.global());
		MethodHandle getCommandLine = linker.downcallHandle(kernel32.findOrThrow("GetCommandLineW"),
			FunctionDescriptor.of(ValueLayout.ADDRESS));
		MemorySegment commandLine;
		try
		{
			commandLine = (MemorySegment) getCommandLine.invokeExact();
		}
		catch (Throwable t)
		{
			// GetCommandLineW returns a pointer to memory that the process owns, and does not fail
			throw new AssertionError(t);
		}
		return commandLine.reinterpret(Long.MAX_VALUE).getString(0, UTF_16LE);
	}

	/**
	 * Prevents instantiation.
	 */
	private CommandLineArguments()
	{
	}
}
