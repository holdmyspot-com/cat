package com.holdmyspot.cat.common.optimizer.contract.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.optimizer.contract.BuiltInTool;
import com.holdmyspot.cat.common.optimizer.contract.BuiltInTool.Decision;
import com.holdmyspot.cat.common.optimizer.contract.TrialRecord;
import com.holdmyspot.cat.common.optimizer.contract.UndecidableToolException;
import com.holdmyspot.cat.common.optimizer.evidence.SessionEvidence.Tool;

import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

/**
 * Tests each built-in tool on both sides of its verdict, the splitting of a {@code tool:} line into arguments, and
 * the placeholders in its arguments.
 */
public final class BuiltInToolTest
{
	/**
	 * The text, when the trial started, of the file that the reading tools examine.
	 */
	private static final String NOTES = "# Notes\n\nalpha\nbeta\n\ngamma\ndelta\n";
	/**
	 * The path of the file that the reading tools examine.
	 */
	private static final String NOTES_PATH = "docs/notes.md";

	/**
	 * Creates the test fixture.
	 */
	public BuiltInToolTest()
	{
	}

	/**
	 * A trial record held in memory.
	 *
	 * @param replies every turn's reply
	 * @param tools every tool call
	 * @param after the text of each file after the trial, by path
	 * @param initial the text of each file when the trial started, by path
	 * @param received every tool result as the trial's agents received it
	 */
	private record Recorded(List<List<String>> replies, List<Tool> tools, Map<String, String> after,
		Map<String, String> initial, List<String> received) implements TrialRecord
	{
		@Override
		public boolean exists(String path)
		{
			return after.containsKey(path);
		}

		@Override
		public Optional<String> content(String path)
		{
			return Optional.ofNullable(after.get(path));
		}

		@Override
		public Optional<String> initial(String path)
		{
			return Optional.ofNullable(initial.get(path));
		}
	}

	/**
	 * Returns a record that holds only replies, one message per turn.
	 *
	 * @param replies the reply to each turn
	 * @return the record
	 */
	private static Recorded replies(String... replies)
	{
		return new Recorded(List.of(replies).stream().map(List::of).toList(), List.of(), Map.of(), Map.of(NOTES_PATH,
			NOTES), List.of());
	}

	/**
	 * Returns a record whose workspace holds files after the trial.
	 *
	 * @param after the text of each file after the trial, by path
	 * @return the record
	 */
	private static Recorded files(Map<String, String> after)
	{
		return new Recorded(List.of(List.of("Done.")), List.of(), after, Map.of(), List.of());
	}

	/**
	 * Returns a record that holds tool calls whose agents received each output whole.
	 *
	 * @param tools the tool calls
	 * @return the record
	 */
	private static Recorded tools(Tool... tools)
	{
		List<String> received = List.of(tools).stream().map(Tool::output).toList();
		return new Recorded(List.of(List.of("Done.")), List.of(tools), Map.of(), Map.of(NOTES_PATH, NOTES), received);
	}

	/**
	 * Returns a command that the main agent ran in the first turn.
	 *
	 * @param command the command
	 * @param status its exit status, or {@code null} if the harness reported none
	 * @param output its output
	 * @return the tool call
	 */
	private static Tool command(String command, Integer status, String output)
	{
		return new Tool(1, "root", "shell", command, true, status, output);
	}

	/**
	 * Returns a call of a tool that runs no command.
	 *
	 * @param agent the agent that called it
	 * @param tool the tool's name
	 * @param output its output
	 * @return the tool call
	 */
	private static Tool call(String agent, String tool, String output)
	{
		return new Tool(1, agent, tool, "{}", false, null, output);
	}

