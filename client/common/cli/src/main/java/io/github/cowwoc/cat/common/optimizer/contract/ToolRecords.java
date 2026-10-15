package io.github.cowwoc.cat.common.optimizer.contract;

import io.github.cowwoc.cat.common.optimizer.evidence.SessionEvidence;
import io.github.cowwoc.cat.common.optimizer.evidence.SessionEvidence.Tool;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The reading of a {@link TrialRecord} that several kinds of {@link BuiltInTool} share.
 */
final class ToolRecords
{
	/**
	 * Matches the line-number prefix that a file-reading tool or command writes before a line: a number followed by a
	 * tab, an arrow or a colon, as {@code cat -n}, {@code nl}, {@code grep -n} and harness reading tools write it, or
	 * {@code L<n>: }.
	 */
	private static final Pattern LINE_NUMBER = Pattern.compile("^(?:\\s*\\d+(?:\\t|→|:)|L\\d+: )");

	/**
	 * Replaces the placeholders of a path.
	 *
	 * @param builtInTool the built-in tool
	 * @param path       the path as written
	 * @param textByName the text of each placeholder
	 * @return the filled path
	 * @throws UndecidableToolException if the filled path is not a normalized project-relative path
	 */
	static String path(BuiltInTool builtInTool, String path, Map<String, String> textByName)
		throws UndecidableToolException
	{
		try
		{
			return ToolSupport.fillPath(builtInTool.name(), path, textByName);
		}
		catch (IllegalArgumentException e)
		{
			throw new UndecidableToolException(e.getMessage());
		}
	}

	/**
	 * Normalizes a file's text for comparison: a carriage return before a line feed is dropped, as is trailing
	 * whitespace.
	 *
	 * @param text the text
	 * @return the normalized text
	 */
	static String normalizeContent(String text)
	{
		return text.replace("\r\n", "\n").stripTrailing();
	}

	/**
	 * Returns the first call of a tool.
	 *
	 * @param record the trial's record
	 * @param tool   the tool's name
	 * @return the call, or empty if the trial never called the tool
	 */
	static Optional<Tool> firstCall(TrialRecord record, String tool)
	{
		return record.tools().stream().filter(call -> call.tool().equals(tool)).findFirst();
	}

	/**
	 * Describes a tool call for an observed value.
	 *
	 * @param call the call
	 * @return the description
	 */
	static String describeCall(Tool call)
	{
		String actor = "the main agent";
		if (!call.agent().equals(SessionEvidence.ROOT_AGENT))
			actor = "child " + call.agent();
		return actor + " called " + call.tool() + " in turn " + call.turn() + " with " +
			ToolSupport.quote(call.input());
	}

	/**
	 * Lists the tools that a trial called, for an observed value.
	 *
	 * @param record the trial's record
	 * @return the description
	 */
	static String describeTools(TrialRecord record)
	{
		Set<String> tools = new LinkedHashSet<>();
		for (Tool call : record.tools())
			tools.add(call.tool());
		if (tools.isEmpty())
			return "it made no tool call";
		return "it called " + String.join(", ", tools);
	}

	/**
	 * Returns the non-blank lines of a file as it was when the trial started, without trailing whitespace.
	 *
	 * @param record the trial's record
	 * @param path   the normalized project-relative path
	 * @return the lines, in file order
	 * @throws IOException               if the file cannot be read
	 * @throws UndecidableToolException if the path was not a file when the trial started
	 */
	static List<String> initialLines(TrialRecord record, String path) throws IOException, UndecidableToolException
	{
		Optional<String> initial = record.initial(path);
		if (initial.isEmpty())
			throw new UndecidableToolException("`" + path + "` was not a file when the trial started");
		List<String> lines = new ArrayList<>();
		for (String line : initial.get().split("\n", -1))
		{
			String stripped = line.stripTrailing();
			if (!stripped.isEmpty())
				lines.add(stripped);
		}
		return lines;
	}

	/**
	 * Returns every line of the tool results that the trial's agents received, without trailing whitespace, both as
	 * written and without a line-number prefix.
	 *
	 * @param record the trial's record
	 * @return the lines
	 */
	static Set<String> outputLines(TrialRecord record)
	{
		Set<String> lines = new HashSet<>();
		for (String result : record.received())
		{
			for (String line : result.split("\n", -1))
			{
				String stripped = line.stripTrailing();
				lines.add(stripped);
				lines.add(LINE_NUMBER.matcher(stripped).replaceFirst(""));
			}
		}
		return lines;
	}

	/**
	 * Prevents instantiation.
	 */
	private ToolRecords()
	{
	}
}
