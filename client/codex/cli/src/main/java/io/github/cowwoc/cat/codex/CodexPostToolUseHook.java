package io.github.cowwoc.cat.codex;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Repairs the closing instruction of a {@code run-part} or {@code read-part} result that Codex cut.
 * <p>
 * Both programs print parts that Codex shows whole unless the agent sets {@code max_output_tokens}, in which case Codex
 * removes lines from the middle of the part. The part's closing instruction then names the next part, so following it
 * would skip the removed lines. Codex shows this hook the result after its cut but not the call's
 * {@code max_output_tokens}, so the hook recognizes a cut by Codex's markers. It replaces the closing instruction with
 * one that prints the same part again without the limit, and returns the result as a blocking decision, whose reason
 * Codex shows the model in place of the result. Every other result passes unchanged.
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
		run(args, CommandShell.current(), System.in, System.out, System.err);
	}

	/**
	 * Processes one Codex PostToolUse event. A failure leaves the result unchanged: the hook writes no response and
	 * reports the failure on {@code error}, because stopping the session would lose more output than the cut did.
	 *
	 * @param args a non-null empty command-line argument array
	 * @param shell the shell that ran the agent's command, and that runs the command that the repaired result names
	 * @param input non-null harness hook input
	 * @param output non-null harness hook output
	 * @param error non-null diagnostic output
	 * @throws NullPointerException if any argument is null
	 */
	public static void run(String[] args, CommandShell shell, InputStream input, OutputStream output,
		PrintStream error)
	{
		requireThat(args, "args").isNotNull();
		requireThat(shell, "shell").isNotNull();
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
			Optional<String> repaired = repair(shell, command.stringValue(), result.stringValue());
			if (repaired.isEmpty())
				return;
			output.write(json.writeValueAsBytes(Map.of("decision", "block", "reason", repaired.get())));
			output.write('\n');
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
	 * @return the repaired result, or empty when Codex did not cut it or the command runs neither program
	 */
	private static Optional<String> repair(CommandShell shell, String command, String result)
	{
		Matcher script = SCRIPT.matcher(command);
		if (!script.find() || !CUT.matcher(result).find())
			return Optional.empty();
		Matcher nextPart = NEXT_PART.matcher(result);
		if (nextPart.find())
		{
			return Optional.of(result.substring(0, nextPart.start() + 1) +
				instruction(nextPart.group("command") + " " + start(shell, command, script)));
		}
		Matcher lastPart = LAST_PART.matcher(result);
		if (lastPart.find())
		{
			String folder = group(script, "folder", "quotedFolder");
			String readPart = folder + shell.launcher("read-part");
			return Optional.of(result.substring(0, lastPart.start() + 1) +
				instruction(shell.command(readPart, lastPart.group("path"), start(shell, command, script))));
		}
		Matcher closingLine = CLOSING_LINE.matcher(result);
		String kept;
		if (closingLine.find())
			kept = result.substring(0, closingLine.start());
		else
			kept = result.stripTrailing();
		return Optional.of(kept + "\n" + instruction(command));
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