	/**
	 * Returns one {@code tool:} line of each tool, with a record that it passes and a record that it fails.
	 *
	 * @return rows of the tool's {@code tool:} line, its name, the passing record and the failing record
	 */
	@DataProvider(name = "builtInTools")
	public static Object[][] builtInTools()
	{
		return new Object[][]
			{
				{"exact-text Done.", "exact-text", replies("  Done.\n"), replies("Done, mostly.")},
				{"forbidden-text \"As an AI\"", "forbidden-text", replies("Sure.", "Here it is."),
					replies("Sure.", "As an AI, I cannot.")},
				{"pattern (?m)^-\\s\\w+$", "pattern", replies("Colors:\n- red"), replies("red, green")},
				{"count (?m)^- 3 3", "count", replies("- a\n- b\n- c"), replies("- a\n- b")},
				{"file-exists build/report.txt", "file-exists", files(Map.of("build/report.txt", "ok")),
					files(Map.of())},
				{"file-absent build/report.txt", "file-absent", files(Map.of()),
					files(Map.of("build/report.txt", "ok"))},
				{"file-content VERSION 1.2.0", "file-content", files(Map.of("VERSION", "1.2.0\r\n")),
					files(Map.of("VERSION", "1.1.0\n"))},
				{"exit-status \"mvn .*test\" 0", "exit-status",
					tools(command("mvn -q test", 1, ""), command("mvn -q test", 0, "")),
					tools(command("mvn -q test", 0, ""), command("mvn test", 1, ""))},
				{"tool-used Read", "tool-used", tools(call("child-1", "Read", "")), tools(call("root", "Grep", ""))},
				{"tool-unused Bash", "tool-unused", tools(call("root", "Read", "")),
					tools(call("root", "Read", ""), call("child-1", "Bash", ""))},
				{"no-tool-call", "no-tool-call", tools(), tools(call("root", "Read", ""))},
				{"read-in-full " + NOTES_PATH, "read-in-full",
					tools(call("root", "Read", "     1\t# Notes\n     2\t\n     3\talpha\n     4\tbeta\n"),
						command("sed -n 5,7p docs/notes.md", 0, "\ngamma\ndelta\n")),
					tools(call("root", "Read", "     1\t# Notes\n     3\talpha\n     4\tbeta\n     6\tgamma\n"))},
				{"read-at-most " + NOTES_PATH + " 40", "read-at-most",
					tools(command("grep -n beta docs/notes.md", 1, "4:beta\n"),
						command("head -1 docs/notes.md", 0, "# Notes\n")),
					tools(command("grep -n -e beta -e gamma -e delta docs/notes.md", 0, "4:beta\n6:gamma\n7:delta\n"))}
			};
	}

	/**
	 * Verifies that each tool passes the record that satisfies it and fails the one that does not, naming the
	 * behavior that it detects and the value that it observed.
	 *
	 * @param line the tool's {@code tool:} line
	 * @param name the tool's name
	 * @param passing a record that the tool passes
	 * @param failing a record that the tool fails
	 * @throws IOException if a record cannot be read
	 * @throws UndecidableToolException if a record lacks what the tool observes
	 */
	@Test(dataProvider = "builtInTools")
	public void eachCheckDecidesBothSides(String line, String name, TrialRecord passing, TrialRecord failing)
		throws IOException, UndecidableToolException
	{
		// Requirement: R-C9
		BuiltInTool builtInTool = BuiltInTool.parse(line);
		requireThat(builtInTool.name(), "name").isEqualTo(name);
		requireThat(builtInTool.behavior(), "behavior").isNotBlank();

		Decision passed = builtInTool.decide(passing, Map.of());
		requireThat(passed.passed(), "passed.passed()").withContext(passed.observed(), "observed").isTrue();
		Decision failed = builtInTool.decide(failing, Map.of());
		requireThat(failed.passed(), "failed.passed()").withContext(failed.observed(), "observed").isFalse();
		requireThat(failed.observed(), "failed.observed()").isNotBlank();
	}

	/**
	 * Verifies that each tool names a distinct behavior, so that a failure group tells development which tool
	 * failed.
	 */
	@Test
	public void checksNameDistinctBehaviors()
	{
		// Requirement: R-C18
		Set<String> behaviors = new HashSet<>();
		for (Object[] row : builtInTools())
			requireThat(behaviors.add(BuiltInTool.parse((String) row[0]).behavior()), "distinct").isTrue();
	}

