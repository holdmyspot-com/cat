package io.github.cowwoc.cat.codex;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.Platform;
import io.github.cowwoc.cat.common.ShellQuoting;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The shell in which Codex runs an agent's commands: a POSIX shell on Linux and macOS, and PowerShell on Windows.
 * {@code run-part} runs the agent's command in it, and the commands that {@code run-part} and {@code read-part} print
 * for the agent are written for it.
 */
public enum CommandShell
{
	/**
	 * The POSIX shell that Codex uses on Linux and macOS.
	 */
	POSIX
	{
		@Override
		public String quote(String word)
		{
			return ShellQuoting.posix(word);
		}

		@Override
		public String command(List<String> words)
		{
			requireThat(words, "words").isNotEmpty();
			return join(words);
		}

		@Override
		public String launcher(String name)
		{
			requireThat(name, "name").isNotBlank();
			return name;
		}

		@Override
		public List<String> processArguments(String command, Map<String, String> environment)
		{
			requireThat(command, "command").isNotNull();
			requireThat(environment, "environment").isNotNull();
			return List.of("bash", "-c", command);
		}

		@Override
		public String runPartUsage()
		{
			return "usage: run-part <<'EOF', then the command on the lines that follow, then EOF";
		}

		/**
		 * The characters that end a command or start a redirection.
		 */
		private static final String COMMAND_ENDS = "();<>|&";

		@Override
		public Optional<List<String>> words(String command)
		{
			requireThat(command, "command").isNotNull();
			return split(command, false);
		}

		@Override
		public Optional<List<String>> simpleCommandWords(String command)
		{
			requireThat(command, "command").isNotNull();
			return split(command.strip(), true);
		}

		/**
		 * Splits the start of a command into words, without expanding variables.
		 *
		 * @param command the command
		 * @param whole {@code true} if the words must make up the whole command
		 * @return the words up to the first unquoted character that ends a command or starts a redirection, or empty
		 *   if a quote is not closed, or if {@code whole} is {@code true} and the command holds such a character or an
		 *   unquoted line break
		 */
		private static Optional<List<String>> split(String command, boolean whole)
		{
			List<String> words = new ArrayList<>();
			StringBuilder word = new StringBuilder();
			boolean inWord = false;
			int i = 0;
			while (i < command.length())
			{
				char c = command.charAt(i);
				boolean ends = COMMAND_ENDS.indexOf(c) >= 0;
				if (whole && (ends || c == '\n'))
					return Optional.empty();
				if (Character.isWhitespace(c) || ends)
				{
					if (inWord)
						words.add(word.toString());
					word.setLength(0);
					inWord = false;
					if (ends)
						return Optional.of(words);
					++i;
					continue;
				}
				inWord = true;
				switch (c)
				{
					case '\'' ->
					{
						int end = command.indexOf('\'', i + 1);
						if (end < 0)
							return Optional.empty();
						word.append(command, i + 1, end);
						i = end + 1;
					}
					case '"' ->
					{
						i = appendDoubleQuoted(command, i + 1, word);
						if (i < 0)
							return Optional.empty();
					}
					case '\\' ->
					{
						if (i + 1 < command.length())
							word.append(command.charAt(i + 1));
						i += 2;
					}
					default ->
					{
						word.append(c);
						++i;
					}
				}
			}
			if (inWord)
				words.add(word.toString());
			return Optional.of(words);
		}

		/**
		 * Appends the text of a double-quoted shell string to a word.
		 *
		 * @param command the command
		 * @param from the index after the opening quote
		 * @param word the word
		 * @return the index after the closing quote, or {@code -1} if the string is not closed
		 */
		private static int appendDoubleQuoted(String command, int from, StringBuilder word)
		{
			int i = from;
			while (i < command.length())
			{
				char c = command.charAt(i);
				if (c == '"')
					return i + 1;
				if (c == '\\' && i + 1 < command.length() && "\\\"$`\n".indexOf(command.charAt(i + 1)) >= 0)
				{
					word.append(command.charAt(i + 1));
					i += 2;
					continue;
				}
				word.append(c);
				++i;
			}
			return -1;
		}
	},
	/**
	 * The PowerShell that Codex uses on Windows: PowerShell 7 when it is installed, otherwise Windows PowerShell.
	 */
	POWERSHELL
	{
		/**
		 * The characters that PowerShell reads as a single quote, each of which a single-quoted string doubles.
		 */
		private static final String SINGLE_QUOTES = ShellQuoting.POWERSHELL_SINGLE_QUOTES;
		/**
		 * The characters that PowerShell reads as a double quote.
		 */
		private static final String DOUBLE_QUOTES = "\"\u201C\u201D\u201E";
		/**
		 * The line that Codex runs before each command, so that PowerShell reads and writes the output of programs as
		 * UTF-8.
		 */
		private static final String UTF8_OUTPUT = "try { [Console]::OutputEncoding=[System.Text.Encoding]::UTF8 } " +
			"catch {}\n";
		/**
		 * The statement that makes PowerShell write the text that it pipes to a program as UTF-8. Windows PowerShell
		 * otherwise writes it as ASCII, replacing every other character with {@code ?}.
		 */
		private static final String UTF8_INPUT = "$OutputEncoding = [Text.UTF8Encoding]::new()";
		/**
		 * The characters that end a command or start a redirection.
		 */
		private static final String COMMAND_ENDS = "();<>|&{}";
		/**
		 * The file names of the programs that Windows runs with {@code cmd.exe}.
		 */
		private static final Pattern BATCH_FILE = Pattern.compile("(?i).*\\.(?:cmd|bat)");

		@Override
		public String quote(String word)
		{
			return ShellQuoting.powerShell(word);
		}

		@Override
		public String command(List<String> words)
		{
			requireThat(words, "words").isNotEmpty();
			if (!BATCH_FILE.matcher(words.getFirst()).matches())
			{
				// The call operator runs a program whose path is quoted, which PowerShell would otherwise print as a string
				return "& " + join(words);
			}
			// cmd.exe runs the text after a character such as & as another command unless the character is inside double
			// quotes, which PowerShell adds only around an argument that holds whitespace. Each argument that needs quotes is
			// therefore double-quoted inside its PowerShell string, which PowerShell passes as it is. cmd.exe still expands
			// a %name% that names a variable.
			List<String> arguments = new ArrayList<>(words.size());
			arguments.add(words.getFirst());
			for (String argument : words.subList(1, words.size()))
			{
				if (ShellQuoting.isPlainPowerShellWord(argument))
					arguments.add(argument);
				else
					arguments.add('"' + argument + '"');
			}
			return "& " + join(arguments);
		}

		@Override
		public String launcher(String name)
		{
			requireThat(name, "name").isNotBlank();
			return name + ".cmd";
		}

		@Override
		public List<String> processArguments(String command, Map<String, String> environment)
		{
			requireThat(command, "command").isNotNull();
			requireThat(environment, "environment").isNotNull();
			// -EncodedCommand would avoid quoting the command, but it makes PowerShell write every stream other than
			// standard output as serialized objects (CLIXML) instead of text.
			return List.of(executable(environment), "-NoProfile", "-Command", quoteArgument(UTF8_OUTPUT + command));
		}

		/**
		 * Quotes an argument the way that Windows programs split their command line, so that the program receives it
		 * unchanged: inside double quotes, a double quote is escaped with a backslash, and the backslashes before a
		 * double quote, or before the closing quote, are doubled.
		 * <p>
		 * Java adds no quotes to an argument that starts and ends with a double quote, and passes it unchanged unless the
		 * system property {@code jdk.lang.Process.allowAmbiguousCommands} is {@code false}. Java would otherwise quote
		 * the argument without escaping the double quotes inside it.
		 *
		 * @param argument the argument
		 * @return the quoted argument
		 */
		private static String quoteArgument(String argument)
		{
			StringBuilder quoted = new StringBuilder("\"");
			int backslashes = 0;
			for (int i = 0; i < argument.length(); ++i)
			{
				char c = argument.charAt(i);
				if (c == '\\')
				{
					++backslashes;
					continue;
				}
				if (c == '"')
					quoted.repeat('\\', backslashes * 2 + 1);
				else
					quoted.repeat('\\', backslashes);
				quoted.append(c);
				backslashes = 0;
			}
			return quoted.repeat('\\', backslashes * 2).append('"').toString();
		}

		/**
		 * Returns the PowerShell that Codex runs commands in: {@code pwsh.exe} on the {@code PATH} or in its default
		 * folder, otherwise {@code powershell.exe} on the {@code PATH} or in its default folder.
		 *
		 * @param environment the environment, whose {@code PATH} and {@code SystemRoot} name the folders to search
		 * @return the path of the program
		 */
		private static String executable(Map<String, String> environment)
		{
			String path = variable(environment, "PATH");
			Optional<Path> pwsh = find(path, "pwsh.exe");
			if (pwsh.isPresent())
				return pwsh.get().toString();
			Path pwshDefault = Path.of("C:\\Program Files\\PowerShell\\7\\pwsh.exe");
			if (Files.isRegularFile(pwshDefault))
				return pwshDefault.toString();
			Optional<Path> powershell = find(path, "powershell.exe");
			if (powershell.isPresent())
				return powershell.get().toString();
			String systemRoot = variable(environment, "SystemRoot");
			if (systemRoot.isEmpty())
				systemRoot = "C:\\Windows";
			return Path.of(systemRoot, "System32", "WindowsPowerShell", "v1.0", "powershell.exe").toString();
		}

		/**
		 * Returns the first folder of a search path that holds a program.
		 *
		 * @param path the folders, separated by the platform's path separator
		 * @param program the program's file name
		 * @return the program's path, or empty if no folder holds it
		 */
		private static Optional<Path> find(String path, String program)
		{
			for (String folder : path.split(Pattern.quote(File.pathSeparator)))
			{
				if (folder.isBlank())
					continue;
				try
				{
					Path candidate = Path.of(folder.strip()).resolve(program);
					if (Files.isRegularFile(candidate))
						return Optional.of(candidate);
				}
				catch (InvalidPathException _)
				{
					// A folder that the platform cannot name holds no program
				}
			}
			return Optional.empty();
		}

		/**
		 * Returns an environment variable, whose name Windows matches in any letter case.
		 *
		 * @param environment the environment
		 * @param name the variable's name
		 * @return the variable's value, or an empty string if it is unset
		 */
		private static String variable(Map<String, String> environment, String name)
		{
			for (Entry<String, String> entry : environment.entrySet())
			{
				if (entry.getKey().equalsIgnoreCase(name))
					return entry.getValue();
			}
			return "";
		}

		@Override
		public String runPartUsage()
		{
			return "usage: a " + UTF8_INPUT + " line, a @' line, then the command on the lines that follow, then a " +
				"'@ | run-part line";
		}

		@Override
		public Optional<List<String>> words(String command)
		{
			requireThat(command, "command").isNotNull();
			return split(command, false);
		}

		@Override
		public Optional<List<String>> simpleCommandWords(String command)
		{
			requireThat(command, "command").isNotNull();
			return split(command.strip(), true);
		}

		/**
		 * Splits the start of a command into words, without expanding variables.
		 *
		 * @param command the command
		 * @param whole {@code true} if the words must make up the whole command
		 * @return the words up to the first unquoted character that ends a command or starts a redirection, or empty
		 *   if a quote is not closed, or if {@code whole} is {@code true} and the command holds such a character or an
		 *   unquoted line break
		 */
		private static Optional<List<String>> split(String command, boolean whole)
		{
			List<String> words = new ArrayList<>();
			StringBuilder word = new StringBuilder();
			boolean inWord = false;
			int i = 0;
			while (i < command.length())
			{
				char c = command.charAt(i);
				boolean ends = COMMAND_ENDS.indexOf(c) >= 0;
				if (whole && (ends || c == '\n'))
					return Optional.empty();
				if (Character.isWhitespace(c) || ends)
				{
					if (inWord)
						words.add(word.toString());
					word.setLength(0);
					inWord = false;
					if (ends)
						return Optional.of(words);
					++i;
					continue;
				}
				inWord = true;
				if (SINGLE_QUOTES.indexOf(c) >= 0)
					i = appendSingleQuoted(command, i + 1, word);
				else if (DOUBLE_QUOTES.indexOf(c) >= 0)
					i = appendDoubleQuoted(command, i + 1, word);
				else if (c == '`')
				{
					if (i + 1 < command.length())
						word.append(command.charAt(i + 1));
					i += 2;
				}
				else
				{
					word.append(c);
					++i;
				}
				if (i < 0)
					return Optional.empty();
			}
			if (inWord)
				words.add(word.toString());
			return Optional.of(words);
		}

		/**
		 * Appends the text of a single-quoted PowerShell string to a word.
		 *
		 * @param command the command
		 * @param from the index after the opening quote
		 * @param word the word
		 * @return the index after the closing quote, or {@code -1} if the string is not closed
		 */
		private static int appendSingleQuoted(String command, int from, StringBuilder word)
		{
			int i = from;
			while (i < command.length())
			{
				char c = command.charAt(i);
				if (SINGLE_QUOTES.indexOf(c) >= 0)
				{
					// Two quotes stand for one quote inside the string
					if (i + 1 < command.length() && SINGLE_QUOTES.indexOf(command.charAt(i + 1)) >= 0)
					{
						word.append(c);
						i += 2;
						continue;
					}
					return i + 1;
				}
				word.append(c);
				++i;
			}
			return -1;
		}

		/**
		 * Appends the text of a double-quoted PowerShell string to a word, without expanding its variables.
		 *
		 * @param command the command
		 * @param from the index after the opening quote
		 * @param word the word
		 * @return the index after the closing quote, or {@code -1} if the string is not closed
		 */
		private static int appendDoubleQuoted(String command, int from, StringBuilder word)
		{
			int i = from;
			while (i < command.length())
			{
				char c = command.charAt(i);
				if (c == '`' && i + 1 < command.length())
				{
					word.append(command.charAt(i + 1));
					i += 2;
					continue;
				}
				if (DOUBLE_QUOTES.indexOf(c) >= 0)
				{
					// Two quotes stand for one quote inside the string
					if (i + 1 < command.length() && DOUBLE_QUOTES.indexOf(command.charAt(i + 1)) >= 0)
					{
						word.append(c);
						i += 2;
						continue;
					}
					return i + 1;
				}
				word.append(c);
				++i;
			}
			return -1;
		}
	};

