package io.github.cowwoc.cat.codex;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;

import io.github.cowwoc.cat.common.Platform;
import io.github.cowwoc.cat.common.SessionWorkDirectory;
import io.github.cowwoc.cat.common.SessionWorkDirectory.Removal;
import io.github.cowwoc.cat.common.TemporaryDirectories;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Repairs the closing instruction of a {@code run-part} or {@code read-part} result that Codex cut, and deletes the
 * folder of a {@code run-part} output once the agent has seen all of it.
 * <p>
 * Both programs print parts that Codex shows whole unless the agent sets {@code max_output_tokens}, in which case Codex
 * removes lines from the middle of the part. The part's closing instruction then names the next part, so following it
 * would skip the removed lines. Codex shows this hook the result after its cut but not the call's
 * {@code max_output_tokens}, so the hook recognizes a cut by Codex's markers. It replaces the closing instruction with
 * one that prints the same part again without the limit, and returns the result as a blocking decision, whose reason
 * Codex shows the model in place of the result. Every other result passes unchanged.
 * <p>
 * A result that Codex did not cut and that ends with {@code [End of <path>]} shows the agent the last part of a saved
 * output. When {@code <path>} is a file that {@code run-part} saved, the hook deletes the file's folder, unless a part
 * that Codex cut has not yet been printed again uncut: the hook records the position of each cut part in the folder and
 * clears it when that part comes back uncut, so that the repaired instruction still finds the file. Output whose last
 * part the agent never reads stays until its session's work directory is removed.
 */
public final class CodexPostToolUseHook
{
	private static final String EVENT_NAME = "PostToolUse";
	/**
	 * The file, in the folder of a saved output, that holds the positions of the parts that Codex cut, one per line.
	 */
	private static final String CUT_POSITIONS = "cut-positions";
	/**
	 * The file, in the folder of a saved output, whose presence records that the agent saw the last part uncut while
	 * a cut part remained.
	 */
	private static final String LAST_PART_SEEN = "last-part-seen";
	private static final int MAXIMUM_EVENT_BYTES = 1_048_576;
	/**
	 * Codex's markers of a result whose middle it removed.
	 */
	private static final Pattern CUT = Pattern.compile("\\A\\s*Warning: truncated output|…\\d+ tokens truncated…");
	/**
	 * The line that ends a part that more parts follow: the command that prints the next part, then its position.
	 */
	private static final Pattern NEXT_PART = Pattern.compile("\n\\[(?:The last line continues at the start of " +
		"the next part\\. )?More output follows\\. Before any other command or answer, run: (?<command>.+) \\S+]\\s*\\z");
	/**
	 * The line that ends the last part: the saved file's path.
	 */
	private static final Pattern LAST_PART = Pattern.compile("\n\\[End of (?<path>[^\n]+)]\\s*\\z");
	/**
	 * Any bracketed line that ends a result.
	 */
	private static final Pattern CLOSING_LINE = Pattern.compile("\n\\[[^\n]*]\\s*\\z");
	/**
	 * The program that a command runs, and the folder that holds both programs. The program's path may be quoted, in
	 * which case it may hold spaces, and on Windows it ends with {@code .cmd}.
	 */
	private static final Pattern SCRIPT = Pattern.compile("['\"](?<quotedFolder>[^'\"\n]*[/\\\\]bin[/\\\\])" +
		"(?<quotedName>run-part|read-part)(?:\\.cmd)?['\"]|(?<folder>[^\\s'\"]*[/\\\\]bin[/\\\\])" +
		"(?<name>run-part|read-part)(?:\\.cmd)?(?=\\s|$)");
	/**
	 * A position that {@code read-part} prints: {@code <line>} or {@code <line>:<byte>}.
	 */
	private static final Pattern POSITION = Pattern.compile("[1-9][0-9]*(?::[1-9][0-9]*)?");

