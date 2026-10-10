package io.github.cowwoc.cat.common.optimizer.contract.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.optimizer.contract.Assertion;
import io.github.cowwoc.cat.common.optimizer.contract.AssertionKind;
import io.github.cowwoc.cat.common.optimizer.contract.Scenario;
import io.github.cowwoc.cat.common.optimizer.contract.ScenarioFormatException;
import io.github.cowwoc.cat.common.optimizer.contract.VariableDeclaration;

import org.testng.annotations.Test;

import java.util.List;
import java.util.Set;

/**
 * Tests {@link Scenario#parse(String)}, {@link Scenario#render()} and the files that delivery writes from a scenario.
 */
public final class ScenarioTest
{
	/**
	 * A variables table with one variable.
	 */
	private static final String VARIABLES = """
		## Variables

		| Name | Description |
		|---|---|
		| notes | The release-notes file name |
		""";
	/**
	 * A single turn.
	 */
	private static final String TURN = """
		## Scenario

		Summarize `docs/{{notes}}`.
		""";
	/**
	 * One judged assertion.
	 */
	private static final String ASSERTIONS = """
		## Assertions

		### summary_given

		kind: effect

		The reply summarizes the notes.
		""";

	/**
	 * Creates the test fixture.
	 */
	public ScenarioTest()
	{
	}

	/**
	 * Returns the problem that parsing reports.
	 *
	 * @param content a scenario that does not follow the format
	 * @return the exception's message
	 */
	private static String refusal(String content)
	{
		try
		{
			Scenario.parse(content);
		}
		catch (ScenarioFormatException e)
		{
			return e.getMessage();
		}
		throw new AssertionError("The scenario was accepted:\n" + content);
	}

	/**
	 * Verifies that the design's example parses into its variables, its one turn and its assertion with a
	 * {@code tool:} line.
	 *
	 * @throws ScenarioFormatException if the scenario is refused
	 */
	@Test
	public void parsesSingleTurn() throws ScenarioFormatException
	{
		// Requirement: R-C20
		Scenario scenario = Scenario.parse("""
			# Long document read whole

			## Design Goals

			The agent reads a long file whole before it answers.

			## Variables

			| Name | Description |
			|---|---|
			| notes | The release-notes file name, without its directory |
			| release | The release whose publication date the turn asks for |
			| before | Log text placed before the release's entry |

			## Scenario

			When was release {{release}} published? The notes are in `docs/{{notes}}`.

			## Assertions

			### whole_file_read

			kind: effect
			tool: read-in-full docs/{{notes}}

			The agent reads all of `docs/{{notes}}` before it answers.
			""");
		requireThat(scenario.variables(), "variables").isEqualTo(List.of(
			new VariableDeclaration("notes", "The release-notes file name, without its directory"),
			new VariableDeclaration("release", "The release whose publication date the turn asks for"),
			new VariableDeclaration("before", "Log text placed before the release's entry")));
		requireThat(scenario.turns(), "turns").isEqualTo(List.of(
			"When was release {{release}} published? The notes are in `docs/{{notes}}`."));
		requireThat(scenario.assertions(), "assertions").isEqualTo(List.of(new Assertion("whole_file_read",
			AssertionKind.EFFECT, "read-in-full docs/{{notes}}",
			"The agent reads all of `docs/{{notes}}` before it answers.")));
	}

	/**
	 * Verifies that numbered turn subsections become the turns in order, each keeping its inner lines.
	 *
	 * @throws ScenarioFormatException if the scenario is refused
	 */
	@Test
	public void parsesNumberedTurns() throws ScenarioFormatException
	{
		// Requirement: R-C20
		Scenario scenario = Scenario.parse(VARIABLES + """

			## Scenario

			### Turn 1

			Open `docs/{{notes}}`.

			Then wait.

			### Turn 2

			Summarize it.

			""" + ASSERTIONS);
		requireThat(scenario.turns(), "turns").isEqualTo(List.of("Open `docs/{{notes}}`.\n\nThen wait.",
			"Summarize it."));
	}

