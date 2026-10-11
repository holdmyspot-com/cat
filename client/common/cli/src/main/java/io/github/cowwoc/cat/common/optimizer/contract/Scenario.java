package io.github.cowwoc.cat.common.optimizer.contract;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.StringJoiner;

/**
 * A prompt test's {@code scenario.md}: the variables that its test cases give values, the user turns and the
 * assertions that a compliant trial satisfies.
 * <p>
 * Turns, assertion statements and assertions' {@code tool} values may contain {@code {{name}}} placeholders for
 * declared variables and for {@value TestCase#EXPECTED_OUTCOME}. Text in double braces whose content is not lowercase
 * words joined by underscores, such as {@code ${{ github.token }}}, is not a placeholder.
 *
 * @param variables  the declared variables, in order
 * @param turns      the user turns, in order
 * @param assertions the assertions, in order
 */
public record Scenario(List<VariableDeclaration> variables, List<String> turns, List<Assertion> assertions)
{
	/**
	 * Creates a scenario.
	 *
	 * @param variables  the declared variables, in order
	 * @param turns      the user turns, in order
	 * @param assertions the assertions, in order
	 * @throws NullPointerException     if any of the arguments are null
	 * @throws IllegalArgumentException if a list is empty, a turn is blank, two variables share a name, two assertions
	 *                                  share an identifier, or a turn, assertion statement or assertion's {@code tool}
	 *                                  has a placeholder for an undeclared variable
	 */
	public Scenario
	{
		requireThat(variables, "variables").isNotEmpty();
		requireThat(turns, "turns").isNotEmpty();
		requireThat(assertions, "assertions").isNotEmpty();
		variables = List.copyOf(variables);
		turns = List.copyOf(turns);
		assertions = List.copyOf(assertions);

		Set<String> names = new HashSet<>();
		for (VariableDeclaration variable : variables)
		{
			if (!names.add(variable.name()))
				throw new IllegalArgumentException("The variable '" + variable.name() + "' is declared twice");
		}
		Set<String> ids = new HashSet<>();
		for (Assertion assertion : assertions)
		{
			if (!ids.add(assertion.id()))
				throw new IllegalArgumentException("The assertion '" + assertion.id() + "' is declared twice");
		}
		for (String turn : turns)
		{
			requireThat(turn, "turn").isNotBlank();
			requireDeclared(names, turn);
		}
		for (Assertion assertion : assertions)
		{
			requireDeclared(names, assertion.statement());
			if (assertion.tool() != null)
				requireDeclared(names, assertion.tool());
		}
	}

	/**
	 * Rejects a text with a placeholder for an undeclared variable.
	 *
	 * @param names the declared variable names
	 * @param text  the text
	 * @throws IllegalArgumentException if {@code text} has a placeholder that is neither declared nor
	 *                                  {@value TestCase#EXPECTED_OUTCOME}
	 */
	private static void requireDeclared(Set<String> names, String text)
	{
		String undeclared = undeclared(names, text);
		if (undeclared != null)
		{
			throw new IllegalArgumentException("The placeholder {{" + undeclared + "}} names no declared variable. " +
				"Declared variables: " + names);
		}
	}

	/**
	 * Returns the first placeholder of a text that names neither a declared variable nor the expected outcome.
	 *
	 * @param names the declared variable names
	 * @param text  the text
	 * @return the undeclared name, or {@code null} if every placeholder is declared
	 */
	static String undeclared(Set<String> names, String text)
	{
		for (String name : Placeholders.scenarioNames(text))
		{
			if (!name.equals(TestCase.EXPECTED_OUTCOME) && !names.contains(name))
				return name;
		}
		return null;
	}

	/**
	 * Parses the content of a {@code scenario.md}.
	 *
	 * @param content the file's content
	 * @return the scenario
	 * @throws NullPointerException      if {@code content} is null
	 * @throws ScenarioFormatException if the content does not follow the scenario format; the message starts with the
	 *                                   line it concerns
	 */
	public static Scenario parse(String content) throws ScenarioFormatException
	{
		requireThat(content, "content").isNotNull();
		return new ScenarioParser("", content).parse();
	}

