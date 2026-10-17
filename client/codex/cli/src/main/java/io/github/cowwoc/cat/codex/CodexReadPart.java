package io.github.cowwoc.cat.codex;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.ISO_8859_1;
import static java.nio.charset.StandardCharsets.UTF_8;

import io.github.cowwoc.cat.common.CommandLineArguments;
import io.github.cowwoc.cat.common.Platform;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Prints a file from a given position through its end in parts that Codex shows whole.
 * <p>
 * Each call looks up how many bytes Codex keeps of this call's output and prints as many lines as fit together with
 * the line that ends the part; that line names the command that prints the next part, and the last part ends with
 * {@code [End of <path>]}. A part that more parts follow exits with status 0. The last part exits with the exit status
 * that {@code <path>.exit-status} holds, which {@code run-part} saves for the command whose output {@code <path>}
 * holds, or with 0 when there is no such file. A position is {@code <line>}, or {@code <line>:<byte>} for a part that
 * starts inside a line too long for one part. Lines are printed as the file holds them, without line numbers; when a
 * part ends inside a line, the line that ends the part says so. {@code read-part} refuses a file that holds a NUL byte,
 * since such a file is binary.
 */
public final class CodexReadPart
{
	private static final String USAGE = "usage: read-part <path> [<position>]";
	/**
	 * A position that {@code read-part} prints.
	 */
	private static final Pattern POSITION = Pattern.compile("(?<line>[1-9][0-9]*)(?::(?<byte>[1-9][0-9]*))?");
	/**
	 * The token budget of a call that leaves {@code max_output_tokens} unset, and of every call of the exec tool (Code
	 * Mode).
	 */
	private static final long DEFAULT_TOKENS = 10_000;
	/**
	 * The number of bytes per token that Codex keeps whole.
	 */
	private static final long BYTES_PER_TOKEN = 4;
	/**
	 * The bytes of the budget that a part leaves for the Code Mode header and the other fields of a result object that
	 * a Code Mode script may print as JSON.
	 */
	private static final long RESERVED_BYTES = 512;
	/**
	 * The budget of a call outside a Codex thread, which has none.
	 */
	private static final long NO_LIMIT = Long.MAX_VALUE;
	/**
	 * The setting of {@code config.toml} that caps the token budget.
	 */
	private static final Pattern TOKEN_LIMIT = Pattern.compile("^[ \\t]*tool_output_token_limit[ \\t]*=[ \\t]*" +
		"(?<value>[0-9][0-9_]*)");
	/**
	 * A line of {@code config.toml} that starts a table, after which settings no longer belong to the top level.
	 */
	private static final Pattern TABLE = Pattern.compile("^[ \\t]*\\[");
	private static final int MAXIMUM_CONFIGURATION_BYTES = 1_048_576;
	private static final int MAXIMUM_STATUS_BYTES = 64;
	/**
	 * The number of edits within which a file name is close to the name that the agent typed.
	 */
	private static final int MAXIMUM_EDITS = 6;
	/**
	 * The number of files of a folder that are compared with the name that the agent typed.
	 */
	private static final int MAXIMUM_CANDIDATES = 500;
	private static final int BUFFER_SIZE = 65_536;
	/**
	 * The number of bytes that JSON takes for the newline that ends a line.
	 */
	private static final int ESCAPED_NEWLINE = 2;
	/**
	 * The result of printing parts when the position names a byte past the end of its line.
	 */
	private static final int BAD_POSITION = -1;
	private static final int EXIT_STATUSES = 256;

	/**
	 * Runs {@code read-part}.
	 *
	 * @param args the path, then optionally the position
	 * @throws NullPointerException if {@code args} is null
	 */
	public static void main(String[] args)
	{
		args = CommandLineArguments.recover(args);
		CommandShell shell = CommandShell.current();
		int status = run(args, shell, launcher(shell.launcher("read-part")),
			Platform.current().environment(System.getenv()), System.out, System.err);
		System.out.flush();
		System.exit(status);
	}