	/**
	 * Verifies that headings inside a fenced code block belong to the turn rather than starting a section.
	 *
	 * @throws ScenarioFormatException if the scenario is refused
	 */
	@Test
	public void fencedHeadingsStayInTurn() throws ScenarioFormatException
	{
		// Requirement: R-C20
		Scenario scenario = Scenario.parse(VARIABLES + """

			## Scenario

			Fix this file:

			```markdown
			## Assertions
			### Turn 7
			```

			""" + ASSERTIONS);
		requireThat(scenario.turns(), "turns").isEqualTo(List.of(
			"Fix this file:\n\n```markdown\n## Assertions\n### Turn 7\n```"));
	}

	/**
	 * Verifies that an escaped pipe in a table cell becomes a literal pipe.
	 *
	 * @throws ScenarioFormatException if the scenario is refused
	 */
	@Test
	public void unescapesPipe() throws ScenarioFormatException
	{
		// Requirement: R-C20
		Scenario scenario = Scenario.parse("""
			## Variables

			| Name | Description |
			|---|---|
			| notes | Either `a \\| b` or `c` |

			""" + TURN + ASSERTIONS);
		requireThat(scenario.variables(), "variables").isEqualTo(List.of(
			new VariableDeclaration("notes", "Either `a | b` or `c`")));
	}

	/**
	 * Verifies that a guard assertion without a {@code tool:} line is decided by the judge, and that the expected
	 * outcome's placeholder is allowed in it.
	 *
	 * @throws ScenarioFormatException if the scenario is refused
	 */
	@Test
	public void parsesGuardWithoutTool() throws ScenarioFormatException
	{
		// Requirement: R-C20
		Scenario scenario = Scenario.parse(VARIABLES + TURN + """
			## Assertions

			### answer_correct

			kind: guard

			The reply states {{expected_outcome}}.
			""");
		requireThat(scenario.assertions(), "assertions").isEqualTo(List.of(new Assertion("answer_correct",
			AssertionKind.GUARD, null, "The reply states {{expected_outcome}}.")));
	}

	/**
	 * Verifies that text in double braces that is not a variable name stays as it is.
	 *
	 * @throws ScenarioFormatException if the scenario is refused
	 */
	@Test
	public void keepsOtherBraces() throws ScenarioFormatException
	{
		// Requirement: R-C20
		Scenario scenario = Scenario.parse(VARIABLES + """
			## Scenario

			Pass `${{ github.token }}` and `{{Notes}}` to `docs/{{notes}}`.

			""" + ASSERTIONS);
		requireThat(scenario.turns(), "turns").isEqualTo(List.of(
			"Pass `${{ github.token }}` and `{{Notes}}` to `docs/{{notes}}`."));
		requireThat(scenario.namedInText(), "namedInText").isEqualTo(Set.of("notes"));
	}

	/**
	 * Verifies that a scenario without a required section is refused, naming the section.
	 */
	@Test
	public void refusesMissingSection()
	{
		// Requirement: R-C20
		requireThat(refusal(TURN + ASSERTIONS), "withoutVariables").contains("## Variables");
		requireThat(refusal(VARIABLES + ASSERTIONS), "withoutScenario").contains("## Scenario");
		requireThat(refusal(VARIABLES + TURN), "withoutAssertions").contains("## Assertions");
	}

	/**
	 * Verifies that a section that appears twice is refused at its second heading.
	 */
	@Test
	public void refusesDuplicateSection()
	{
		// Requirement: R-C20
		requireThat(refusal(VARIABLES + TURN + TURN + ASSERTIONS), "refusal").startsWith("Line 9:");
	}

	/**
	 * Verifies that a section that the format does not define is refused at its heading.
	 */
	@Test
	public void refusesUnknownSection()
	{
		// Requirement: R-C20
		requireThat(refusal(VARIABLES + "## Notes\n\nText.\n" + TURN + ASSERTIONS), "refusal").
			startsWith("Line 6:").contains("## Notes");
	}

