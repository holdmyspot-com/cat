package io.github.cowwoc.cat.common.optimizer.contract;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.optimizer.contract.ToolSupport.Reply;
import io.github.cowwoc.cat.common.optimizer.evidence.SessionEvidence.Tool;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * A built-in tool: CAT decides an assertion from a trial's record without a judge, as the assertion's {@code tool:}
 * line names.
 * <p>
 * Each tool detects one behavior, which names the failure that development groups. Its arguments may hold
 * {@code {{name}}} placeholders, replaced by the test case's values after the line is split, so a value that holds
 * spaces remains one argument. In a regular expression, a value matches literally.
 */
public sealed interface BuiltInTool
{
	/**
	 * Splits a {@code tool:} line into its arguments.
	 * <p>
	 * Arguments are separated by whitespace. An argument that starts with a double quote extends to the next unescaped
	 * double quote and may hold whitespace; inside it, {@code \"} stands for a double quote and {@code \\} for a
	 * backslash, and every other backslash is kept. An unquoted argument is taken as written and may not hold a double
	 * quote.
	 *
	 * @param line the line
	 * @return the tool's name followed by its arguments
	 * @throws NullPointerException     if {@code line} is null
	 * @throws IllegalArgumentException if a quoted argument is unterminated or is followed by other text, or if an
	 *                                  unquoted argument holds a double quote
	 */
	static List<String> arguments(String line)
	{
		return ToolLine.arguments(line);
	}

	/**
	 * Parses a {@code tool:} line.
	 *
	 * @param line the line, which starts with the tool's name
	 * @return the tool
	 * @throws NullPointerException     if {@code line} is null
	 * @throws IllegalArgumentException if the line names no built-in tool, or its arguments do not suit the tool
	 */
	static BuiltInTool parse(String line)
	{
		return ToolLine.parse(line);
	}

	/**
	 * Returns the tool's name, as the {@code tool:} line writes it.
	 *
	 * @return the name
	 */
	String name();

	/**
	 * Returns the behavior that a failure of the tool shows.
	 *
	 * @return the behavior's name
	 */
	String behavior();

	/**
	 * Returns the arguments that may hold placeholders.
	 *
	 * @return the arguments as written, before any value replaces a placeholder
	 */
	List<String> templates();

	/**
	 * Returns the names of the placeholders in the tool's arguments.
	 *
	 * @return the names, in order of first appearance
	 */
	default Set<String> placeholders()
	{
		Set<String> names = new LinkedHashSet<>();
		for (String template : templates())
			names.addAll(Placeholders.scenarioNames(template));
		return names;
	}

	/**
	 * Rejects values that would make an argument invalid, such as a value that turns a path argument into a path
	 * outside the project.
	 *
	 * @param textByName the text of each placeholder
	 * @throws NullPointerException     if {@code textByName} is null
	 * @throws IllegalArgumentException if a filled argument is invalid
	 */
	default void validate(Map<String, String> textByName)
	{
		requireThat(textByName, "textByName").isNotNull();
	}

	/**
	 * Returns the turn whose reply the tool examines.
	 *
	 * @return the turn, counted from 1, or {@code null} if the tool examines every turn or no reply
	 */
	default Integer turn()
	{
		return null;
	}

	/**
	 * Indicates whether the tool needs the exit status of commands, which some harnesses do not report.
	 *
	 * @return {@code true} if it does
	 */
	default boolean needsExitStatus()
	{
		return false;
	}

	/**
	 * Decides the tool for one trial.
	 *
	 * @param record     the trial's record
	 * @param textByName the text of each placeholder in the trial's test case
	 * @return the decision
	 * @throws NullPointerException      if any of the arguments are null
	 * @throws IOException               if the record cannot be read
	 * @throws UndecidableToolException if the record lacks what the tool observes
	 */
	Decision decide(TrialRecord record, Map<String, String> textByName) throws IOException, UndecidableToolException;

	/**
	 * The outcome of a tool in one trial.
	 *
	 * @param passed   {@code true} if the trial passed the tool
	 * @param observed what the tool found, quoting the evidence
	 */
	record Decision(boolean passed, String observed)
	{
		/**
		 * Creates a decision.
		 *
		 * @param passed   {@code true} if the trial passed the tool
		 * @param observed what the tool found, quoting the evidence
		 * @throws NullPointerException     if {@code observed} is null
		 * @throws IllegalArgumentException if {@code observed} is blank
		 */
		public Decision
		{
			requireThat(observed, "observed").isNotBlank();
		}
	}

