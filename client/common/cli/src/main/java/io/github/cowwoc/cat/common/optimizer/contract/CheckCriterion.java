package io.github.cowwoc.cat.common.optimizer.contract;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.optimizer.contract.CheckSupport.Reply;

import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A condition that CAT evaluates directly from recorded trial evidence, without asking a judge. The agent that
 * derives the proposal selects one of CAT's implemented conditions and supplies its parameters; it never writes
 * check code.
 * <p>
 * A string parameter may contain {@code {{dimension}}} placeholders, replaced by the text selected for that
 * variation dimension. An inserted value in a regular expression is matched literally.
 * <p>
 * A reply condition examines the reply to its {@code turn}, or every reply when {@code turn} is {@code null}. A
 * trial that gave no examined reply fails it.
 */
public sealed interface CheckCriterion
{
	/**
	 * Returns the kind's name in the contract's JSON form.
	 *
	 * @return the name
	 */
	String kind();

	/**
	 * Returns what the check requires, with its parameters.
	 *
	 * @return the description
	 */
	String describe();

	/**
	 * Returns the string parameters, which may name variation dimensions.
	 *
	 * @return the parameters' values
	 */
	List<String> templates();

	/**
	 * Returns the variation dimensions that the parameters name.
	 *
	 * @return the dimension names
	 */
	default Set<String> dimensions()
	{
		Set<String> dimensions = new LinkedHashSet<>();
		for (String template : templates())
			dimensions.addAll(Placeholders.names(template));
		return dimensions;
	}

	/**
	 * Decides whether the recorded evidence satisfies the condition.
	 *
	 * @param evidence the recorded replies, files and tool calls to examine
	 * @param textByDimension the text of the variation value that the evidence drew for each dimension that the
	 *   parameters name
	 * @return the decision
	 * @throws IOException if the evidence cannot be read
	 * @throws UndecidableCheckException if the evidence does not record what the kind observes
	 */
	Decision decide(CheckEvidence evidence, Map<String, String> textByDimension)
		throws IOException, UndecidableCheckException;

	/**
	 * A check's decision on one piece of evidence.
	 *
	 * @param passed {@code true} if the evidence satisfied the check
	 * @param observed what the check observed
	 */
	record Decision(boolean passed, String observed)
	{
		/**
		 * Creates a decision.
		 *
		 * @param passed {@code true} if the evidence satisfied the check
		 * @param observed what the check observed
		 * @throws NullPointerException if {@code observed} is null
		 * @throws IllegalArgumentException if {@code observed} is blank
		 */
		public Decision
		{
			requireThat(observed, "observed").isNotBlank();
		}
	}

	/**
	 * A condition that examines the replies.
	 */
	sealed interface ReplyCriterion extends CheckCriterion
	{
		/**
		 * Returns the turn whose reply the check examines.
		 *
		 * @return the turn, from 1, or {@code null} for every turn
		 */
		Integer turn();
	}

	/**
	 * Every examined reply equals the expected text after leading and trailing whitespace is removed from both.
	 *
	 * @param turn the turn whose reply the check examines, from 1, or {@code null} for every turn
	 * @param text the text
	 */
	record ExactText(Integer turn, String text) implements ReplyCriterion
	{
		/**
		 * Creates the criterion.
		 *
		 * @param turn the turn whose reply the check examines, from 1, or {@code null} for every turn
		 * @param text the text
		 * @throws NullPointerException if {@code text} is null
		 * @throws IllegalArgumentException if {@code turn} is less than 1 or {@code text} is blank
		 */
		public ExactText
		{
			CheckSupport.requireTurn(turn);
			CheckSupport.requireText(text, "text");
		}

		@Override
		public String kind()
		{
			return "exact_text";
		}

		@Override
		public String describe()
		{
			return CheckSupport.subject(turn) + " is exactly \"" + text + "\"";
		}

		@Override
		public List<String> templates()
		{
			return List.of(text);
		}

		@Override
		public Decision decide(CheckEvidence evidence, Map<String, String> textByDimension)
		{
			List<Reply> replies = CheckSupport.examined(evidence.replies(), turn);
			if (replies.isEmpty())
				return new Decision(false, CheckSupport.noReply(turn));
			String expected = Placeholders.fill(text, textByDimension).strip();
			StringBuilder observed = new StringBuilder();
			for (Reply reply : replies)
			{
				if (!reply.text().strip().equals(expected))
					return new Decision(false, reply.describeTurn() + " is " + CheckSupport.quote(reply.text()));
				if (!observed.isEmpty())
					observed.append("; ");
				observed.append(reply.describeTurn()).append(" is ").append(CheckSupport.quote(reply.text()));
			}
			return new Decision(true, observed.toString());
		}
	}