	/**
	 * Verifies that a variables table whose columns are not exactly {@code Name} and {@code Description} is refused.
	 */
	@Test
	public void refusesOtherColumns()
	{
		// Requirement: R-C20
		requireThat(refusal("""
			## Variables

			| Name | Value |
			|---|---|
			| notes | a.md |
			""" + TURN + ASSERTIONS), "renamed").startsWith("Line 3:");
		requireThat(refusal("""
			## Variables

			| Name | Description |
			|---|---|
			| notes | The file | extra |
			""" + TURN + ASSERTIONS), "extraCell").startsWith("Line 5:");
	}

	/**
	 * Verifies that text after the variables table is refused, so that a table split by a blank line is not read in
	 * part.
	 */
	@Test
	public void refusesTextAfterTable()
	{
		// Requirement: R-C20
		requireThat(refusal(VARIABLES + "\n| release | The release |\n" + TURN + ASSERTIONS), "refusal").
			startsWith("Line 7:");
	}

	/**
	 * Verifies that a prompt test with no variable is refused, because it has no test cases to vary.
	 */
	@Test
	public void refusesNoVariable()
	{
		// Requirement: R-C20
		requireThat(refusal("""
			## Variables

			| Name | Description |
			|---|---|

			## Scenario

			Summarize the notes.

			""" + ASSERTIONS), "refusal").contains("no variable");
	}

	/**
	 * Verifies that a variable name that is not snake_case, a repeated variable and the reserved name are refused at
	 * their rows.
	 */
	@Test
	public void refusesInvalidVariableNames()
	{
		// Requirement: R-C20
		requireThat(refusal("""
			## Variables

			| Name | Description |
			|---|---|
			| releaseNotes | The notes |
			""" + TURN + ASSERTIONS), "camelCase").startsWith("Line 5:");
		requireThat(refusal(VARIABLES + """
			| notes | The notes again |
			""" + TURN + ASSERTIONS), "duplicate").startsWith("Line 6:");
		requireThat(refusal(VARIABLES + """
			| expected_outcome | The answer |
			""" + TURN + ASSERTIONS), "reserved").startsWith("Line 6:").contains("expected_outcome");
	}

	/**
	 * Verifies that turn subsections that are not numbered from 1 in order, and text before the first of them, are
	 * refused.
	 */
	@Test
	public void refusesMisnumberedTurns()
	{
		// Requirement: R-C20
		requireThat(refusal(VARIABLES + """
			## Scenario

			### Turn 2

			Summarize `docs/{{notes}}`.

			""" + ASSERTIONS), "startsAtTwo").startsWith("Line 8:");
		requireThat(refusal(VARIABLES + """
			## Scenario

			### Step 1

			Summarize `docs/{{notes}}`.

			""" + ASSERTIONS), "otherHeading").startsWith("Line 8:");
		requireThat(refusal(VARIABLES + """
			## Scenario

			Context first.

			### Turn 1

			Summarize `docs/{{notes}}`.

			""" + ASSERTIONS), "textBeforeTurn").startsWith("Line 8:");
	}

	/**
	 * Verifies that an empty turn is refused.
	 */
	@Test
	public void refusesBlankTurn()
	{
		// Requirement: R-C20
		requireThat(refusal(VARIABLES + """
			## Scenario

			### Turn 1

			""" + ASSERTIONS), "refusal").startsWith("Line 8:");
	}