	/**
	 * Every examined reply equals a text, ignoring leading and trailing whitespace.
	 *
	 * @param turn the turn whose reply the tool examines, from 1, or {@code null} for every turn
	 * @param text the expected reply
	 */
	record ExactText(Integer turn, String text) implements BuiltInTool
	{
		/**
		 * Creates the tool.
		 *
		 * @param turn the turn whose reply the tool examines, from 1, or {@code null} for every turn
		 * @param text the expected reply
		 * @throws NullPointerException     if {@code text} is null
		 * @throws IllegalArgumentException if {@code turn} is less than 1
		 */
		public ExactText
		{
			ToolSupport.requireTurn(turn);
			requireThat(text, "text").isNotNull();
		}

		@Override
		public String name()
		{
			return "exact-text";
		}

		@Override
		public String behavior()
		{
			return "reply_not_exact";
		}

		@Override
		public List<String> templates()
		{
			return List.of(text);
		}

		@Override
		public Decision decide(TrialRecord record, Map<String, String> textByName)
		{
			List<Reply> replies = ToolSupport.examined(record.replies(), turn);
			if (replies.isEmpty())
				return new Decision(false, ToolSupport.noReply(turn));
			String expected = Placeholders.fillScenario(text, textByName).strip();
			for (Reply reply : replies)
			{
				if (!reply.text().strip().equals(expected))
					return new Decision(false, reply.describeTurn() + " is " + ToolSupport.quote(reply.text()));
			}
			return new Decision(true, ToolSupport.subject(turn) + " is " + ToolSupport.quote(expected));
		}
	}

	/**
	 * No examined reply contains a text.
	 *
	 * @param turn the turn whose reply the tool examines, from 1, or {@code null} for every turn
	 * @param text the forbidden text
	 */
	record ForbiddenText(Integer turn, String text) implements BuiltInTool
	{
		/**
		 * Creates the tool.
		 *
		 * @param turn the turn whose reply the tool examines, from 1, or {@code null} for every turn
		 * @param text the forbidden text
		 * @throws NullPointerException     if {@code text} is null
		 * @throws IllegalArgumentException if {@code turn} is less than 1 or {@code text} is blank
		 */
		public ForbiddenText
		{
			ToolSupport.requireTurn(turn);
			ToolSupport.requireText(text, "forbidden-text's text");
		}

		@Override
		public String name()
		{
			return "forbidden-text";
		}

		@Override
		public String behavior()
		{
			return "forbidden_text_in_reply";
		}

		@Override
		public List<String> templates()
		{
			return List.of(text);
		}

		@Override
		public Decision decide(TrialRecord record, Map<String, String> textByName)
		{
			List<Reply> replies = ToolSupport.examined(record.replies(), turn);
			if (replies.isEmpty())
				return new Decision(false, ToolSupport.noReply(turn));
			String forbidden = Placeholders.fillScenario(text, textByName);
			for (Reply reply : replies)
			{
				if (reply.text().contains(forbidden))
				{
					return new Decision(false, reply.describeTurn() + " contains " + ToolSupport.quote(forbidden) +
						": " + ToolSupport.quote(reply.text()));
				}
			}
			return new Decision(true, ToolSupport.subject(turn) + " omits " + ToolSupport.quote(forbidden));
		}
	}

	/**
	 * Every examined reply contains a match of a regular expression.
	 *
	 * @param turn  the turn whose reply the tool examines, from 1, or {@code null} for every turn
	 * @param regex the Java regular expression
	 */
	record PatternMatch(Integer turn, String regex) implements BuiltInTool
	{
		/**
		 * Creates the tool.
		 *
		 * @param turn  the turn whose reply the tool examines, from 1, or {@code null} for every turn
		 * @param regex the Java regular expression
		 * @throws NullPointerException     if {@code regex} is null
		 * @throws IllegalArgumentException if {@code turn} is less than 1, or {@code regex} is blank or not a valid
		 *                                  Java regular expression
		 */
		public PatternMatch
		{
			ToolSupport.requireTurn(turn);
			ToolSupport.requireScenarioRegex("pattern", regex);
		}