	/**
	 * Returns the path of a launcher that agents run: the launcher of the same name in the {@code bin} folder of the
	 * plugin whose {@code client} folder holds the runtime image that runs this program.
	 *
	 * @param name the launcher's file name
	 * @return the path
	 */
	static Path launcher(String name)
	{
		return Path.of(System.getProperty("java.home")).resolveSibling("bin").resolve(name);
	}

	/**
	 * Prints the part of a file that starts at a position.
	 *
	 * @param args the path, then optionally the position
	 * @param shell the shell that runs the commands that this program prints
	 * @param readPart the {@code read-part} launcher that the line that ends a part names
	 * @param environment the environment: {@code CODEX_THREAD_ID} is set in a Codex thread, and
	 *   {@code CODEX_HOME}, or else the {@code .codex} folder of the user's home folder ({@code HOME}, or
	 *   {@code USERPROFILE} on Windows), holds the {@code config.toml} that may cap the budget
	 * @param output receives the part
	 * @param error receives diagnostics
	 * @return the exit status: 0 for a part that more parts follow, the saved status of the last part, 1 when the file
	 *   cannot be printed, or 2 for a usage error
	 * @throws NullPointerException if any argument is null
	 */
	public static int run(String[] args, CommandShell shell, Path readPart, Map<String, String> environment,
		OutputStream output, PrintStream error)
	{
		requireThat(args, "args").isNotNull();
		requireThat(shell, "shell").isNotNull();
		requireThat(readPart, "readPart").isNotNull();
		requireThat(environment, "environment").isNotNull();
		requireThat(output, "output").isNotNull();
		requireThat(error, "error").isNotNull();
		if (args.length < 1 || args.length > 2)
		{
			error.println(USAGE);
			return 2;
		}
		String path = args[0];
		String requested = "1";
		if (args.length == 2)
			requested = args[1];
		Matcher position = POSITION.matcher(requested);
		if (!position.matches())
		{
			error.println(notPrinted(requested));
			return 2;
		}
		Path file;
		try
		{
			file = Path.of(path);
		}
		catch (InvalidPathException _)
		{
			file = null;
		}
		if (file == null || !Files.isRegularFile(file) || !Files.isReadable(file))
		{
			error.println("read-part: cannot read \"" + path + "\"");
			suggest(path, args, shell, readPart, error);
			return 1;
		}
		try
		{
			long count = countLines(file);
			if (count < 0)
			{
				error.println("read-part: \"" + path + "\" holds NUL bytes, so it is binary, not text; inspect it " +
					"with a tool for its format instead");
				return 1;
			}
			Part part = new Part(path, shell.command(readPart.toString(), path), limit(environment), count);
			long line = parse(position.group("line"), Long.MAX_VALUE);
			int column = 1;
			if (position.group("byte") != null)
				column = (int) parse(position.group("byte"), Integer.MAX_VALUE);
			BufferedOutputStream buffered = new BufferedOutputStream(output, BUFFER_SIZE);
			int printed = part.print(file, line, column, requested, buffered);
			buffered.flush();
			if (printed == BAD_POSITION)
			{
				error.println(notPrinted(requested));
				return 2;
			}
			if (printed == Part.LAST)
				return savedStatus(path);
			return printed;
		}
		catch (IOException | UncheckedIOException e)
		{
			error.println("read-part: " + e.getMessage());
			return 1;
		}
	}

	/**
	 * Returns the message for a position that {@code read-part} could not have printed.
	 *
	 * @param position the position
	 * @return the message
	 */
	private static String notPrinted(String position)
	{
		return "read-part: \"" + position + "\" is not a position that read-part printed";
	}