	/**
	 * Runs the harness PostToolUse hook.
	 *
	 * @param args a non-null empty command-line argument array
	 * @throws NullPointerException if {@code args} is null
	 */
	public static void main(String[] args)
	{
		requireThat(args, "args").isNotNull();
		run(args, CommandShell.current(), Platform.current().environment(System.getenv()),
			TemporaryDirectories::deleteRecursively, System.in, System.out, System.err);
	}

	/**
	 * Processes one Codex PostToolUse event. A failure leaves the result unchanged: the hook writes no response and
	 * reports the failure on {@code error}, because stopping the session would lose more output than the cut did. A
	 * failure to record a cut part's position is reported on {@code error} after the repaired result.
	 *
	 * @param args a non-null empty command-line argument array
	 * @param shell the shell that ran the agent's command, and that runs the command that the repaired result names
	 * @param environment the hook's environment, whose {@code TMPDIR} or {@code TEMP} names the folder in which
	 *   {@code run-part} saves output outside a session (see {@link CodexRunPart#fallbackFolder(Map)})
	 * @param removal deletes the folder of a saved output once the agent has seen all of it
	 * @param input non-null harness hook input
	 * @param output non-null harness hook output
	 * @param error non-null diagnostic output
	 * @throws NullPointerException if any argument is null
	 */
	public static void run(String[] args, CommandShell shell, Map<String, String> environment, Removal removal,
		InputStream input, OutputStream output, PrintStream error)
	{
		requireThat(args, "args").isNotNull();
		requireThat(shell, "shell").isNotNull();
		requireThat(environment, "environment").isNotNull();
		requireThat(removal, "removal").isNotNull();
		requireThat(input, "input").isNotNull();
		requireThat(output, "output").isNotNull();
		requireThat(error, "error").isNotNull();
		JsonMapper json = JsonMapper.builder().build();
		try
		{
			if (args.length != 0)
				throw new IllegalArgumentException(EVENT_NAME + " accepts no arguments");
			byte[] bytes = input.readNBytes(MAXIMUM_EVENT_BYTES + 1);
			if (bytes.length > MAXIMUM_EVENT_BYTES)
				throw new IOException(EVENT_NAME + " input is too large");
			JsonNode event = json.readTree(bytes);
			JsonNode command = event.path("tool_input").path("command");
			JsonNode result = event.path("tool_response");
			if (!command.isString() || !result.isString())
				return;
			Matcher script = SCRIPT.matcher(command.stringValue());
			if (!script.find())
				return;
			String position = start(shell, command.stringValue(), script);
			Optional<Path> folder = savedOutput(shell, result.stringValue()).
				flatMap(saved -> runPartFolder(saved, environment));
			if (CUT.matcher(result.stringValue()).find())
			{
				String repaired = repair(shell, command.stringValue(), result.stringValue(), script, position);
				output.write(json.writeValueAsBytes(Map.of("decision", "block", "reason", repaired)));
				output.write('\n');
				if (folder.isPresent())
				{
					try
					{
						recordCut(folder.get(), position);
					}
					catch (IOException e)
					{
						error.println("CAT " + EVENT_NAME + ": " + e.getMessage());
					}
				}
				return;
			}
			if (folder.isPresent() && isSeenWhole(folder.get(), position,
				LAST_PART.matcher(result.stringValue()).find()))
				removal.remove(folder.get());
		}
		catch (IOException | IllegalArgumentException | JacksonException e)
		{
			error.println("CAT " + EVENT_NAME + ": " + e.getMessage());
		}
	}