	/**
	 * Returns the {@code ## Variables}, {@code ## Scenario} and {@code ## Assertions} sections of a
	 * {@code scenario.md} that {@link #parse(String)} reads back as this scenario. A single turn is written directly
	 * under {@code ## Scenario}, and several turns as numbered {@code ### Turn <n>} subsections. A turn is written as
	 * prose, or as a {@code description} pair if a line of it would read as a heading or a pair.
	 *
	 * @return the sections, ending with a line break
	 * @throws IllegalArgumentException if the text would read back differently, such as a description that ends with
	 *                                  spaces
	 */
	public String render()
	{
		return render(List.of(FixturePattern.DEFAULT));
	}

	/**
	 * Returns the sections of a {@code scenario.md} that reads back as this scenario and selects fixtures by a list of
	 * patterns.
	 *
	 * @param patterns the fixture patterns; a {@code ## Fixtures} section is written unless they are
	 *                 {@value FixturePattern#DEFAULT} alone
	 * @return the sections, ending with a line break
	 * @throws IllegalArgumentException if the text would read back differently
	 */
	private String render(List<String> patterns)
	{
		StringBuilder text = new StringBuilder(256);
		text.append("## Variables\n\n");
		for (VariableDeclaration variable : variables)
			appendPair(text, variable.name(), variable.description());

		if (!patterns.equals(List.of(FixturePattern.DEFAULT)))
		{
			StringJoiner spans = new StringJoiner(", ");
			for (String pattern : patterns)
				spans.add(CodeSpan.render(pattern));
			text.append("\n## Fixtures\n\n- **patterns**: ").append(spans).append('\n');
		}

		text.append("\n## Scenario\n\n");
		if (turns.size() == 1)
			appendTurn(text, turns.getFirst());
		else
		{
			for (int i = 0; i < turns.size(); ++i)
			{
				if (i > 0)
					text.append('\n');
				text.append("### Turn ").append(i + 1).append("\n\n");
				appendTurn(text, turns.get(i));
			}
		}

		text.append("\n## Assertions\n");
		for (Assertion assertion : assertions)
		{
			text.append("\n### ").append(assertion.id()).append("\n\n");
			appendPair(text, "kind", assertion.kind().name().toLowerCase(Locale.ROOT));
			if (assertion.tool() != null)
				appendPair(text, "tool", CodeSpan.render(assertion.tool()));
			appendPair(text, "description", assertion.statement());
		}

		String rendered = text.toString();
		Scenario parsed;
		List<String> parsedPatterns;
		try
		{
			parsed = parse(rendered);
			parsedPatterns = fixturePatterns(rendered);
		}
		catch (ScenarioFormatException e)
		{
			throw new IllegalArgumentException("The scenario cannot be written as a scenario.md: " + e.getMessage(), e);
		}
		if (!parsed.equals(this) || !parsedPatterns.equals(patterns))
		{
			throw new IllegalArgumentException("The scenario cannot be written as a scenario.md, because it would " +
				"read back differently:\n" + rendered);
		}
		return rendered;
	}

	/**
	 * Appends a key-value pair whose value continues on lines indented by two spaces.
	 *
	 * @param text  the text to append to
	 * @param key   the key
	 * @param value the value
	 */
	private static void appendPair(StringBuilder text, String key, String value)
	{
		text.append("- **").append(key).append("**:");
		String[] lines = value.split("\n", -1);
		if (!lines[0].isEmpty())
			text.append(' ').append(lines[0]);
		text.append('\n');
		for (int i = 1; i < lines.length; ++i)
		{
			if (!lines[i].isEmpty())
				text.append("  ").append(lines[i]);
			text.append('\n');
		}
	}

	/**
	 * Appends a turn as prose, or as a {@code description} pair if a line of it would read as a heading or a pair.
	 *
	 * @param text the text to append to
	 * @param turn the turn
	 */
	private static void appendTurn(StringBuilder text, String turn)
	{
		for (String line : turn.split("\n", -1))
		{
			if (line.startsWith("#") || line.startsWith("- **"))
			{
				appendPair(text, "description", turn);
				return;
			}
		}
		text.append(turn).append('\n');
	}