	/**
	 * No examined reply contains a text.
	 *
	 * @param turn the turn whose reply the check examines, from 1, or {@code null} for every turn
	 * @param text the forbidden text
	 */
	record ForbiddenText(Integer turn, String text) implements ReplyCriterion
	{
		/**
		 * Creates the criterion.
		 *
		 * @param turn the turn whose reply the check examines, from 1, or {@code null} for every turn
		 * @param text the forbidden text
		 * @throws NullPointerException if {@code text} is null
		 * @throws IllegalArgumentException if {@code turn} is less than 1 or {@code text} is blank
		 */
		public ForbiddenText
		{
			CheckSupport.requireTurn(turn);
			CheckSupport.requireText(text, "text");
		}

		@Override
		public String kind()
		{
			return "forbidden_text";
		}

		@Override
		public String describe()
		{
			if (turn == null)
				return "no reply contains \"" + text + "\"";
			return "the reply to turn " + turn + " does not contain \"" + text + "\"";
		}

		@Override
		public List<String> templates()
		{
			return List.of(text);
		}

		@Override
		public Decision decide(CheckEvidence evidence, Map<String, String> textByDimension)
		{
			List<Reply> replies = CheckSupport.examined(evidence.replies(), turn);
			if (replies.isEmpty())
				return new Decision(false, CheckSupport.noReply(turn));
			String forbidden = Placeholders.fill(text, textByDimension);
			for (Reply reply : replies)
			{
				if (reply.text().contains(forbidden))
				{
					return new Decision(false, reply.describeTurn() + " contains " + CheckSupport.quote(forbidden) + ": " +
						CheckSupport.quote(reply.text()));
				}
			}
			if (turn == null)
				return new Decision(true, "no reply contains " + CheckSupport.quote(forbidden));
			return new Decision(true, "the reply to turn " + turn + " does not contain " +
				CheckSupport.quote(forbidden));
		}
	}

	/**
	 * Every examined reply contains a match of a Java regular expression.
	 *
	 * @param turn the turn whose reply the check examines, from 1, or {@code null} for every turn
	 * @param pattern the regular expression
	 */
	record PatternMatch(Integer turn, String pattern) implements ReplyCriterion
	{
		/**
		 * Creates the criterion.
		 *
		 * @param turn the turn whose reply the check examines, from 1, or {@code null} for every turn
		 * @param pattern the regular expression
		 * @throws NullPointerException if {@code pattern} is null
		 * @throws IllegalArgumentException if {@code turn} is less than 1, or {@code pattern} is blank or is not a
		 *   valid Java regular expression
		 */
		public PatternMatch
		{
			CheckSupport.requireTurn(turn);
			CheckSupport.requireRegex(pattern, "pattern");
		}

		@Override
		public String kind()
		{
			return "pattern";
		}

		@Override
		public String describe()
		{
			return CheckSupport.subject(turn) + " matches the regular expression `" + pattern + "`";
		}

		@Override
		public List<String> templates()
		{
			return List.of(pattern);
		}

		@Override
		public Decision decide(CheckEvidence evidence, Map<String, String> textByDimension)
		{
			List<Reply> replies = CheckSupport.examined(evidence.replies(), turn);
			if (replies.isEmpty())
				return new Decision(false, CheckSupport.noReply(turn));
			Pattern compiled = Pattern.compile(Placeholders.fillLiterally(pattern, textByDimension));
			StringBuilder observed = new StringBuilder();
			for (Reply reply : replies)
			{
				Matcher matcher = compiled.matcher(reply.text());
				if (!matcher.find())
				{
					return new Decision(false, reply.describeTurn() + " has no match: " +
						CheckSupport.quote(reply.text()));
				}
				if (!observed.isEmpty())
					observed.append("; ");
				observed.append(reply.describeTurn()).append(" matches ").append(CheckSupport.quote(matcher.group()));
			}
			return new Decision(true, observed.toString());
		}
	}