	/**
	 * Verifies that arguments split on spaces, that a quoted argument keeps its spaces and unescapes {@code \"} and
	 * {@code \\}, and that an unquoted backslash stays literal.
	 *
	 * @throws IOException if a record cannot be read
	 * @throws UndecidableToolException if a record lacks what the tool observes
	 */
	@Test
	public void argumentsSplitOnSpacesAndQuotes() throws IOException, UndecidableToolException
	{
		// Requirement: R-C9
		requireThat(BuiltInTool.arguments("count   \\d+  1 2"), "unquoted").
			isEqualTo(List.of("count", "\\d+", "1", "2"));
		requireThat(BuiltInTool.arguments("exact-text \"say \\\"hi\\\" \\\\ \\d\" \"\""), "quoted").
			isEqualTo(List.of("exact-text", "say \"hi\" \\ \\d", ""));

		BuiltInTool builtInTool = BuiltInTool.parse("exact-text \"He said \\\"no\\\".\"");
		requireThat(builtInTool.decide(replies("He said \"no\"."), Map.of()).passed(), "quotedText").isTrue();
	}

	/**
	 * Verifies that a malformed {@code tool:} line is refused with the reason.
	 *
	 * @param line the line
	 * @param reason a phrase of the expected refusal
	 */
	@Test(dataProvider = "malformed")
	public void refusesMalformedLines(String line, String reason)
	{
		// Requirement: R-C9
		try
		{
			BuiltInTool.parse(line);
		}
		catch (IllegalArgumentException e)
		{
			requireThat(e.getMessage(), "message").contains(reason);
			return;
		}
		throw new AssertionError("The line was accepted: " + line);
	}

	/**
	 * Returns malformed {@code tool:} lines and a phrase of each refusal.
	 *
	 * @return rows of the line and the phrase
	 */
	@DataProvider(name = "malformed")
	public static Object[][] malformed()
	{
		return new Object[][]
			{
				{"", "names no built-in tool"},
				{"exact_text Done.", "no built-in tool named exact_text"},
				{"exact-text \"Done.", "unterminated"},
				{"exact-text \"a\"b", "closing quote"},
				{"exact-text a\"b", "double quote"},
				{"exact-text Done. Again.", "takes 1 argument"},
				{"no-tool-call Read", "takes 0 arguments"},
				{"count x 3 1", "minimum"},
				{"count x one 2", "whole number"},
				{"pattern (", "regular expression"},
				{"file-exists ../outside", "normalized"},
				{"file-exists turn=1", "turn="},
				{"exact-text turn=0 Done.", "turn"},
				{"read-at-most docs/a.md 101", "percent"}
			};
	}

	/**
	 * Verifies that {@code turn=<n>} limits a reply tool to that turn's reply.
	 *
	 * @throws IOException if a record cannot be read
	 * @throws UndecidableToolException if a record lacks what the tool observes
	 */
	@Test
	public void turnLimitsAReplyCheck() throws IOException, UndecidableToolException
	{
		// Requirement: R-C9
		Recorded record = replies("Working.", "Done.");
		requireThat(BuiltInTool.parse("exact-text turn=2 Done.").decide(record, Map.of()).passed(), "turn2").isTrue();
		requireThat(BuiltInTool.parse("exact-text Done.").decide(record, Map.of()).passed(), "everyTurn").isFalse();
		requireThat(BuiltInTool.parse("exact-text turn=3 Done.").decide(record, Map.of()).passed(), "noReply").
			isFalse();
		requireThat(((BuiltInTool.Count) BuiltInTool.parse("count turn=1 o 1 1")).turn(), "turn").isEqualTo(1);
	}

	/**
	 * Verifies that a reply tool reads every message of a turn, joined in order.
	 *
	 * @throws IOException if a record cannot be read
	 * @throws UndecidableToolException if a record lacks what the tool observes
	 */
	@Test
	public void replyChecksReadEveryMessage() throws IOException, UndecidableToolException
	{
		// Requirement: R-C12
		Recorded record = new Recorded(List.of(List.of("forbidden progress", "Done.")), List.of(), Map.of(),
			Map.of(), List.of());
		requireThat(BuiltInTool.parse("forbidden-text forbidden").decide(record, Map.of()).passed(), "interim").
			isFalse();
		requireThat(BuiltInTool.parse("exact-text \"forbidden progress\nDone.\"").decide(record, Map.of()).passed(),
			"joined").isTrue();
	}