	/**
	 * Returns a cut result of a {@code run-part} or {@code read-part} command with its closing instruction replaced.
	 * The saved file already holds every line, so the new instruction prints it again from the position at which the
	 * cut part started, without running the original command a second time. A result without a part's closing line
	 * instead ends with an instruction to run the agent's command again.
	 *
	 * @param shell the shell that ran the command
	 * @param command the shell command that the agent ran
	 * @param result the command's result as Codex showed it
	 * @param script the match of the program that the command runs
	 * @param start the position at which the agent's command started the part that it printed
	 * @return the repaired result
	 */
	private static String repair(CommandShell shell, String command, String result, Matcher script, String start)
	{
		Matcher nextPart = NEXT_PART.matcher(result);
		if (nextPart.find())
			return result.substring(0, nextPart.start() + 1) + instruction(nextPart.group("command") + " " + start);
		Matcher lastPart = LAST_PART.matcher(result);
		if (lastPart.find())
		{
			String folder = group(script, "folder", "quotedFolder");
			String readPart = folder + shell.launcher("read-part");
			return result.substring(0, lastPart.start() + 1) +
				instruction(shell.command(readPart, lastPart.group("path"), start));
		}
		Matcher closingLine = CLOSING_LINE.matcher(result);
		String kept;
		if (closingLine.find())
			kept = result.substring(0, closingLine.start());
		else
			kept = result.stripTrailing();
		return kept + "\n" + instruction(command);
	}

	/**
	 * Returns the saved file whose part a result printed, as the part's closing line names it.
	 *
	 * @param shell the shell that ran the command
	 * @param result the command's result as Codex showed it
	 * @return the saved file, or empty when the result does not end with a part's closing line that names a valid
	 *   absolute path
	 */
	private static Optional<Path> savedOutput(CommandShell shell, String result)
	{
		String text;
		Matcher lastPart = LAST_PART.matcher(result);
		if (lastPart.find())
			text = lastPart.group("path");
		else
		{
			Matcher nextPart = NEXT_PART.matcher(result);
			if (!nextPart.find())
				return Optional.empty();
			String nextCommand = nextPart.group("command");
			Matcher script = SCRIPT.matcher(nextCommand);
			if (!script.find())
				return Optional.empty();
			Optional<List<String>> words = shell.words(nextCommand.substring(script.start()));
			if (words.isEmpty() || words.get().size() < 2)
				return Optional.empty();
			text = words.get().get(1);
			// A launcher that cmd.exe runs receives a path that needs quotes inside double quotes
			if (text.length() >= 2 && text.startsWith("\"") && text.endsWith("\""))
				text = text.substring(1, text.length() - 1);
		}
		try
		{
			Path saved = Path.of(text);
			if (!saved.isAbsolute())
				return Optional.empty();
			return Optional.of(saved.normalize());
		}
		catch (InvalidPathException _)
		{
			return Optional.empty();
		}
	}

	/**
	 * Returns the folder in which {@code run-part} saved a file.
	 *
	 * @param saved the saved file
	 * @param environment the hook's environment
	 * @return the file's folder when the file is {@code output.txt} with {@code output.txt.exit-status} beside it, in a
	 *   {@code run-part-*} folder directly inside a session's temporary directory or the folder that {@code run-part}
	 *   falls back to; otherwise empty, so that a file that the agent named itself is never deleted
	 */
	private static Optional<Path> runPartFolder(Path saved, Map<String, String> environment)
	{
		Path name = saved.getFileName();
		Path folder = saved.getParent();
		if (name == null || !name.toString().equals(CodexRunPart.SAVED_OUTPUT) || folder == null)
			return Optional.empty();
		Path folderName = folder.getFileName();
		Path temporary = folder.getParent();
		if (folderName == null || temporary == null || !folderName.toString().startsWith(CodexRunPart.FOLDER_PREFIX))
			return Optional.empty();

		boolean isTemporary = SessionWorkDirectory.isTemporaryDirectory(temporary) ||
			temporary.equals(CodexRunPart.fallbackFolder(environment).toAbsolutePath().normalize());
		Path exitStatus = folder.resolve(CodexRunPart.SAVED_OUTPUT + CodexRunPart.EXIT_STATUS_SUFFIX);
		if (isTemporary && Files.isDirectory(folder, LinkOption.NOFOLLOW_LINKS) &&
			Files.isRegularFile(exitStatus, LinkOption.NOFOLLOW_LINKS))
		{
			return Optional.of(folder);
		}
		return Optional.empty();
	}

