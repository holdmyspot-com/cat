package com.holdmyspot.cat.common.optimizer.contract.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.optimizer.contract.Assertion;
import com.holdmyspot.cat.common.optimizer.contract.AssertionKind;
import com.holdmyspot.cat.common.optimizer.contract.ExpectedBehavior;
import com.holdmyspot.cat.common.optimizer.contract.OnToolFailure;
import com.holdmyspot.cat.common.optimizer.contract.Scenario;
import com.holdmyspot.cat.common.optimizer.contract.ScenarioFormatException;
import com.holdmyspot.cat.common.optimizer.contract.VariableDeclaration;

import org.testng.annotations.Test;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Tests {@link Scenario#parse(String)}, {@link Scenario#render()} and the files that delivery writes from a scenario.
 */
public final class ScenarioTest
{
	/**
	 * A variables section with one variable.
	 */
	private static final String VARIABLES = """
		## Variables

		- **notes**: The release-notes file name
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

		- **kind**: effect
		- **description**: The reply summarizes the notes.
		""";
	/**
	 * An expected behavior with its description and time limit.
	 */
	private static final String EXPECTED_BEHAVIOR = """
		## Expected Behavior

		- **description**: The agent summarizes the notes.
		- **time_limit_seconds**: 120
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
	 * Verifies that a single-turn scenario parses into its variables, its one turn and its assertion with a
	 * {@code tool}.
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

			- **notes**: The release-notes file name, without its directory
			- **release**: The release whose publication date the turn asks for
			- **before**: Log text placed before the release's entry

			## Scenario

			When was release {{release}} published? The notes are in `docs/{{notes}}`.

			## Assertions

			### whole_file_read

			- **kind**: effect
			- **tool**: `read-in-full docs/{{notes}}`
			- **description**: The agent reads all of `docs/{{notes}}` before it answers.
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
	 * Verifies that numbered turn subsections become the turns in order, each keeping its inner lines, whether a turn
	 * is prose or a {@code description}.
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

			- **description**: Open `docs/{{notes}}`.

			  Then wait.

			### Turn 2

			Summarize it.

			""" + ASSERTIONS);
		requireThat(scenario.turns(), "turns").isEqualTo(List.of("Open `docs/{{notes}}`.\n\nThen wait.",
			"Summarize it."));
	}

	/**
	 * Verifies that a {@code ## Scenario} section of key-value pairs reads as the prose that its {@code description}
	 * holds.
	 *
	 * @throws ScenarioFormatException if the scenario is refused
	 */
	@Test
	public void parsesScenarioDescription() throws ScenarioFormatException
	{
		// Requirement: R-C20
		Scenario prose = Scenario.parse(VARIABLES + TURN + ASSERTIONS);
		Scenario pairs = Scenario.parse(VARIABLES + """
			## Scenario

			- **description**: Summarize `docs/{{notes}}`.
			""" + ASSERTIONS);
		requireThat(pairs, "pairs").isEqualTo(prose);
	}

	/**
	 * Verifies that the {@code ## Expected Behavior} section is read into the scenario's expected behavior and time
	 * limit.
	 *
	 * @throws ScenarioFormatException if the scenario is refused
	 */
	@Test
	public void parsesExpectedBehavior() throws ScenarioFormatException
	{
		// Requirement: R-C20
		Scenario scenario = Scenario.parse(VARIABLES + TURN + EXPECTED_BEHAVIOR + ASSERTIONS);
		requireThat(scenario.expectedBehavior(), "expectedBehavior").isEqualTo(Optional.of(
			new ExpectedBehavior("The agent summarizes the notes.", Duration.ofSeconds(120))));
		requireThat(scenario.turns(), "turns").isEqualTo(List.of("Summarize `docs/{{notes}}`."));
	}

	/**
	 * Verifies that a scenario without a {@code ## Expected Behavior} section, as recorded before the section existed,
	 * is still read, without an expected behavior.
	 *
	 * @throws ScenarioFormatException if the scenario is refused
	 */
	@Test
	public void readsScenarioWithoutExpectedBehavior() throws ScenarioFormatException
	{
		// Requirement: R-C20
		Scenario scenario = Scenario.parse(VARIABLES + TURN + ASSERTIONS);
		requireThat(scenario.expectedBehavior(), "expectedBehavior").isEmpty();
		requireThat(scenario, "scenario").isEqualTo(single());
	}

	/**
	 * Verifies that a value continues on the lines indented under its item, across paragraphs, and may start on the
	 * line after its key.
	 *
	 * @throws ScenarioFormatException if the scenario is refused
	 */
	@Test
	public void parsesMultiLineValues() throws ScenarioFormatException
	{
		// Requirement: R-C20
		Scenario scenario = Scenario.parse("""
			## Variables

			- **notes**: The release-notes file name,
			  without its directory
			- **release**:
			  The release to look up

			""" + TURN + """

			## Assertions

			### summary_given

			- **kind**: effect
			- **description**: The reply summarizes the notes.

			  It names every release.
			""");
		requireThat(scenario.variables(), "variables").isEqualTo(List.of(
			new VariableDeclaration("notes", "The release-notes file name,\nwithout its directory"),
			new VariableDeclaration("release", "The release to look up")));
		requireThat(scenario.assertions().getFirst().statement(), "statement").
			isEqualTo("The reply summarizes the notes.\n\nIt names every release.");
	}

	/**
	 * Verifies that a value also continues on the lines indented under its item with a tab.
	 *
	 * @throws ScenarioFormatException if the scenario is refused
	 */
	@Test
	public void parsesTabIndentedValues() throws ScenarioFormatException
	{
		// Requirement: R-C20
		Scenario scenario = Scenario.parse("""
			## Variables

			- **notes**: The release-notes file name,
			\twithout its directory

			""" + TURN + """

			## Assertions

			### summary_given

			- **kind**: effect
			- **description**: The reply summarizes the notes.

			\tIt names every release.
			""");
		requireThat(scenario.variables(), "variables").isEqualTo(List.of(
			new VariableDeclaration("notes", "The release-notes file name,\nwithout its directory")));
		requireThat(scenario.assertions().getFirst().statement(), "statement").
			isEqualTo("The reply summarizes the notes.\n\nIt names every release.");
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
			- **kind**: effect
			```

			""" + ASSERTIONS);
		requireThat(scenario.turns(), "turns").isEqualTo(List.of(
			"Fix this file:\n\n```markdown\n## Assertions\n### Turn 7\n- **kind**: effect\n```"));
	}

	/**
	 * Verifies that a guard assertion without a {@code tool} is decided by the judge, and that the expected
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

			- **kind**: guard
			- **description**: The reply states {{expected_outcome}}.
			""");
		requireThat(scenario.assertions(), "assertions").isEqualTo(List.of(new Assertion("answer_correct",
			AssertionKind.GUARD, null, "The reply states {{expected_outcome}}.")));
	}

	/**
	 * Verifies that an assertion can leave a failure of its built-in tool to the judge, that {@code fail} is the
	 * default, and that a rendered scenario writes {@code on_tool_failure} only when it is {@code judge}.
	 *
	 * @throws ScenarioFormatException if the scenario is refused
	 */
	@Test
	public void parsesToolFailureLeftToJudge() throws ScenarioFormatException
	{
		// Requirement: R-C20
		Scenario scenario = Scenario.parse(VARIABLES + TURN + """
			## Assertions

			### component_named

			- **kind**: effect
			- **tool**: `pattern "(?i)session store"`
			- **on_tool_failure**: judge
			- **description**: The reply names the session store in any wording.

			### notes_read

			- **kind**: effect
			- **tool**: `read-in-full docs/{{notes}}`
			- **on_tool_failure**: fail
			- **description**: The agent reads all of `docs/{{notes}}`.
			""");
		requireThat(scenario.assertions(), "assertions").isEqualTo(List.of(
			new Assertion("component_named", AssertionKind.EFFECT, "pattern \"(?i)session store\"",
				OnToolFailure.JUDGE, "The reply names the session store in any wording."),
			new Assertion("notes_read", AssertionKind.EFFECT, "read-in-full docs/{{notes}}", OnToolFailure.FAIL,
				"The agent reads all of `docs/{{notes}}`.")));
		String rendered = scenario.render();
		requireThat(Scenario.parse(rendered), "parsed").isEqualTo(scenario);
		requireThat(rendered, "rendered").contains("- **on_tool_failure**: judge\n");
		requireThat(rendered.indexOf("on_tool_failure"), "first").isEqualTo(rendered.lastIndexOf("on_tool_failure"));
	}

	/**
	 * Verifies that {@code on_tool_failure} without a {@code tool}, or with a value other than {@code fail} or
	 * {@code judge}, is refused at its line.
	 */
	@Test
	public void refusesInvalidToolFailure()
	{
		// Requirement: R-C20
		String heading = VARIABLES + TURN + "## Assertions\n\n### summary_given\n\n- **kind**: effect\n";
		String description = "- **description**: The reply summarizes the notes.\n";
		requireThat(refusal(heading + "- **on_tool_failure**: judge\n" + description), "noTool").
			startsWith("Line 12:").contains("tool");
		requireThat(refusal(heading + "- **tool**: `pattern notes`\n- **on_tool_failure**: retry\n" + description),
			"unknownValue").startsWith("Line 13:").contains("retry");
	}

	/**
	 * Verifies that a {@code tool} holding backticks is read from a code span with a longer delimiter.
	 *
	 * @throws ScenarioFormatException if the scenario is refused
	 */
	@Test
	public void parsesToolHoldingBackticks() throws ScenarioFormatException
	{
		// Requirement: R-C20
		Scenario scenario = Scenario.parse(VARIABLES + TURN + """
			## Assertions

			### command_quoted

			- **kind**: effect
			- **tool**: ``exact-text "Run `make`."``
			- **description**: The reply quotes the command.
			""");
		requireThat(scenario.assertions().getFirst().tool(), "tool").isEqualTo("exact-text \"Run `make`.\"");
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
		requireThat(refusal(VARIABLES + TURN + TURN + ASSERTIONS), "refusal").startsWith("Line 7:");
	}

	/**
	 * Verifies that a section that the format does not define is refused at its heading.
	 */
	@Test
	public void refusesUnknownSection()
	{
		// Requirement: R-C20
		requireThat(refusal(VARIABLES + "## Notes\n\nText.\n" + TURN + ASSERTIONS), "refusal").
			startsWith("Line 4:").contains("## Notes");
	}

	/**
	 * Verifies that prose in a section of key-value pairs is refused at its line, including an assertion written with
	 * bare key lines.
	 */
	@Test
	public void refusesProseInKeyValueSection()
	{
		// Requirement: R-C20
		requireThat(refusal("## Variables\n\nThe notes file name.\n" + TURN + ASSERTIONS), "variablesProse").
			startsWith("Line 3:").contains("key-value");
		requireThat(refusal(VARIABLES + "The notes file name.\n" + TURN + ASSERTIONS), "afterPair").
			startsWith("Line 4:");
		requireThat(refusal(VARIABLES + TURN + """
			## Assertions

			### summary_given

			kind: effect

			The reply summarizes the notes.
			"""), "keyLines").startsWith("Line 11:");
	}

	/**
	 * Verifies that a {@code ## Scenario} section or turn that mixes prose with key-value pairs is refused at the
	 * line where the second form starts.
	 */
	@Test
	public void refusesMixedScenario()
	{
		// Requirement: R-C20
		requireThat(refusal(VARIABLES + """
			## Scenario

			Summarize `docs/{{notes}}`.
			- **description**: Twice.
			""" + ASSERTIONS), "pairAfterProse").startsWith("Line 7:");
		requireThat(refusal(VARIABLES + """
			## Scenario

			- **description**: Summarize `docs/{{notes}}`.
			Then stop.
			""" + ASSERTIONS), "proseAfterPair").startsWith("Line 7:");
		requireThat(refusal(VARIABLES + """
			## Scenario

			### Turn 1

			Open it.

			- **description**: Twice.
			""" + ASSERTIONS), "turn").startsWith("Line 10:");
	}

	/**
	 * Verifies that a key that its section does not define is refused at its line, naming the key.
	 */
	@Test
	public void refusesUndefinedKey()
	{
		// Requirement: R-C20
		requireThat(refusal(VARIABLES + "## Scenario\n\n- **turn**: Summarize `docs/{{notes}}`.\n" + ASSERTIONS),
			"scenario").startsWith("Line 6:").contains("turn");
		requireThat(refusal(VARIABLES + TURN + """
			## Assertions

			### summary_given

			- **kind**: effect
			- **timeout**: 5
			- **description**: The reply summarizes the notes.
			"""), "assertion").startsWith("Line 12:").contains("timeout");
	}

	/**
	 * Verifies that a {@code ## Scenario} section of key-value pairs whose {@code description} has no value is refused.
	 */
	@Test
	public void refusesEmptyDescription()
	{
		// Requirement: R-C20
		requireThat(refusal(VARIABLES + "## Scenario\n\n- **description**:\n" + ASSERTIONS), "refusal").
			startsWith("Line 6:").contains("description");
	}

	/**
	 * Verifies that a prompt test with no variable is refused, because it has no test cases to vary.
	 */
	@Test
	public void refusesNoVariable()
	{
		// Requirement: R-C20
		requireThat(refusal("## Variables\n\n## Scenario\n\nSummarize the notes.\n\n" + ASSERTIONS), "refusal").
			contains("no variable");
	}

	/**
	 * Verifies that a variable name that is not snake_case, a repeated variable, the reserved name and a variable
	 * without a description are refused at their items.
	 */
	@Test
	public void refusesInvalidVariables()
	{
		// Requirement: R-C20
		requireThat(refusal("## Variables\n\n- **releaseNotes**: The notes\n" + TURN + ASSERTIONS), "camelCase").
			startsWith("Line 3:");
		requireThat(refusal(VARIABLES + "- **notes**: The notes again\n" + TURN + ASSERTIONS), "duplicate").
			startsWith("Line 4:");
		requireThat(refusal(VARIABLES + "- **expected_outcome**: The answer\n" + TURN + ASSERTIONS), "reserved").
			startsWith("Line 4:").contains("expected_outcome");
		requireThat(refusal(VARIABLES + "- **release**:\n" + TURN + ASSERTIONS), "noDescription").
			startsWith("Line 4:").contains("release");
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

			""" + ASSERTIONS), "startsAtTwo").startsWith("Line 6:");
		requireThat(refusal(VARIABLES + """
			## Scenario

			### Step 1

			Summarize `docs/{{notes}}`.

			""" + ASSERTIONS), "otherHeading").startsWith("Line 6:");
		requireThat(refusal(VARIABLES + """
			## Scenario

			Context first.

			### Turn 1

			Summarize `docs/{{notes}}`.

			""" + ASSERTIONS), "textBeforeTurn").startsWith("Line 6:");
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

			""" + ASSERTIONS), "refusal").startsWith("Line 6:");
	}

	/**
	 * Verifies that an assertion without a kind, with an unknown kind, without a description, with a repeated key, or
	 * with a {@code tool} that is not one code span, names no built-in tool or gives it unsuitable arguments is
	 * refused at the line concerned.
	 */
	@Test
	public void refusesMalformedAssertions()
	{
		// Requirement: R-C20
		String heading = VARIABLES + TURN + "## Assertions\n\n### summary_given\n\n";
		String description = "- **description**: The reply summarizes the notes.\n";
		requireThat(refusal(heading + description), "noKind").startsWith("Line 9:").contains("kind");
		requireThat(refusal(heading + "- **kind**: maybe\n" + description), "unknownKind").startsWith("Line 11:");
		requireThat(refusal(heading + "- **kind**: effect\n"), "noDescription").startsWith("Line 9:").
			contains("description");
		requireThat(refusal(heading + "- **kind**: effect\n- **kind**: guard\n" + description), "repeatedKey").
			startsWith("Line 12:").contains("kind");
		requireThat(refusal(heading + "- **kind**: effect\n- **tool**:\n" + description), "blankTool").
			startsWith("Line 12:");
		requireThat(refusal(heading + "- **kind**: effect\n- **tool**: read-in-full docs/{{notes}}\n" + description),
			"toolOutsideCodeSpan").startsWith("Line 12:").contains("code span");
		requireThat(refusal(heading + "- **kind**: effect\n- **tool**: `tool-used Read`, `tool-used Grep`\n" +
			description), "twoCodeSpans").startsWith("Line 12:").contains("one code span");
		requireThat(refusal(heading + "- **kind**: effect\n- **tool**: `tool-used Read` twice\n" + description),
			"textAfterCodeSpan").startsWith("Line 12:").contains("one code span");
		requireThat(refusal(heading + "- **kind**: effect\n- **tool**: `tool-used Read\n" + description),
			"unclosedCodeSpan").startsWith("Line 12:").contains("one code span");
		requireThat(refusal(heading + "- **kind**: effect\n- **tool**: `spell-check`\n" + description),
			"unknownTool").startsWith("Line 12:").contains("spell-check");
		requireThat(refusal(heading + "- **kind**: effect\n- **tool**: `count \"^- \" 3 1`\n" + description),
			"unsuitedArguments").startsWith("Line 12:").contains("exceeds");
	}

	/**
	 * Verifies that an {@code ## Expected Behavior} section without its description or time limit, with prose, with a
	 * key that the section does not define, with a time limit that is not a positive whole number of seconds, or with
	 * a placeholder for an undeclared variable is refused at the line concerned.
	 */
	@Test
	public void refusesMalformedExpectedBehavior()
	{
		// Requirement: R-C20
		String heading = VARIABLES + TURN + "## Expected Behavior\n\n";
		String description = "- **description**: The agent summarizes the notes.\n";
		String limit = "- **time_limit_seconds**: 120\n";
		requireThat(refusal(heading + description + ASSERTIONS), "noTimeLimit").startsWith("Line 7:").
			contains("time_limit_seconds");
		requireThat(refusal(heading + limit + ASSERTIONS), "noDescription").startsWith("Line 7:").
			contains("description");
		requireThat(refusal(heading + "The agent summarizes the notes.\n" + ASSERTIONS), "prose").
			startsWith("Line 9:");
		requireThat(refusal(heading + description + limit + "- **timeout**: 60\n" + ASSERTIONS), "unknownKey").
			startsWith("Line 11:").contains("timeout");
		requireThat(refusal(heading + description + "- **time_limit_seconds**: two minutes\n" + ASSERTIONS),
			"notNumber").startsWith("Line 10:").contains("time_limit_seconds");
		requireThat(refusal(heading + description + "- **time_limit_seconds**: 0\n" + ASSERTIONS), "zero").
			startsWith("Line 10:");
		requireThat(refusal(heading + description + "- **time_limit_seconds**: 1.5\n" + ASSERTIONS), "fraction").
			startsWith("Line 10:");
		requireThat(refusal(heading + "- **description**:\n" + limit + ASSERTIONS), "emptyDescription").
			startsWith("Line 9:");
		requireThat(refusal(heading + "- **description**: The agent reads {{file}}.\n" + limit + ASSERTIONS),
			"undeclared").startsWith("Line 9:").contains("{{file}}");
	}

	/**
	 * Verifies that an assertion identifier that is not snake_case, a repeated one and the reserved one are refused at
	 * their headings.
	 */
	@Test
	public void refusesInvalidAssertionIds()
	{
		// Requirement: R-C20
		String body = "- **kind**: effect\n- **description**: The reply summarizes the notes.\n\n";
		String start = VARIABLES + TURN + "## Assertions\n\n";
		requireThat(refusal(start + "### Summary Given\n\n" + body), "notSnakeCase").startsWith("Line 9:");
		requireThat(refusal(start + "### summary_given\n\n" + body + "### summary_given\n\n" + body),
			"duplicate").startsWith("Line 14:");
		requireThat(refusal(start + "### expected_outcome\n\n" + body), "reserved").startsWith("Line 9:").
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
			startsWith("Line 9:");
		requireThat(refusal(VARIABLES + TURN + "## Assertions\n"), "empty").contains("no assertion");
	}

	/**
	 * Verifies that a placeholder for an undeclared variable in a turn, an assertion's description or its
	 * {@code tool} is refused at its line.
	 */
	@Test
	public void refusesUndeclaredPlaceholder()
	{
		// Requirement: R-C20
		requireThat(refusal(VARIABLES + "## Scenario\n\nSummarize `{{note}}`.\n\n" + ASSERTIONS), "turn").
			startsWith("Line 6:").contains("{{note}}");
		requireThat(refusal(VARIABLES + TURN + """
			## Assertions

			### summary_given

			- **kind**: effect
			- **tool**: `read-in-full docs/{{file}}`
			- **description**: The reply summarizes the notes.
			"""), "tool").startsWith("Line 12:").contains("{{file}}");
		requireThat(refusal(VARIABLES + TURN + """
			## Assertions

			### summary_given

			- **kind**: effect
			- **description**: The reply summarizes {{release}}.
			"""), "statement").startsWith("Line 12:").contains("{{release}}");
	}

	/**
	 * Verifies that a rendered scenario parses back into the same variables, turns and assertions, including a
	 * description that spans several lines, numbered turns, a turn that holds a heading or a key-value line, and a
	 * {@code tool} that holds backticks.
	 *
	 * @throws ScenarioFormatException if the rendered scenario is refused
	 */
	@Test
	public void renderedScenarioParsesBack() throws ScenarioFormatException
	{
		// Requirement: R-W11
		Scenario scenario = new Scenario(
			List.of(new VariableDeclaration("notes", "The notes file | without\nits directory"),
				new VariableDeclaration("release", "The release to look up")),
			List.of("Open `docs/{{notes}}`.", "When was {{release}} published?\n\nAnswer with a date.",
				"Fix this:\n## Notes\n- **kind**: effect"),
			List.of(new Assertion("whole_file_read", AssertionKind.EFFECT, "read-in-full docs/{{notes}}",
					"The agent reads all of `docs/{{notes}}`.\n\nIt reads nothing else."),
				new Assertion("command_quoted", AssertionKind.EFFECT, "exact-text \"Run `make`.\"",
					"The reply quotes the command."),
				new Assertion("no_edit", AssertionKind.GUARD, null, "The agent changes no file.")));
		requireThat(Scenario.parse(scenario.render()), "parsed").isEqualTo(scenario);

		requireThat(single().render(), "single").isEqualTo(VARIABLES + "\n" + TURN + "\n" + ASSERTIONS);

		Scenario heading = new Scenario(single().variables(), List.of("Fix `docs/{{notes}}`:\n## Notes"),
			single().assertions());
		requireThat(heading.render(), "heading").isEqualTo(VARIABLES + """

			## Scenario

			- **description**: Fix `docs/{{notes}}`:
			\t## Notes

			""" + ASSERTIONS);
	}

	/**
	 * Verifies that a scenario with an expected behavior renders its {@code ## Expected Behavior} section between
	 * {@code ## Scenario} and {@code ## Assertions}, and parses back.
	 *
	 * @throws ScenarioFormatException if the rendered scenario is refused
	 */
	@Test
	public void rendersExpectedBehavior() throws ScenarioFormatException
	{
		// Requirement: R-C20
		Scenario scenario = new Scenario(single().variables(), single().turns(), Optional.of(
			new ExpectedBehavior("The agent summarizes the notes.", Duration.ofSeconds(120))), single().assertions());
		String rendered = scenario.render();
		requireThat(rendered, "rendered").isEqualTo(VARIABLES + "\n" + TURN + "\n" + EXPECTED_BEHAVIOR + "\n" +
			ASSERTIONS);
		requireThat(Scenario.parse(rendered), "parsed").isEqualTo(scenario);
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
	public void rewriteKeepsPreambleAndGoals() throws ScenarioFormatException
	{
		// Requirement: R-W11
		String recorded = """
			# Release notes

			Written for the 2.0 release.

			## Variables

			- **file**: The notes

			## Design Goals

			The agent summarizes the notes.
			```
			## Scenario
			```

			## Scenario

			Summarize `{{file}}`.

			## Assertions

			### summary_given

			- **kind**: effect
			- **description**: The reply summarizes the notes.
			""";
		String head = """
			# Release notes

			Written for the 2.0 release.

			## Design Goals

			The agent summarizes the notes.
			```
			## Scenario
			```

			""" + VARIABLES + "\n";
		Scenario scenario = single();
		String file = scenario.rewrite(recorded);
		requireThat(file, "file").isEqualTo(head + TURN + "\n" + ASSERTIONS);
		requireThat(Scenario.parse(file), "parsed").isEqualTo(scenario);

		requireThat(scenario.rewrite(VARIABLES + TURN + ASSERTIONS), "default").
			isEqualTo(VARIABLES + "\n" + TURN + "\n" + ASSERTIONS);
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
	 * Verifies that a scenario whose text would read back differently, such as a description that ends with spaces,
	 * is refused rather than written.
	 */
	@Test(expectedExceptions = IllegalArgumentException.class, expectedExceptionsMessageRegExp = ".*differently.*")
	public void refusesTextThatReadsBackDifferently()
	{
		// Requirement: R-W11
		new Scenario(List.of(new VariableDeclaration("notes", "The notes file  ")),
			List.of("Summarize `docs/{{notes}}`."),
			List.of(new Assertion("summary_given", AssertionKind.EFFECT, null, "The reply summarizes the notes."))).
			render();
	}
}