		@Override
		public String name()
		{
			return "pattern";
		}

		@Override
		public String behavior()
		{
			return "pattern_missing_from_reply";
		}

		@Override
		public List<String> templates()
		{
			return List.of(regex);
		}

		@Override
		public Decision decide(TrialRecord record, Map<String, String> textByName)
		{
			List<Reply> replies = ToolSupport.examined(record.replies(), turn);
			if (replies.isEmpty())
				return new Decision(false, ToolSupport.noReply(turn));
			Pattern compiled = ToolSupport.compile(name(), regex, textByName);
			for (Reply reply : replies)
			{
				if (!compiled.matcher(reply.text()).find())
				{
					return new Decision(false, reply.describeTurn() + " has no match: " +
						ToolSupport.quote(reply.text()));
				}
			}
			return new Decision(true, ToolSupport.subject(turn) + " has a match");
		}
	}

	/**
	 * The number of matches of a regular expression in the examined replies is within bounds.
	 *
	 * @param turn    the turn whose reply the tool examines, from 1, or {@code null} for every turn
	 * @param regex   the Java regular expression
	 * @param minimum the fewest matches
	 * @param maximum the most matches
	 */
	record Count(Integer turn, String regex, int minimum, int maximum) implements BuiltInTool
	{
		/**
		 * Creates the tool.
		 *
		 * @param turn    the turn whose reply the tool examines, from 1, or {@code null} for every turn
		 * @param regex   the Java regular expression
		 * @param minimum the fewest matches
		 * @param maximum the most matches
		 * @throws NullPointerException     if {@code regex} is null
		 * @throws IllegalArgumentException if {@code turn} is less than 1, {@code regex} is blank or not a valid Java
		 *                                  regular expression, {@code minimum} is negative, or {@code minimum} exceeds
		 *                                  {@code maximum}
		 */
		public Count
		{
			ToolSupport.requireTurn(turn);
			ToolSupport.requireScenarioRegex("count", regex);
			if (minimum < 0)
				throw new IllegalArgumentException("count's minimum may not be negative, but it is " + minimum);
			if (minimum > maximum)
			{
				throw new IllegalArgumentException("count's minimum " + minimum + " exceeds its maximum " +
					maximum);
			}
		}

		@Override
		public String name()
		{
			return "count";
		}

		@Override
		public String behavior()
		{
			return "match_count_out_of_bounds";
		}

		@Override
		public List<String> templates()
		{
			return List.of(regex);
		}

		@Override
		public Decision decide(TrialRecord record, Map<String, String> textByName)
		{
			List<Reply> replies = ToolSupport.examined(record.replies(), turn);
			if (replies.isEmpty())
				return new Decision(false, ToolSupport.noReply(turn));
			Pattern compiled = ToolSupport.compile(name(), regex, textByName);
			long matches = 0;
			for (Reply reply : replies)
				matches += compiled.matcher(reply.text()).results().count();
			boolean passed = matches >= minimum && matches <= maximum;
			return new Decision(passed, matches + " matches in " + ToolSupport.subject(turn) + ", where " +
				minimum + " to " + maximum + " are expected");
		}
	}

	/**
	 * A path exists in the project after the trial.
	 *
	 * @param path the normalized project-relative path, which uses {@code /} as the separator
	 */
	record FileExists(String path) implements BuiltInTool
	{
		/**
		 * Creates the tool.
		 *
		 * @param path the normalized project-relative path, which uses {@code /} as the separator
		 * @throws NullPointerException     if {@code path} is null
		 * @throws IllegalArgumentException if {@code path} is blank or not a normalized project-relative path
		 */
		public FileExists
		{
			ToolSupport.requireScenarioPath("file-exists", path);
		}

		@Override
		public String name()
		{
			return "file-exists";
		}

		@Override
		public String behavior()
		{
			return "file_missing";
		}

		@Override
		public List<String> templates()
		{
			return List.of(path);
		}

		@Override
		public void validate(Map<String, String> textByName)
		{
			ToolSupport.fillPath(name(), path, textByName);
		}