	/**
	 * Returns the value of a number, or a maximum if it is larger.
	 *
	 * @param digits the number's digits
	 * @param maximum the maximum
	 * @return the value
	 */
	private static long parse(String digits, long maximum)
	{
		BigInteger value = new BigInteger(digits);
		if (value.compareTo(BigInteger.valueOf(maximum)) > 0)
			return maximum;
		return value.longValue();
	}

	/**
	 * Returns the number of lines of a file, counting a last line that does not end with a newline.
	 *
	 * @param file the file
	 * @return the number of lines, or {@code -1} if the file holds a NUL byte
	 * @throws IOException if the file cannot be read
	 */
	private static long countLines(Path file) throws IOException
	{
		long count = 0;
		int last = '\n';
		byte[] buffer = new byte[BUFFER_SIZE];
		try (InputStream in = Files.newInputStream(file))
		{
			while (true)
			{
				int length = in.read(buffer);
				if (length < 0)
					break;
				for (int i = 0; i < length; ++i)
				{
					if (buffer[i] == '\n')
						++count;
					else if (buffer[i] == 0)
						return -1;
				}
				if (length > 0)
					last = buffer[length - 1];
			}
		}
		if (last != '\n')
			++count;
		return count;
	}

	/**
	 * Returns the exit status of the last part: the status that {@code run-part} saved for the command whose output
	 * the file holds, or 0 when it saved none.
	 *
	 * @param path the file's path
	 * @return the status
	 */
	private static int savedStatus(String path)
	{
		Path saved = Path.of(path + CodexRunPart.EXIT_STATUS_SUFFIX);
		if (!Files.isRegularFile(saved))
			return 0;
		byte[] bytes;
		try (InputStream in = Files.newInputStream(saved))
		{
			bytes = in.readNBytes(MAXIMUM_STATUS_BYTES + 1);
		}
		catch (IOException _)
		{
			return 0;
		}
		if (bytes.length > MAXIMUM_STATUS_BYTES)
			return 0;
		String status = new String(bytes, ISO_8859_1).replaceFirst("\n+\\z", "");
		if (!status.matches("[0-9]+"))
			return 0;
		return new BigInteger(status).mod(BigInteger.valueOf(EXIT_STATUSES)).intValue();
	}

	/**
	 * Returns the number of bytes that a part may take after JSON escaping.
	 * <p>
	 * Codex keeps the output of a command whole when it has at most 4 bytes per token of the call's token budget, and
	 * otherwise removes bytes from its middle. Outside a Codex thread there is no budget. In a Codex thread, the budget
	 * of a call that leaves {@code max_output_tokens} unset is 10,000 tokens, capped by the
	 * {@code tool_output_token_limit} of {@code config.toml}; a call of the exec tool (Code Mode) has 10,000 tokens
	 * whatever {@code config.toml} says, and that budget also holds a header of about 50 bytes. A Code Mode script may
	 * print the call's whole result object as JSON rather than its output field, so a part must also fit after JSON
	 * escaping, together with the object's other fields, which take about 150 bytes.
	 *
	 * @param environment the environment
	 * @return the number of bytes, or {@link #NO_LIMIT} outside a Codex thread
	 */
	private static long limit(Map<String, String> environment)
	{
		String thread = environment.get("CODEX_THREAD_ID");
		if (thread == null || thread.isEmpty())
			return NO_LIMIT;
		long tokens = Math.min(DEFAULT_TOKENS, configuredTokens(environment));
		return Math.max(1, tokens * BYTES_PER_TOKEN - RESERVED_BYTES);
	}