	/**
	 * Returns the shell in which Codex runs commands on the current platform.
	 *
	 * @return {@link #POWERSHELL} on Windows, otherwise {@link #POSIX}
	 * @throws UnsupportedOperationException if the platform is unsupported
	 */
	public static CommandShell current()
	{
		if (Platform.current().isWindows())
			return POWERSHELL;
		return POSIX;
	}

	/**
	 * Quotes a word so that the shell passes it to a program unchanged.
	 *
	 * @param word the word
	 * @return the word, quoted if the shell would change it otherwise
	 * @throws NullPointerException if {@code word} is null
	 */
	public abstract String quote(String word);

	/**
	 * Returns the command that runs a program with arguments.
	 *
	 * @param words the program's path, then its arguments
	 * @return the command
	 * @throws NullPointerException     if {@code words} is null
	 * @throws IllegalArgumentException if {@code words} is empty
	 */
	public abstract String command(List<String> words);

	/**
	 * Returns the command that runs a program with arguments.
	 *
	 * @param words the program's path, then its arguments
	 * @return the command
	 * @throws NullPointerException     if {@code words} is null
	 * @throws IllegalArgumentException if {@code words} is empty
	 */
	public String command(String... words)
	{
		requireThat(words, "words").isNotNull();
		return command(List.of(words));
	}