	/**
	 * The number of matches of a Java regular expression in the examined replies lies within inclusive bounds.
	 *
	 * @param turn the turn whose reply the check examines, from 1, or {@code null} to count across every reply
	 * @param pattern the regular expression
	 * @param minimum the fewest matches allowed, or {@code null} for no lower bound
	 * @param maximum the most matches allowed, or {@code null} for no upper bound
	 */
	record Count(Integer turn, String pattern, Integer minimum, Integer maximum) implements ReplyCriterion
	{
		/**
		 * Creates the criterion.
		 *
		 * @param turn the turn whose reply the check examines, from 1, or {@code null} to count across every reply
		 * @param pattern the regular expression
		 * @param minimum the fewest matches allowed, or {@code null} for no lower bound
		 * @param maximum the most matches allowed, or {@code null} for no upper bound
		 * @throws NullPointerException if {@code pattern} is null
		 * @throws IllegalArgumentException if {@code turn} is less than 1, {@code pattern} is blank or is not a valid
		 *   Java regular expression, both bounds are {@code null}, a bound is negative, or {@code minimum} exceeds
		 *   {@code maximum}
		 */
		public Count
		{
			CheckSupport.requireTurn(turn);
			CheckSupport.requireRegex(pattern, "pattern");
			if (minimum == null && maximum == null)
				throw new IllegalArgumentException("A count needs a minimum, a maximum or both");
			if (minimum != null)
				requireThat(minimum, "minimum").isNotNegative();
			if (maximum != null)
				requireThat(maximum, "maximum").isNotNegative();
			if (minimum != null && maximum != null)
				requireThat(minimum, "minimum").isLessThanOrEqualTo(maximum, "maximum");
		}

		@Override
		public String kind()
		{
			return "count";
		}

		@Override
		public String describe()
		{
			String bounds;
			if (minimum == null)
				bounds = "at most " + maximum;
			else if (maximum == null)
				bounds = "at least " + minimum;
			else if (minimum.equals(maximum))
				bounds = "exactly " + minimum;
			else
				bounds = "between " + minimum + " and " + maximum;
			String subject;
			if (turn == null)
				subject = "the replies together have ";
			else
				subject = "the reply to turn " + turn + " has ";
			return subject + bounds + " matches of the regular expression `" + pattern + "`";
		}

		@Override
		public List<String> templates()
		{
			return List.of(pattern);
		}

		@Override
		public Decision decide(CheckEvidence evidence, Map<String, String> textByDimension)
		{
			List<Reply> replies = CheckSupport.examined(evidence.replies(), turn);
			if (replies.isEmpty())
				return new Decision(false, CheckSupport.noReply(turn));
			Pattern compiled = Pattern.compile(Placeholders.fillLiterally(pattern, textByDimension));
			long matches = 0;
			for (Reply reply : replies)
				matches += compiled.matcher(reply.text()).results().count();
			boolean passed = (minimum == null || matches >= minimum) && (maximum == null || matches <= maximum);
			String where;
			if (turn == null)
				where = " across the replies";
			else
				where = " in the reply to turn " + turn;
			return new Decision(passed, matches + " matches" + where);
		}
	}

	/**
	 * A file or directory exists, or does not exist, in the project after the trial.
	 *
	 * @param path the normalized project-relative path, which uses {@code /} as the separator
	 * @param exists {@code true} if the path must exist, {@code false} if it must not
	 */
	record FileExists(String path, boolean exists) implements CheckCriterion
	{
		/**
		 * Creates the criterion.
		 *
		 * @param path the normalized project-relative path, which uses {@code /} as the separator
		 * @param exists {@code true} if the path must exist, {@code false} if it must not
		 * @throws NullPointerException if {@code path} is null
		 * @throws IllegalArgumentException if {@code path} is blank or is not a normalized project-relative path
		 */
		public FileExists
		{
			CheckSupport.requirePath(path);
		}

		@Override
		public String kind()
		{
			return "file_exists";
		}

		@Override
		public String describe()
		{
			if (exists)
				return "`" + path + "` exists after the trial";
			return "`" + path + "` does not exist after the trial";
		}

		@Override
		public List<String> templates()
		{
			return List.of(path);
		}

		@Override
		public Decision decide(CheckEvidence evidence, Map<String, String> textByDimension) throws IOException
		{
			String filled = Placeholders.fill(path, textByDimension);
			if (!ProjectPaths.isNormalized(filled))
				return new Decision(false, "`" + filled + "` is not a normalized project-relative path");
			boolean found = evidence.exists(filled);
			if (found)
				return new Decision(exists, "`" + filled + "` exists");
			return new Decision(!exists, "`" + filled + "` does not exist");
		}
	}

	/**
	 * A file in the project after the trial equals the expected text after CRLF line endings become {@code \n} and
	 * trailing whitespace is removed from both.
	 *
	 * @param path the normalized project-relative path, which uses {@code /} as the separator
	 * @param text the file's expected content
	 */
	record FileContent(String path, String text) implements CheckCriterion
	{
		/**
		 * Creates the criterion.
		 *
		 * @param path the normalized project-relative path, which uses {@code /} as the separator
		 * @param text the file's expected content
		 * @throws NullPointerException if any argument is null
		 * @throws IllegalArgumentException if {@code path} is blank or is not a normalized project-relative path
		 */
		public FileContent
		{
			CheckSupport.requirePath(path);
			requireThat(text, "text").isNotNull();
		}