	/**
	 * Verifies that placeholders are replaced after splitting, so that a value with spaces stays one argument, and
	 * that a value in a regular expression matches literally.
	 *
	 * @throws IOException if a record cannot be read
	 * @throws UndecidableToolException if a record lacks what the tool observes
	 */
	@Test
	public void placeholdersFillAfterSplitting() throws IOException, UndecidableToolException
	{
		// Requirement: R-C9
		BuiltInTool exact = BuiltInTool.parse("exact-text {{expected_outcome}}");
		requireThat(exact.placeholders(), "placeholders").isEqualTo(Set.of("expected_outcome"));
		requireThat(exact.decide(replies("released on 2025-03-04"), Map.of("expected_outcome",
			"released on 2025-03-04")).passed(), "spacedValue").isTrue();

		BuiltInTool pattern = BuiltInTool.parse("pattern v{{release}}$");
		requireThat(pattern.decide(replies("v1.2"), Map.of("release", "1.2")).passed(), "literal").isTrue();
		requireThat(pattern.decide(replies("v1x2"), Map.of("release", "1.2")).passed(), "dotIsLiteral").isFalse();

		BuiltInTool file = BuiltInTool.parse("file-exists docs/{{notes}}");
		requireThat(file.decide(files(Map.of("docs/a b.md", "")), Map.of("notes", "a b.md")).passed(), "path").
			isTrue();
		requireThat(BuiltInTool.parse("forbidden-text \"${{ github.token }}\"").placeholders(), "notAPlaceholder").
			isEmpty();
	}

	/**
	 * Verifies that a value that turns a path argument into a path outside the workspace is refused.
	 */
	@Test(expectedExceptions = IllegalArgumentException.class, expectedExceptionsMessageRegExp = ".*normalized.*")
	public void refusesAValueThatLeavesTheWorkspace()
	{
		// Requirement: R-C9
		BuiltInTool.parse("file-exists docs/{{notes}}").validate(Map.of("notes", "../../etc/passwd"));
	}

	/**
	 * Verifies that an exit status that the harness did not report leaves the tool undecided rather than guessed,
	 * and that only {@code exit-status} needs one.
	 *
	 * @throws IOException if a record cannot be read
	 */
	@Test
	public void exitStatusNeedsAReportedStatus() throws IOException
	{
		// Requirement: R-C9
		BuiltInTool builtInTool = BuiltInTool.parse("exit-status make 0");
		requireThat(builtInTool.needsExitStatus(), "needsExitStatus").isTrue();
		requireThat(BuiltInTool.parse("tool-used shell").needsExitStatus(), "toolUsed").isFalse();
		try
		{
			builtInTool.decide(tools(command("make", null, "")), Map.of());
		}
		catch (UndecidableToolException e)
		{
			requireThat(e.getMessage(), "message").contains("make");
			return;
		}
		throw new AssertionError("An unreported exit status was decided");
	}

	/**
	 * Verifies that a reading tool of a file that did not exist when the trial started leaves the tool undecided.
	 *
	 * @throws IOException if a record cannot be read
	 */
	@Test(expectedExceptions = UndecidableToolException.class)
	public void readingAFileAbsentAtStartIsUndecided() throws IOException, UndecidableToolException
	{
		// Requirement: R-C9
		BuiltInTool.parse("read-in-full docs/missing.md").decide(tools(), Map.of());
	}

	/**
	 * Verifies that the reading tools count only the lines that the agent received, so that a file whose whole text
	 * a command printed is not read in full when the harness cut the output before the agent received it.
	 *
	 * @throws IOException if a record cannot be read
	 * @throws UndecidableToolException if a record lacks what the tool observes
	 */
	@Test
	public void readingCountsOnlyReceivedLines() throws IOException, UndecidableToolException
	{
		Tool printed = command("cat docs/notes.md", 0, NOTES);
		Recorded record = new Recorded(List.of(List.of("Done.")), List.of(printed), Map.of(),
			Map.of(NOTES_PATH, NOTES), List.of("# Notes\n\nalpha\n[4 lines cut]"));

		Decision inFull = BuiltInTool.parse("read-in-full " + NOTES_PATH).decide(record, Map.of());
		requireThat(inFull.passed(), "inFull.passed()").withContext(inFull.observed(), "observed").isFalse();
		Decision atMost = BuiltInTool.parse("read-at-most " + NOTES_PATH + " 40").decide(record, Map.of());
		requireThat(atMost.passed(), "atMost.passed()").withContext(atMost.observed(), "observed").isTrue();
	}
}