		@Override
		public Decision decide(TrialRecord record, Map<String, String> textByName)
			throws IOException, UndecidableToolException
		{
			String filled = ToolRecords.path(this, path, textByName);
			if (record.exists(filled))
				return new Decision(true, "`" + filled + "` exists after the trial");
			return new Decision(false, "`" + filled + "` does not exist after the trial");
		}
	}

	/**
	 * A path does not exist in the project after the trial.
	 *
	 * @param path the normalized project-relative path, which uses {@code /} as the separator
	 */
	record FileAbsent(String path) implements BuiltInTool
	{
		/**
		 * Creates the tool.
		 *
		 * @param path the normalized project-relative path, which uses {@code /} as the separator
		 * @throws NullPointerException     if {@code path} is null
		 * @throws IllegalArgumentException if {@code path} is blank or not a normalized project-relative path
		 */
		public FileAbsent
		{
			ToolSupport.requireScenarioPath("file-absent", path);
		}

		@Override
		public String name()
		{
			return "file-absent";
		}

		@Override
		public String behavior()
		{
			return "file_present";
		}

		@Override
		public List<String> templates()
		{
			return List.of(path);
		}

		@Override
		public void validate(Map<String, String> textByName)
		{
			ToolSupport.fillPath(name(), path, textByName);
		}

		@Override
		public Decision decide(TrialRecord record, Map<String, String> textByName)
			throws IOException, UndecidableToolException
		{
			String filled = ToolRecords.path(this, path, textByName);
			if (record.exists(filled))
				return new Decision(false, "`" + filled + "` exists after the trial");
			return new Decision(true, "`" + filled + "` does not exist after the trial");
		}
	}

	/**
	 * A file of the project holds a text after the trial. A carriage return before a line feed is ignored, as is
	 * whitespace at the end of the file or the text.
	 *
	 * @param path the normalized project-relative path, which uses {@code /} as the separator
	 * @param text the expected content
	 */
	record FileContent(String path, String text) implements BuiltInTool
	{
		/**
		 * Creates the tool.
		 *
		 * @param path the normalized project-relative path, which uses {@code /} as the separator
		 * @param text the expected content
		 * @throws NullPointerException     if any of the arguments are null
		 * @throws IllegalArgumentException if {@code path} is blank or not a normalized project-relative path
		 */
		public FileContent
		{
			ToolSupport.requireScenarioPath("file-content", path);
			requireThat(text, "text").isNotNull();
		}

		@Override
		public String name()
		{
			return "file-content";
		}

		@Override
		public String behavior()
		{
			return "file_content_differs";
		}

		@Override
		public List<String> templates()
		{
			return List.of(path, text);
		}

		@Override
		public void validate(Map<String, String> textByName)
		{
			ToolSupport.fillPath(name(), path, textByName);
		}

		@Override
		public Decision decide(TrialRecord record, Map<String, String> textByName)
			throws IOException, UndecidableToolException
		{
			String filled = ToolRecords.path(this, path, textByName);
			Optional<String> content = record.content(filled);
			if (content.isEmpty())
				return new Decision(false, "`" + filled + "` is not a file after the trial");
			String actual = ToolRecords.normalizeContent(content.get());
			String expected = ToolRecords.normalizeContent(Placeholders.fillScenario(text, textByName));
			if (actual.equals(expected))
				return new Decision(true, "`" + filled + "` holds the expected text");
			return new Decision(false, "`" + filled + "` holds " + ToolSupport.quote(actual));
		}
	}

	/**
	 * The last recorded command that matches a regular expression exited with a status.
	 *
	 * @param regex  the Java regular expression that the command must contain a match of
	 * @param status the expected exit status
	 */
	record ExitStatus(String regex, int status) implements BuiltInTool
	{
		/**
		 * Creates the tool.
		 *
		 * @param regex  the Java regular expression that the command must contain a match of
		 * @param status the expected exit status
		 * @throws NullPointerException     if {@code regex} is null
		 * @throws IllegalArgumentException if {@code regex} is blank or not a valid Java regular expression
		 */
		public ExitStatus
		{
			ToolSupport.requireScenarioRegex("exit-status", regex);
		}

		@Override
		public String name()
		{
			return "exit-status";
		}

