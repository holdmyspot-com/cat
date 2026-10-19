package com.holdmyspot.cat.codex;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.SessionDirectory.Removal;
import com.holdmyspot.cat.common.TemporaryDirectories;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Replaces a {@code run-part} or {@code read-part} result that Codex cut with the command that prints the same part
 * again, and deletes the folder of a {@code run-part} output once the agent has seen its last part.
 * <p>
 * Both programs print parts that Codex shows whole unless the agent sets {@code max_output_tokens}, in which case Codex
 * removes lines from the middle of the part. Codex shows this hook the result after its cut but not the call's
 * {@code max_output_tokens}, so the hook recognizes a cut by Codex's markers. It returns a blocking decision, whose
 * reason Codex shows the model in place of the whole result: a line that says the output was cut off because the
 * agent set {@code max_output_tokens}, and names the {@code read-part} command that prints the same part again from
 * the saved output. A cut result that does not end with a part's closing line names the agent's command instead. The
 * hook deletes nothing after a cut, so that the command still finds the saved output.
 * <p>
 * A result that Codex did not cut and that ends with {@code [End of <path>. The file was deleted]} shows the agent the
 * whole last part of a saved output. When {@code <path>} is a file that {@code run-part} saved, the hook deletes the
 * file's folder and leaves the result unchanged, since that line already tells the agent that the file is gone.
 * Output whose last part the agent never sees uncut stays until its session's CAT session directory is removed, as
 * does all output while Codex runs no hooks. Every other result passes unchanged.
 */
public final class CodexPostToolUseHook
{
	private static final String EVENT_NAME = "PostToolUse";
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
	 * The line that ends the last part, which is the only line of an empty output: the saved file's path.
	 */
	private static final Pattern LAST_PART = Pattern.compile("(?:\\A|\n)\\[End of (?<path>[^\n]+)\\. The file was " +
		"deleted]\\s*\\z");
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
	 * saved output that cannot be deleted stays until its session's CAT session directory is removed.
	 *
	 * @param args a non-null empty command-line argument array
	 * @param shell the shell that ran the agent's command, and that runs the command that the repaired result names
	 * @param environment the hook's environment, whose {@code TMPDIR} or {@code TEMP} names the folder in which
	 *   {@code run-part} saves output outside a session (see {@link CodexRunPart#fallbackFolder(Map)})
	 * @param removal deletes the folder of a saved output once the agent has seen its last part uncut
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
			if (CUT.matcher(result.stringValue()).find())
			{
				String position = start(shell, command.stringValue(), script);
				String reason = instruction(repeatCommand(shell, command.stringValue(), result.stringValue(), script,
					position));
				output.write(json.writeValueAsBytes(Map.of("decision", "block", "reason", reason)));
				output.write('\n');
				return;
			}
			Optional<Path> saved = savedOutput(result.stringValue());
			if (saved.isPresent() && CodexRunPart.isSavedOutput(saved.get(), environment))
				removal.remove(saved.get().getParent());
		}
		catch (IOException | IllegalArgumentException | JacksonException e)
		{
			error.println("CAT " + EVENT_NAME + ": " + e.getMessage());
		}
	}

	/**
	 * Returns the command that prints a cut part of a {@code run-part} or {@code read-part} command again. The saved
	 * file already holds every line, so the command prints it from the position at which the cut part started, without
	 * running the agent's command a second time. For a result without a part's closing line, it is the agent's command.
	 *
	 * @param shell the shell that ran the command
	 * @param command the shell command that the agent ran
	 * @param result the command's result as Codex showed it
	 * @param script the match of the program that the command runs
	 * @param start the position at which the agent's command started the part that it printed
	 * @return the command
	 */
	private static String repeatCommand(CommandShell shell, String command, String result, Matcher script,
		String start)
	{
		Matcher nextPart = NEXT_PART.matcher(result);
		if (nextPart.find())
			return nextPart.group("command") + " " + start;
		Matcher lastPart = LAST_PART.matcher(result);
		if (lastPart.find())
		{
			String readPart = group(script, "folder", "quotedFolder") + shell.launcher("read-part");
			return shell.command(readPart, lastPart.group("path"), start);
		}
		return command;
	}

	/**
	 * Returns the saved file whose last part a result printed, as the part's closing line names it.
	 *
	 * @param result the command's result as Codex showed it
	 * @return the saved file, or empty when the result does not end with the last part's closing line or that line
	 *   does not name a valid absolute path
	 */
	private static Optional<Path> savedOutput(String result)
	{
		Matcher lastPart = LAST_PART.matcher(result);
		if (!lastPart.find())
			return Optional.empty();
		try
		{
			Path saved = Path.of(lastPart.group("path"));
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
	 * Returns the line that tells the model to run a command that shows the cut lines again.
	 *
	 * @param command the command
	 * @return the line
	 */
	private static String instruction(String command)
	{
		return "[The output was cut off because you set max_output_tokens. Run the following command without " +
			"max_output_tokens to see it: " + command + "]\n";
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