	/**
	 * Returns a new {@code scenario.md} for this scenario: a title, the design goals and the sections that
	 * {@link #render()} returns. Design goals that would read back as a section or a title are left out.
	 *
	 * @param title       the file's title
	 * @param designGoals what a compliant response does
	 * @return the file's content
	 * @throws NullPointerException     if any of the arguments are null
	 * @throws IllegalArgumentException if {@code title} is blank or spans several lines, or {@link #render()} refuses
	 *                                  this scenario
	 */
	public String renderFile(String title, String designGoals)
	{
		requireThat(title, "title").isNotBlank().doesNotContain("\n");
		requireThat(designGoals, "designGoals").isNotNull();
		String sections = render();
		String heading = "# " + title.strip() + "\n\n";
		String file = heading + "## Design Goals\n\n" + designGoals.strip() + "\n\n" + sections;
		if (readsBack(file))
			return file;
		return heading + sections;
	}

	/**
	 * Returns a recorded {@code scenario.md} rewritten for this scenario: the text before its first section, such as
	 * its title, its {@code ## Design Goals} section and its fixture patterns are kept, the given patterns are appended
	 * to them, and the rendered sections replace the others.
	 *
	 * @param recorded the content of the recorded file
	 * @param appended the fixture patterns to append, in order
	 * @return the file's new content
	 * @throws NullPointerException     if any of the arguments are null
	 * @throws ScenarioFormatException  if {@code recorded} has a section that the scenario format does not define, a
	 *                                  repeated section, a title after a section, or a malformed {@code ## Fixtures}
	 *                                  section
	 * @throws IllegalArgumentException if an appended pattern is malformed, or the file would read back differently
	 */
	public String rewrite(String recorded, List<String> appended) throws ScenarioFormatException
	{
		requireThat(recorded, "recorded").isNotNull();
		requireThat(appended, "appended").isNotNull();
		for (String pattern : appended)
			FixturePattern.parse(pattern);
		ScenarioParser parser = new ScenarioParser("", recorded);
		List<String> patterns = new ArrayList<>(parser.fixturePatterns());
		patterns.addAll(appended);
		String file = parser.titleAndDesignGoals() + render(patterns);
		if (!readsBack(file))
			throw new IllegalArgumentException("The rewritten scenario.md would read back differently:\n" + file);
		return file;
	}

	/**
	 * Returns the fixture patterns of a {@code scenario.md}, reading only its {@code ## Fixtures} section.
	 *
	 * @param content the file's content
	 * @return the patterns, in order, or {@value FixturePattern#DEFAULT} alone if the file has no
	 *         {@code ## Fixtures} section
	 * @throws NullPointerException    if {@code content} is null
	 * @throws ScenarioFormatException if a section is unknown or repeated, a title follows a section, or the
	 *                                 {@code ## Fixtures} section is malformed; the message starts with the line it
	 *                                 concerns
	 */
	public static List<String> fixturePatterns(String content) throws ScenarioFormatException
	{
		requireThat(content, "content").isNotNull();
		return new ScenarioParser("", content).fixturePatterns();
	}

	/**
	 * Indicates whether a {@code scenario.md} reads back as this scenario.
	 *
	 * @param file the file's content
	 * @return {@code true} if the file parses into this scenario
	 */
	private boolean readsBack(String file)
	{
		try
		{
			return parse(file).equals(this);
		}
		catch (ScenarioFormatException _)
		{
			return false;
		}
	}

	/**
	 * Returns the variables that the turns, assertion statements or assertions' {@code tool} values name.
	 *
	 * @return the names of the declared variables that this text uses, in order of first use
	 */
	public Set<String> namedInText()
	{
		Set<String> named = new LinkedHashSet<>();
		for (String turn : turns)
			named.addAll(Placeholders.scenarioNames(turn));
		for (Assertion assertion : assertions)
		{
			named.addAll(Placeholders.scenarioNames(assertion.statement()));
			if (assertion.tool() != null)
				named.addAll(Placeholders.scenarioNames(assertion.tool()));
		}
		named.remove(TestCase.EXPECTED_OUTCOME);
		return named;
	}
}