		@Override
		public String behavior()
		{
			return "wrong_exit_status";
		}

		@Override
		public List<String> templates()
		{
			return List.of(regex);
		}

		@Override
		public boolean needsExitStatus()
		{
			return true;
		}

		@Override
		public Decision decide(TrialRecord record, Map<String, String> textByName) throws UndecidableToolException
		{
			Pattern compiled = ToolSupport.compile(name(), regex, textByName);
			Tool last = null;
			for (Tool tool : record.tools())
			{
				if (tool.command() && compiled.matcher(tool.input()).find())
					last = tool;
			}
			if (last == null)
			{
				return new Decision(false, "no recorded command matches " +
					ToolSupport.quote(Placeholders.fillScenario(regex, textByName)));
			}
			if (last.exitStatus() == null)
			{
				throw new UndecidableToolException("The harness reported no exit status for the command " +
					ToolSupport.quote(last.input()));
			}
			String observed = "the last matching command, " + ToolSupport.quote(last.input()) + ", exited with " +
				last.exitStatus();
			return new Decision(last.exitStatus() == status, observed);
		}
	}

	/**
	 * The trial called a tool.
	 *
	 * @param tool the tool's name
	 */
	record ToolUsed(String tool) implements BuiltInTool
	{
		/**
		 * Creates the tool.
		 *
		 * @param tool the tool's name
		 * @throws NullPointerException     if {@code tool} is null
		 * @throws IllegalArgumentException if {@code tool} is blank
		 */
		public ToolUsed
		{
			ToolSupport.requireText(tool, "tool-used's tool");
		}

		@Override
		public String name()
		{
			return "tool-used";
		}

		@Override
		public String behavior()
		{
			return "tool_not_called";
		}

		@Override
		public List<String> templates()
		{
			return List.of(tool);
		}

		@Override
		public Decision decide(TrialRecord record, Map<String, String> textByName)
		{
			String filled = Placeholders.fillScenario(tool, textByName);
			Optional<Tool> call = ToolRecords.firstCall(record, filled);
			if (call.isPresent())
				return new Decision(true, ToolRecords.describeCall(call.get()));
			return new Decision(false, "the trial never called " + filled + "; " + ToolRecords.describeTools(record));
		}
	}

	/**
	 * The trial never called a tool.
	 *
	 * @param tool the tool's name
	 */
	record ToolUnused(String tool) implements BuiltInTool
	{
		/**
		 * Creates the tool.
		 *
		 * @param tool the tool's name
		 * @throws NullPointerException     if {@code tool} is null
		 * @throws IllegalArgumentException if {@code tool} is blank
		 */
		public ToolUnused
		{
			ToolSupport.requireText(tool, "tool-unused's tool");
		}

		@Override
		public String name()
		{
			return "tool-unused";
		}

		@Override
		public String behavior()
		{
			return "tool_called";
		}

		@Override
		public List<String> templates()
		{
			return List.of(tool);
		}

		@Override
		public Decision decide(TrialRecord record, Map<String, String> textByName)
		{
			String filled = Placeholders.fillScenario(tool, textByName);
			Optional<Tool> call = ToolRecords.firstCall(record, filled);
			if (call.isPresent())
				return new Decision(false, ToolRecords.describeCall(call.get()));
			return new Decision(true, "the trial never called " + filled);
		}
	}

	/**
	 * The trial made no tool call.
	 */
	record NoToolCall() implements BuiltInTool
	{
		@Override
		public String name()
		{
			return "no-tool-call";
		}

		@Override
		public String behavior()
		{
			return "unneeded_tool_call";
		}

		@Override
		public List<String> templates()
		{
			return List.of();
		}

		@Override
		public Decision decide(TrialRecord record, Map<String, String> textByName)
		{
			if (record.tools().isEmpty())
				return new Decision(true, "the trial made no tool call");
			return new Decision(false, ToolRecords.describeCall(record.tools().getFirst()));
		}
	}