	/**
	 * Records the position of a part that Codex cut, so that the folder is kept until that part is printed uncut.
	 *
	 * @param folder the folder of the saved output
	 * @param position the part's position
	 * @throws IOException if the record cannot be written
	 */
	private static void recordCut(Path folder, String position) throws IOException
	{
		Set<String> positions = cutPositions(folder);
		if (positions.add(position))
			Files.write(folder.resolve(CUT_POSITIONS), positions, UTF_8);
	}

	/**
	 * Records that the agent saw a part uncut, and indicates whether it has now seen all of the saved output: the last
	 * part, and every part that Codex cut, uncut.
	 *
	 * @param folder the folder of the saved output
	 * @param position the part's position
	 * @param lastPart {@code true} if the part is the last one
	 * @return {@code true} if the folder may be deleted
	 * @throws IOException if the records cannot be read or written
	 */
	private static boolean isSeenWhole(Path folder, String position, boolean lastPart) throws IOException
	{
		Path cutRecord = folder.resolve(CUT_POSITIONS);
		Path endRecord = folder.resolve(LAST_PART_SEEN);
		Set<String> positions = cutPositions(folder);
		if (positions.remove(position))
		{
			if (positions.isEmpty())
				Files.delete(cutRecord);
			else
				Files.write(cutRecord, positions, UTF_8);
		}
		boolean endSeen = lastPart || Files.exists(endRecord, LinkOption.NOFOLLOW_LINKS);
		if (!endSeen)
			return false;
		if (positions.isEmpty())
			return true;
		if (lastPart)
			Files.writeString(endRecord, "", UTF_8);
		return false;
	}

	/**
	 * Returns the recorded positions of the parts that Codex cut.
	 *
	 * @param folder the folder of the saved output
	 * @return the positions, in the order in which they were recorded
	 * @throws IOException if the record cannot be read
	 */
	private static Set<String> cutPositions(Path folder) throws IOException
	{
		Path record = folder.resolve(CUT_POSITIONS);
		if (!Files.isRegularFile(record, LinkOption.NOFOLLOW_LINKS))
			return new LinkedHashSet<>();
		Set<String> positions = new LinkedHashSet<>();
		for (String line : Files.readAllLines(record, UTF_8))
		{
			if (!line.isBlank())
				positions.add(line.strip());
		}
		return positions;
	}

	/**
	 * Returns the line that tells the model to run a command that shows the cut lines again.
	 *
	 * @param command the command
	 * @return the line
	 */
	private static String instruction(String command)
	{
		return "[The output was cut off because you set max_output_tokens. Before doing anything else, run the " +
			"following command (without setting max_output_tokens) in order to get the correct output: " + command +
			"]\n";
	}

	/**
	 * Returns a part of the match of the program that a command runs, whether or not the program's path is quoted.
	 *
	 * @param script the match
	 * @param unquoted the name of the part in a match of an unquoted path
	 * @param quoted the name of the part in a match of a quoted path
	 * @return the part
	 */
	private static String group(Matcher script, String unquoted, String quoted)
	{
		String part = script.group(unquoted);
		if (part != null)
			return part;
		return script.group(quoted);
	}

	/**
	 * Returns the position at which the agent's command started the part that it printed: the position that a
	 * {@code read-part} command names, or {@code 1} for a {@code read-part} command that names none and for a
	 * {@code run-part} command, whose part is the first one.
	 *
	 * @param shell the shell that ran the command
	 * @param command the shell command that the agent ran
	 * @param script the match of the program that the command runs
	 * @return the position
	 */
	private static String start(CommandShell shell, String command, Matcher script)
	{
		if (group(script, "name", "quotedName").equals("run-part"))
			return "1";
		Optional<List<String>> words = shell.words(command.substring(script.start()));
		if (words.isPresent() && words.get().size() > 2 && POSITION.matcher(words.get().get(2)).matches())
			return words.get().get(2);
		return "1";
	}

	/**
	 * Prevents instantiation.
	 */
	private CodexPostToolUseHook()
	{
	}
}