	/**
	 * Verifies that an assertion without a kind, with an unknown kind, with an unknown key line, without a statement
	 * or with a {@code tool:} line that names no built-in tool or gives it unsuitable arguments is refused at the line
	 * concerned.
	 */
	@Test
	public void refusesMalformedAssertions()
	{
		// Requirement: R-C20
		String heading = VARIABLES + TURN + "## Assertions\n\n### summary_given\n\n";
		requireThat(refusal(heading + "The reply summarizes the notes.\n"), "noKind").startsWith("Line 13:");
		requireThat(refusal(heading + "kind: maybe\n\nThe reply summarizes the notes.\n"), "unknownKind").
			startsWith("Line 13:");
		requireThat(refusal(heading + "kind: effect\ntimeout: 5\n\nThe reply summarizes the notes.\n"),
			"unknownKey").startsWith("Line 14:").contains("timeout");
		requireThat(refusal(heading + "kind: effect\nThe reply summarizes the notes.\n"), "statementWithoutBlank").
			startsWith("Line 14:");
		requireThat(refusal(heading + "kind: effect\n"), "noStatement").startsWith("Line 11:");
		requireThat(refusal(heading + "kind: effect\ntool:\n\nThe reply summarizes the notes.\n"), "blankTool").
			startsWith("Line 14:");
		requireThat(refusal(heading + "kind: effect\ntool: spell-check\n\nThe reply summarizes the notes.\n"),
			"unknownCheck").startsWith("Line 14:").contains("spell-check");
		requireThat(refusal(heading + "kind: effect\ntool: count \"^- \" 3 1\n\nThe reply summarizes the notes.\n"),
			"unsuitedArguments").startsWith("Line 14:").contains("exceeds");
	}

	/**
	 * Verifies that an assertion identifier that is not snake_case, a repeated one and the reserved one are refused at
	 * their headings.
	 */
	@Test
	public void refusesInvalidAssertionIds()
	{
		// Requirement: R-C20
		String body = "kind: effect\n\nThe reply summarizes the notes.\n\n";
		String start = VARIABLES + TURN + "## Assertions\n\n";
		requireThat(refusal(start + "### Summary Given\n\n" + body), "notSnakeCase").startsWith("Line 11:");
		requireThat(refusal(start + "### summary_given\n\n" + body + "### summary_given\n\n" + body),
			"duplicate").startsWith("Line 17:");
		requireThat(refusal(start + "### expected_outcome\n\n" + body), "reserved").startsWith("Line 11:").
			contains("expected_outcome");
	}

	/**
	 * Verifies that text before the first assertion and an empty assertions section are refused.
	 */
	@Test
	public void refusesAssertionsWithoutSubsections()
	{
		// Requirement: R-C20
		requireThat(refusal(VARIABLES + TURN + "## Assertions\n\nThe reply is short.\n"), "prose").
			startsWith("Line 11:");
		requireThat(refusal(VARIABLES + TURN + "## Assertions\n"), "empty").contains("no assertion");
	}

	/**
	 * Verifies that a placeholder for an undeclared variable in a turn, an assertion statement or a {@code tool:}
	 * line is refused at its line.
	 */
	@Test
	public void refusesUndeclaredPlaceholder()
	{
		// Requirement: R-C20
		requireThat(refusal(VARIABLES + "## Scenario\n\nSummarize `{{note}}`.\n\n" + ASSERTIONS), "turn").
			startsWith("Line 8:").contains("{{note}}");
		requireThat(refusal(VARIABLES + TURN + """
			## Assertions

			### summary_given

			kind: effect
			tool: read-in-full docs/{{file}}

			The reply summarizes the notes.
			"""), "tool").startsWith("Line 14:").contains("{{file}}");
		requireThat(refusal(VARIABLES + TURN + """
			## Assertions

			### summary_given

			kind: effect

			The reply summarizes {{release}}.
			"""), "statement").startsWith("Line 15:").contains("{{release}}");
	}

	/**
	 * Verifies that a rendered scenario parses back into the same variables, turns and assertions, including a
	 * description holding a pipe, numbered turns and a {@code tool:} line.
	 *
	 * @throws ScenarioFormatException if the rendered scenario is refused
	 */
	@Test
	public void renderedScenarioParsesBack() throws ScenarioFormatException
	{
		// Requirement: R-W11
		Scenario scenario = new Scenario(
			List.of(new VariableDeclaration("notes", "The notes file | without its directory"),
				new VariableDeclaration("release", "The release to look up")),
			List.of("Open `docs/{{notes}}`.", "When was {{release}} published?\n\nAnswer with a date."),
			List.of(new Assertion("whole_file_read", AssertionKind.EFFECT, "read-in-full docs/{{notes}}",
					"The agent reads all of `docs/{{notes}}`."),
				new Assertion("no_edit", AssertionKind.GUARD, null, "The agent changes no file.")));
		requireThat(Scenario.parse(scenario.render()), "parsed").isEqualTo(scenario);

		Scenario single = new Scenario(List.of(new VariableDeclaration("notes", "The release-notes file name")),
			List.of("Summarize `docs/{{notes}}`."),
			List.of(new Assertion("summary_given", AssertionKind.EFFECT, null, "The reply summarizes the notes.")));
		requireThat(single.render(), "single").isEqualTo(VARIABLES + "\n" + TURN + "\n" + ASSERTIONS);
	}