	/**
	 * Every non-blank line of a staged file appeared in the trial's tool output, as a whole output line or after a
	 * line-number prefix.
	 *
	 * @param path the normalized project-relative path of the file, which uses {@code /} as the separator
	 */
	record ReadInFull(String path) implements BuiltInTool
	{
		/**
		 * Creates the tool.
		 *
		 * @param path the normalized project-relative path of the file, which uses {@code /} as the separator
		 * @throws NullPointerException     if {@code path} is null
		 * @throws IllegalArgumentException if {@code path} is blank or not a normalized project-relative path
		 */
		public ReadInFull
		{
			ToolSupport.requireScenarioPath("read-in-full", path);
		}

		@Override
		public String name()
		{
			return "read-in-full";
		}

		@Override
		public String behavior()
		{
			return "file_not_read_in_full";
		}

		@Override
		public List<String> templates()
		{
			return List.of(path);
		}

		@Override
		public void validate(Map<String, String> textByName)
		{
			ToolSupport.fillPath(name(), path, textByName);
		}

		@Override
		public Decision decide(TrialRecord record, Map<String, String> textByName)
			throws IOException, UndecidableToolException
		{
			String filled = ToolRecords.path(this, path, textByName);
			List<String> lines = ToolRecords.stagedLines(record, filled);
			Set<String> seen = ToolRecords.outputLines(record);
			int missing = 0;
			String firstMissing = null;
			for (String line : lines)
			{
				if (seen.contains(line))
					continue;
				++missing;
				if (firstMissing == null)
					firstMissing = line;
			}
			if (missing == 0)
			{
				return new Decision(true, "all " + lines.size() + " non-blank lines of `" + filled +
					"` appeared in tool output");
			}
			return new Decision(false, missing + " of " + lines.size() + " non-blank lines of `" + filled +
				"` never appeared in tool output, the first being " + ToolSupport.quote(firstMissing));
		}
	}

	/**
	 * At most a percentage of a staged file's non-blank lines appeared in the trial's tool output, as whole output
	 * lines or after a line-number prefix.
	 *
	 * @param path    the normalized project-relative path of the file, which uses {@code /} as the separator
	 * @param percent the largest percentage of the lines that may appear, from 0 to 100
	 */
	record ReadAtMost(String path, BigDecimal percent) implements BuiltInTool
	{
		/**
		 * The scale of the percentage that an observed value reports.
		 */
		private static final int REPORTED_SCALE = 2;

		/**
		 * Creates the tool.
		 *
		 * @param path    the normalized project-relative path of the file, which uses {@code /} as the separator
		 * @param percent the largest percentage of the lines that may appear, from 0 to 100
		 * @throws NullPointerException     if any of the arguments are null
		 * @throws IllegalArgumentException if {@code path} is blank or not a normalized project-relative path, or
		 *                                  {@code percent} is outside 0 to 100
		 */
		public ReadAtMost
		{
			ToolSupport.requireScenarioPath("read-at-most", path);
			requireThat(percent, "percent").isNotNull();
			ToolLine.requirePercent(percent);
		}

		@Override
		public String name()
		{
			return "read-at-most";
		}

		@Override
		public String behavior()
		{
			return "file_read_beyond_limit";
		}

		@Override
		public List<String> templates()
		{
			return List.of(path);
		}

		@Override
		public void validate(Map<String, String> textByName)
		{
			ToolSupport.fillPath(name(), path, textByName);
		}

		@Override
		public Decision decide(TrialRecord record, Map<String, String> textByName)
			throws IOException, UndecidableToolException
		{
			String filled = ToolRecords.path(this, path, textByName);
			List<String> lines = ToolRecords.stagedLines(record, filled);
			Set<String> output = ToolRecords.outputLines(record);
			int seen = 0;
			for (String line : lines)
			{
				if (output.contains(line))
					++seen;
			}
			BigDecimal total = BigDecimal.valueOf(lines.size());
			BigDecimal appeared = BigDecimal.valueOf(seen).multiply(BigDecimal.valueOf(100));
			boolean passed = appeared.compareTo(percent.multiply(total)) <= 0;
			String share;
			if (lines.isEmpty())
				share = "0";
			else
				share = appeared.divide(total, REPORTED_SCALE, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
			return new Decision(passed, seen + " of " + lines.size() + " non-blank lines of `" + filled +
				"` (" + share + "%) appeared in tool output, where at most " + percent.toPlainString() +
				"% may appear");
		}
	}
}