	/**
	 * Returns the file name of a launcher in the plugin's {@code bin} folder that the shell runs.
	 *
	 * @param name the launcher's name without an extension
	 * @return the file name
	 * @throws NullPointerException     if {@code name} is null
	 * @throws IllegalArgumentException if {@code name} is blank
	 */
	public abstract String launcher(String name);

	/**
	 * Returns the arguments of the process that runs a command the way Codex runs it.
	 *
	 * @param command the command, as the agent wrote it
	 * @param environment the environment that holds the folders to search for the shell
	 * @return the program, then its arguments
	 * @throws NullPointerException if any of the arguments are null
	 */
	public abstract List<String> processArguments(String command, Map<String, String> environment);

	/**
	 * Returns how to give {@code run-part} a command in this shell.
	 *
	 * @return the usage line
	 */
	public abstract String runPartUsage();

	/**
	 * Splits the start of a command into the words that the shell passes to its first program, without expanding
	 * variables. The words end at the first unquoted character that ends a command or starts a redirection.
	 *
	 * @param command the command
	 * @return the words, or empty if a quote is not closed
	 * @throws NullPointerException if {@code command} is null
	 */
	public abstract Optional<List<String>> words(String command);

	/**
	 * Splits a command that runs one program into the words that the shell passes to it, without expanding variables.
	 *
	 * @param command the command
	 * @return the words, or empty if a quote is not closed, or if the command does more than run one program: it holds
	 *   an unquoted character that ends a command or starts a redirection, or an unquoted line break between words
	 * @throws NullPointerException if {@code command} is null
	 */
	public abstract Optional<List<String>> simpleCommandWords(String command);

	/**
	 * Quotes each word and joins them with spaces.
	 *
	 * @param words the words
	 * @return the joined words
	 */
	String join(List<String> words)
	{
		StringBuilder result = new StringBuilder();
		for (String word : words)
		{
			if (!result.isEmpty())
				result.append(' ');
			result.append(quote(word));
		}
		return result.toString();
	}
}