	/**
	 * Verifies that a created scenario file starts with its title and design goals, followed by the rendered sections,
	 * and that design goals which would read back as a section are left out.
	 *
	 * @throws ScenarioFormatException if the written file is refused
	 */
	@Test
	public void createdFileHoldsTitleAndDesignGoals() throws ScenarioFormatException
	{
		// Requirement: R-W11
		Scenario scenario = single();
		String file = scenario.renderFile("summary_given", "The agent summarizes the notes.");
		requireThat(file, "file").isEqualTo("""
			# summary_given

			## Design Goals

			The agent summarizes the notes.

			""" + VARIABLES + "\n" + TURN + "\n" + ASSERTIONS);
		requireThat(Scenario.parse(file), "parsed").isEqualTo(scenario);

		String withoutGoals = scenario.renderFile("summary_given", "Summarizes.\n## Variables\nNone.");
		requireThat(withoutGoals, "withoutGoals").isEqualTo("# summary_given\n\n" + VARIABLES + "\n" + TURN + "\n" +
			ASSERTIONS);
	}

	/**
	 * Verifies that rewriting a recorded scenario file keeps its title, the text before its first section and its
	 * design goals, wherever they lie, and replaces every other section with the rendered scenario.
	 *
	 * @throws ScenarioFormatException if the recorded file or the written file is refused
	 */
	@Test
	public void rewrittenFileKeepsTitleAndDesignGoals() throws ScenarioFormatException
	{
		// Requirement: R-W11
		String recorded = """
			# Release notes

			Written for the 2.0 release.

			## Variables

			| Name | Description |
			|---|---|
			| file | The notes |

			## Design Goals

			The agent summarizes the notes.
			```
			## Scenario
			```

			## Scenario

			Summarize `{{file}}`.

			## Assertions

			### summary_given

			kind: effect

			The reply summarizes the notes.
			""";
		Scenario scenario = single();
		String file = scenario.rewrite(recorded);
		requireThat(file, "file").isEqualTo("""
			# Release notes

			Written for the 2.0 release.

			## Design Goals

			The agent summarizes the notes.
			```
			## Scenario
			```

			""" + VARIABLES + "\n" + TURN + "\n" + ASSERTIONS);
		requireThat(Scenario.parse(file), "parsed").isEqualTo(scenario);
	}

	/**
	 * Returns a scenario with one variable, one turn and one judged assertion, which renders as {@link #VARIABLES},
	 * {@link #TURN} and {@link #ASSERTIONS}.
	 *
	 * @return the scenario
	 */
	private static Scenario single()
	{
		return new Scenario(List.of(new VariableDeclaration("notes", "The release-notes file name")),
			List.of("Summarize `docs/{{notes}}`."),
			List.of(new Assertion("summary_given", AssertionKind.EFFECT, null, "The reply summarizes the notes.")));
	}

	/**
	 * Verifies that a scenario whose text would read back differently, such as a turn holding a section heading, is
	 * refused rather than written.
	 */
	@Test(expectedExceptions = IllegalArgumentException.class, expectedExceptionsMessageRegExp = ".*## Notes.*")
	public void refusesTextThatReadsBackDifferently()
	{
		// Requirement: R-W11
		new Scenario(List.of(new VariableDeclaration("notes", "The notes file")),
			List.of("Summarize `docs/{{notes}}`.\n## Notes\nKeep it short."),
			List.of(new Assertion("summary_given", AssertionKind.EFFECT, null, "The reply summarizes the notes."))).
			render();
	}
}