	/**
	 * Returns the {@code tool_output_token_limit} of Codex's {@code config.toml}.
	 *
	 * @param environment the environment
	 * @return the limit, or {@link Long#MAX_VALUE} if the file does not set a positive one
	 */
	private static long configuredTokens(Map<String, String> environment)
	{
		String codexHome = environment.get("CODEX_HOME");
		Path configuration;
		try
		{
			if (codexHome != null && !codexHome.isEmpty())
				configuration = Path.of(codexHome, "config.toml");
			else
			{
				String home = environment.getOrDefault(Platform.current().homeVariable(), "");
				configuration = Path.of(home, ".codex", "config.toml");
			}
		}
		catch (InvalidPathException _)
		{
			return Long.MAX_VALUE;
		}
		byte[] bytes;
		try (InputStream in = Files.newInputStream(configuration))
		{
			bytes = in.readNBytes(MAXIMUM_CONFIGURATION_BYTES);
		}
		catch (IOException _)
		{
			return Long.MAX_VALUE;
		}
		// The setting's name and value are ASCII, so any decoding that maps each byte to one character finds them.
		for (String line : new String(bytes, ISO_8859_1).split("\n"))
		{
			if (TABLE.matcher(line).find())
				break;
			Matcher setting = TOKEN_LIMIT.matcher(line);
			if (setting.find())
			{
				long tokens = parse(setting.group("value").replace("_", ""), Long.MAX_VALUE);
				if (tokens > 0)
					return tokens;
				return Long.MAX_VALUE;
			}
		}
		return Long.MAX_VALUE;
	}