		@Override
		public String kind()
		{
			return "file_content";
		}

		@Override
		public String describe()
		{
			return "`" + path + "` holds exactly \"" + text + "\"";
		}

		@Override
		public List<String> templates()
		{
			return List.of(path, text);
		}

		@Override
		public Decision decide(CheckEvidence evidence, Map<String, String> textByDimension) throws IOException
		{
			String filled = Placeholders.fill(path, textByDimension);
			if (!ProjectPaths.isNormalized(filled))
				return new Decision(false, "`" + filled + "` is not a normalized project-relative path");
			Optional<String> content = evidence.content(filled);
			if (content.isEmpty())
				return new Decision(false, "`" + filled + "` is not a file");
			boolean passed = normalize(content.get()).equals(normalize(Placeholders.fill(text, textByDimension)));
			return new Decision(passed, "`" + filled + "` holds " + CheckSupport.quote(content.get()));
		}

		/**
		 * Returns a file's text with {@code \n} line endings and without trailing whitespace.
		 *
		 * @param content the text
		 * @return the normalized text
		 */
		private static String normalize(String content)
		{
			return content.replace("\r\n", "\n").stripTrailing();
		}
	}

	/**
	 * The last recorded command that matches a Java regular expression exited with a status. CAT never runs the
	 * command: the status is the one that the trial's evidence recorded.
	 *
	 * @param command the regular expression that the command line must contain a match of
	 * @param status the required exit status
	 */
	record ExitStatus(String command, int status) implements CheckCriterion
	{
		/**
		 * Creates the criterion.
		 *
		 * @param command the regular expression that the command line must contain a match of
		 * @param status the required exit status
		 * @throws NullPointerException if {@code command} is null
		 * @throws IllegalArgumentException if {@code command} is blank or is not a valid Java regular expression
		 */
		public ExitStatus
		{
			CheckSupport.requireRegex(command, "command");
		}

		@Override
		public String kind()
		{
			return "exit_status";
		}

		@Override
		public String describe()
		{
			return "the last command that the trial ran matching the regular expression `" + command +
				"` exited with status " + status;
		}

		@Override
		public List<String> templates()
		{
			return List.of(command);
		}

		@Override
		public Decision decide(CheckEvidence evidence, Map<String, String> textByDimension)
			throws UndecidableCheckException
		{
			List<ToolCall> calls = evidence.toolCalls().orElseThrow(() -> new UndecidableCheckException(
				"The trial's evidence records no tool calls, so an " + kind() + " check cannot be decided"));
			Pattern compiled = Pattern.compile(Placeholders.fillLiterally(command, textByDimension));
			ToolCall last = null;
			for (ToolCall call : calls)
			{
				if (call.command() != null && compiled.matcher(call.command()).find())
					last = call;
			}
			if (last == null)
				return new Decision(false, "the trial ran no command that matches");
			if (last.exitStatus() == null)
			{
				throw new UndecidableCheckException("The harness reported no exit status for `" + last.command() +
					"`, so an " + kind() + " check cannot be decided");
			}
			return new Decision(last.exitStatus() == status, "`" + last.command() + "` exited with status " +
				last.exitStatus());
		}
	}

	/**
	 * The trial called, or never called, a tool.
	 *
	 * @param tool the tool's name, as the harness reports it
	 * @param used {@code true} if the trial must call the tool, {@code false} if it must not
	 */
	record ToolUsed(String tool, boolean used) implements CheckCriterion
	{
		/**
		 * Creates the criterion.
		 *
		 * @param tool the tool's name, as the harness reports it
		 * @param used {@code true} if the trial must call the tool, {@code false} if it must not
		 * @throws NullPointerException if {@code tool} is null
		 * @throws IllegalArgumentException if {@code tool} is blank
		 */
		public ToolUsed
		{
			CheckSupport.requireText(tool, "tool");
		}

		@Override
		public String kind()
		{
			return "tool_used";
		}

		@Override
		public String describe()
		{
			if (used)
				return "the trial calls the tool `" + tool + "`";
			return "the trial never calls the tool `" + tool + "`";
		}

		@Override
		public List<String> templates()
		{
			return List.of(tool);
		}

		@Override
		public Decision decide(CheckEvidence evidence, Map<String, String> textByDimension)
			throws UndecidableCheckException
		{
			List<ToolCall> calls = evidence.toolCalls().orElseThrow(() -> new UndecidableCheckException(
				"The trial's evidence records no tool calls, so a " + kind() + " check cannot be decided"));
			String name = Placeholders.fill(tool, textByDimension);
			long count = calls.stream().filter(call -> call.tool().equals(name)).count();
			return new Decision((count > 0) == used, "the trial called `" + name + "` " + count + " times");
		}
	}
}