	/**
	 * Reports the one file of a path's folder whose name is within a few edits of the path's file name, since the path
	 * may hold a typing error in its file name.
	 *
	 * @param path the path that names no readable file
	 * @param args the arguments of this call
	 * @param shell the shell that runs the suggested command
	 * @param readPart the {@code read-part} launcher
	 * @param error receives the suggestion
	 */
	private static void suggest(String path, String[] args, CommandShell shell, Path readPart, PrintStream error)
	{
		String folder = folder(path);
		Path folderPath;
		try
		{
			folderPath = Path.of(folder);
		}
		catch (InvalidPathException _)
		{
			return;
		}
		if (!Files.isDirectory(folderPath) || !Files.isReadable(folderPath))
			return;
		List<String> names;
		try (Stream<Path> files = Files.list(folderPath))
		{
			names = files.filter(candidate -> Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS)).
				limit(MAXIMUM_CANDIDATES).
				map(candidate -> candidate.getFileName().toString()).
				toList();
		}
		catch (IOException | UncheckedIOException _)
		{
			return;
		}
		String wanted = name(path);
		String closest = "";
		int best = MAXIMUM_EDITS + 1;
		int ties = 0;
		for (String name : names)
		{
			int edits = distance(wanted, name);
			if (edits > MAXIMUM_EDITS)
				continue;
			if (edits < best)
			{
				best = edits;
				closest = name;
				ties = 1;
			}
			else if (edits == best)
			{
				++ties;
			}
		}
		if (ties != 1)
			return;
		String suggestion;
		if (folder.equals(".") && !path.startsWith("."))
			suggestion = closest;
		else if (isSeparator(folder.charAt(folder.length() - 1)))
			suggestion = folder + closest;
		else
			suggestion = folder + path.charAt(lastSeparator(withoutTrailingSlashes(path))) + closest;
		List<String> words = new ArrayList<>(List.of(readPart.toString(), suggestion));
		if (args.length > 1)
			words.add(args[1]);
		String command = shell.command(words);
		error.println("read-part: \"" + folder + "\" holds no such file, but it holds \"" + closest + "\". If you " +
			"meant it, run " + command + " before any other command or your answer.");
	}

	/**
	 * Returns the folder part of a path, as {@code dirname} does.
	 *
	 * @param path the path
	 * @return the folder
	 */
	private static String folder(String path)
	{
		String trimmed = withoutTrailingSlashes(path);
		int slash = lastSeparator(trimmed);
		if (slash < 0)
			return ".";
		String parent = withoutTrailingSlashes(trimmed.substring(0, slash));
		if (parent.isEmpty())
			return String.valueOf(trimmed.charAt(slash));
		return parent;
	}

	/**
	 * Indicates whether a character separates the names of a path: {@code /}, or the platform's own separator.
	 *
	 * @param character the character
	 * @return {@code true} if the character separates names
	 */
	private static boolean isSeparator(char character)
	{
		return character == '/' || character == File.separatorChar;
	}

	/**
	 * Returns the index of the last character of a path that separates its names.
	 *
	 * @param path the path
	 * @return the index, or {@code -1} if the path has one name
	 */
	private static int lastSeparator(String path)
	{
		for (int i = path.length() - 1; i >= 0; --i)
		{
			if (isSeparator(path.charAt(i)))
				return i;
		}
		return -1;
	}

	/**
	 * Returns the file-name part of a path, as {@code basename} does.
	 *
	 * @param path the path
	 * @return the file name
	 */
	private static String name(String path)
	{
		String trimmed = withoutTrailingSlashes(path);
		if (trimmed.length() == 1 && isSeparator(trimmed.charAt(0)))
			return trimmed;
		return trimmed.substring(lastSeparator(trimmed) + 1);
	}

	/**
	 * Removes the slashes that end a path, keeping a path that is only slashes as {@code /}.
	 *
	 * @param path the path
	 * @return the path without its trailing slashes
	 */
	private static String withoutTrailingSlashes(String path)
	{
		int end = path.length();
		while (end > 1 && isSeparator(path.charAt(end - 1)))
			--end;
		return path.substring(0, end);
	}

	/**
	 * Returns the number of single-character insertions, deletions or substitutions that turn one text into another.
	 *
	 * @param first the first text
	 * @param second the second text
	 * @return the number of edits
	 */
	private static int distance(String first, String second)
	{
		int[] previous = new int[second.length() + 1];
		int[] current = new int[second.length() + 1];
		for (int j = 0; j <= second.length(); ++j)
			previous[j] = j;
		for (int i = 1; i <= first.length(); ++i)
		{
			current[0] = i;
			for (int j = 1; j <= second.length(); ++j)
			{
				int cost = 1;
				if (first.charAt(i - 1) == second.charAt(j - 1))
					cost = 0;
				current[j] = Math.min(Math.min(previous[j - 1] + cost, previous[j] + 1), current[j - 1] + 1);
			}
			int[] swap = previous;
			previous = current;
			current = swap;
		}
		return previous[second.length()];
	}

	/**
	 * Prevents instantiation.
	 */
	private CodexReadPart()
	{
	}

	/**
	 * Prints the parts of one file.
	 *
	 * @param path the file's path as the agent typed it
	 * @param command the command that prints a part of the file, without its position
	 * @param limit the number of bytes that a part may take after JSON escaping
	 * @param count the number of lines of the file
	 */
	private record Part(String path, String command, long limit, long count)
	{
		/**
		 * The result of printing parts when the last part was printed.
		 */
		static final int LAST = -2;

		/**
		 * Prints the part that starts at a position, then the line that ends the part.
		 * <p>
		 * Each line is printed as the file holds it. A part ends before a line that does not fit in the rest of it. A
		 * line that does not fit even in an empty part is split where the part must end, and the line that ends the part
		 * says that the last line continues at the start of the next part, which starts at
		 * {@code <line>:<byte at which the rest starts>}. A piece ends after the last space or tab in its second half, or
		 * else where the rest would not start inside a UTF-8 character.
		 *
		 * @param file the file
		 * @param line the number of the part's first line
		 * @param column the number of the byte of that line at which the part starts
		 * @param requested the position as the agent typed it
		 * @param out receives the part
		 * @return 0 for a part that more parts follow, {@link #LAST} for the last part, or {@link #BAD_POSITION} if
		 *   the position names a byte past the end of its line
		 * @throws IOException if the file cannot be read or the part cannot be written
		 */
		int print(Path file, long line, int column, String requested, OutputStream out) throws IOException
		{
			try (InputStream in = Files.newInputStream(file))
			{
				Lines lines = new Lines(in);
				for (long number = 1; number < line && number <= count; ++number)
					lines.next(null);
				long size = 0;
				boolean shown = false;
				ByteArrayOutputStream record = new ByteArrayOutputStream();
				for (long number = line; number <= count; ++number)
				{
					record.reset();
					lines.next(record);
					byte[] text = record.toByteArray();
					int start = 0;
					int byteNumber = 1;
					if (number == line && column > 1)
					{
						if (column > text.length)
							return BAD_POSITION;
						start = column - 1;
						byteNumber = column;
					}
					int length = text.length - start;
					long whole = shownLength(text, start, text.length) + ESCAPED_NEWLINE;
					byte[] ending;
					if (number == count)
						ending = closing();
					else
						ending = footer(position(number + 1, 1), false);
					if (size + whole + shownLength(ending, 0, ending.length) <= limit)
					{
						out.write(text, start, length);
						out.write('\n');
						size += whole;
						shown = true;
						continue;
					}
					if (shown)
					{
						out.write(footer(position(number, byteNumber), false));
						return 0;
					}
					if (length <= 1)
					{
						// A line this short cannot be split, so the part holds it whole.
						out.write(text, start, length);
						out.write('\n');
						size += whole;
						shown = true;
						continue;
					}
					int piece = piece(text, start, length, footer(position(number, byteNumber + length), true));
					out.write(text, start, piece);
					out.write('\n');
					out.write(footer(position(number, byteNumber + piece), true));
					return 0;
				}
				if (!shown && line > Math.max(count, 1))
				{
					out.write(("[" + path + " ends before position " + requested + "]\n").getBytes(UTF_8));
					return 0;
				}
				out.write(closing());
				return LAST;
			}
		}

		/**
		 * Returns the length of the piece of a line that starts a part that the line does not fit in: the longest start
		 * of it that fits with the line that ends the part, then ended after a space or tab in its second half, or else
		 * outside a UTF-8 character.
		 *
		 * @param text the line
		 * @param start the index of the line's first byte that the part holds
		 * @param length the number of bytes from {@code start} to the end of the line
		 * @param footer the longest line that can end the part
		 * @return the number of bytes of the piece
		 */
		private int piece(byte[] text, int start, int length, byte[] footer)
		{
			long room = limit - ESCAPED_NEWLINE - shownLength(footer, 0, footer.length);
			int low = 1;
			int high = length - 1;
			while (low < high)
			{
				int middle = (int) ((low + (long) high + 1) / 2);
				if (shownLength(text, start, start + middle) <= room)
					low = middle;
				else
					high = middle - 1;
			}
			int piece = low;
			int blank = piece;
			while (2 * blank > piece && text[start + blank - 1] != ' ' && text[start + blank - 1] != '\t')
				--blank;
			if (2 * blank > piece)
				return blank;
			while (piece > 1 && (text[start + piece] & 0xC0) == 0x80)
				--piece;
			return piece;
		}

		/**
		 * Returns a position.
		 *
		 * @param line the line number
		 * @param byteNumber the number of the line's byte
		 * @return {@code <line>}, or {@code <line>:<byte>} for a position inside a line
		 */
		private static String position(long line, int byteNumber)
		{
			if (byteNumber == 1)
				return String.valueOf(line);
			return line + ":" + byteNumber;
		}

		/**
		 * Returns the line that ends a part that more parts follow: it names the command that prints the next part,
		 * after saying when the part ends inside a line.
		 *
		 * @param next the position of the next part
		 * @param inside {@code true} if the part ends inside a line
		 * @return the line
		 */
		private byte[] footer(String next, boolean inside)
		{
			String continues = "";
			if (inside)
				continues = "The last line continues at the start of the next part. ";
			return ("[" + continues + "More output follows. Before any other command or answer, run: " + command + " " +
				next + "]\n").getBytes(UTF_8);
		}

		/**
		 * Returns the line that ends the last part, which for an empty file is the only part.
		 *
		 * @return the line
		 */
		private byte[] closing()
		{
			return ("[End of " + path + "]\n").getBytes(UTF_8);
		}

		/**
		 * Returns the number of bytes that Codex shows of text after JSON escaping. Codex shows each byte that is not
		 * part of a UTF-8 character as U+FFFD, which takes 3 bytes. JSON escapes a quote, backslash, backspace, tab,
		 * newline, form feed or carriage return in 2 bytes, and another control character in 6.
		 *
		 * @param text the text
		 * @param from the index of the first byte
		 * @param to the index after the last byte
		 * @return the number of bytes
		 */
		private static long shownLength(byte[] text, int from, int to)
		{
			long length = 0;
			int i = from;
			while (i < to)
			{
				int value = text[i] & 0xFF;
				if (value < 0x80)
				{
					length += switch (value)
					{
						case '"', '\\', '\b', '\t', '\n', '\f', '\r' -> 2;
						default ->
						{
							if (value < 0x20)
								yield 6;
							yield 1;
						}
					};
					++i;
					continue;
				}
				int character = characterLength(text, i, to);
				if (character == 0)
				{
					length += 3;
					++i;
				}
				else
				{
					length += character;
					i += character;
				}
			}
			return length;
		}

		/**
		 * Returns the length of the UTF-8 character that starts at a byte that is not ASCII, by the rules that Codex
		 * uses to decode command output.
		 *
		 * @param text the text
		 * @param at the index of the byte
		 * @param to the index after the text's last byte
		 * @return the number of bytes of the character, or 0 if the byte does not start one
		 */
		private static int characterLength(byte[] text, int at, int to)
		{
			int lead = text[at] & 0xFF;
			int length;
			int secondLow = 0x80;
			int secondHigh = 0xBF;
			if (lead >= 0xC2 && lead <= 0xDF)
			{
				length = 2;
			}
			else if (lead >= 0xE0 && lead <= 0xEF)
			{
				length = 3;
				if (lead == 0xE0)
					secondLow = 0xA0;
				else if (lead == 0xED)
					secondHigh = 0x9F;
			}
			else if (lead >= 0xF0 && lead <= 0xF4)
			{
				length = 4;
				if (lead == 0xF0)
					secondLow = 0x90;
				else if (lead == 0xF4)
					secondHigh = 0x8F;
			}
			else
			{
				return 0;
			}
			if (at + length > to)
				return 0;
			int second = text[at + 1] & 0xFF;
			if (second < secondLow || second > secondHigh)
				return 0;
			for (int i = at + 2; i < at + length; ++i)
			{
				if ((text[i] & 0xC0) != 0x80)
					return 0;
			}
			return length;
		}
	}

	/**
	 * Reads the lines of a file, each without the newline that ends it.
	 */
	private static final class Lines
	{
		private final InputStream in;
		private final byte[] buffer = new byte[BUFFER_SIZE];
		private int position;
		private int end;

		/**
		 * Creates a reader.
		 *
		 * @param in the file's bytes
		 */
		Lines(InputStream in)
		{
			this.in = in;
		}

		/**
		 * Reads the next line.
		 *
		 * @param line receives the line's bytes, or {@code null} to skip the line
		 * @throws IOException if the file cannot be read
		 */
		void next(ByteArrayOutputStream line) throws IOException
		{
			if (position == end && !fill())
				return;
			while (true)
			{
				int newline = position;
				while (newline < end && buffer[newline] != '\n')
					++newline;
				if (line != null)
					line.write(buffer, position, newline - position);
				if (newline < end)
				{
					position = newline + 1;
					return;
				}
				position = end;
				if (!fill())
					return;
			}
		}

		/**
		 * Reads more of the file into the buffer.
		 *
		 * @return {@code false} at the end of the file
		 * @throws IOException if the file cannot be read
		 */
		private boolean fill() throws IOException
		{
			int length = in.read(buffer);
			if (length < 0)
				return false;
			position = 0;
			end = length;
			return true;
		}
	}
}
