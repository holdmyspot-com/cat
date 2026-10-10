package io.github.cowwoc.cat.common.optimizer.contract;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * A prompt test's {@code scenario.md}: the variables that its test cases give values, the user turns and the
 * assertions that a compliant trial satisfies.
 * <p>
 * Turns, assertion statements and {@code tool:} lines may contain {@code {{name}}} placeholders for declared variables
 * and for {@value TestCase#EXPECTED_OUTCOME}. Text in double braces whose content is not lowercase words joined by
 * underscores, such as {@code ${{ github.token }}}, is not a placeholder.
 *
 * @param variables  the declared variables, in table order
 * @param turns      the user turns, in order
 * @param assertions the assertions, in order
 */
public record Scenario(List<VariableDeclaration> variables, List<String> turns, List<Assertion> assertions)
{
	/**
	 * Creates a scenario.
	 *
	 * @param variables  the declared variables, in table order
	 * @param turns      the user turns, in order
	 * @param assertions the assertions, in order
	 * @throws NullPointerException     if any of the arguments are null
	 * @throws IllegalArgumentException if a list is empty, a turn is blank, two variables share a name, two assertions
	 *                                  share an identifier, or a turn, assertion statement or {@code tool:} line has a
	 *                                  placeholder for an undeclared variable
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
	 * under {@code ## Scenario}, and several turns as numbered {@code ### Turn <n>} subsections.
	 *
	 * @return the sections, ending with a line break
	 * @throws IllegalArgumentException if a description spans several lines, or the text would read back differently,
	 *                                  such as a turn or statement that holds a section heading
	 */
	public String render()
	{
		StringBuilder text = new StringBuilder(256);
		text.append("## Variables\n\n| Name | Description |\n|---|---|\n");
		for (VariableDeclaration variable : variables)
		{
			if (variable.description().contains("\n"))
			{
				throw new IllegalArgumentException("The description of the variable " + variable.name() + " spans " +
					"several lines, but a variables table cell holds one line");
			}
			text.append("| ").append(variable.name()).append(" | ").
				append(variable.description().replace("|", "\\|")).append(" |\n");
		}

		text.append("\n## Scenario\n\n");
		if (turns.size() == 1)
			text.append(turns.getFirst()).append('\n');
		else
		{
			for (int i = 0; i < turns.size(); ++i)
			{
				if (i > 0)
					text.append('\n');
				text.append("### Turn ").append(i + 1).append("\n\n").append(turns.get(i)).append('\n');
			}
		}

		text.append("\n## Assertions\n");
		for (Assertion assertion : assertions)
		{
			text.append("\n### ").append(assertion.id()).append("\n\nkind: ").
				append(assertion.kind().name().toLowerCase(Locale.ROOT)).append('\n');
			if (assertion.tool() != null)
				text.append("tool: ").append(assertion.tool()).append('\n');
			text.append('\n').append(assertion.statement()).append('\n');
		}

		String rendered = text.toString();
		Scenario parsed;
		try
		{
			parsed = parse(rendered);
		}
		catch (ScenarioFormatException e)
		{
			throw new IllegalArgumentException("The scenario cannot be written as a scenario.md: " + e.getMessage(), e);
		}
		if (!parsed.equals(this))
		{
			throw new IllegalArgumentException("The scenario cannot be written as a scenario.md, because it would " +
				"read back differently:\n" + rendered);
		}
		return rendered;
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
	 * its title, and its {@code ## Design Goals} section are kept, and the sections that {@link #render()} returns
	 * replace the others.
	 *
	 * @param recorded the content of the recorded file
	 * @return the file's new content
	 * @throws NullPointerException     if {@code recorded} is null
	 * @throws ScenarioFormatException  if {@code recorded} has a section that the scenario format does not define, a
	 *                                  repeated section, or a title after a section
	 * @throws IllegalArgumentException if {@link #render()} refuses this scenario
	 */
	public String rewrite(String recorded) throws ScenarioFormatException
	{
		requireThat(recorded, "recorded").isNotNull();
		String file = new ScenarioParser("", recorded).titleAndDesignGoals() + render();
		if (!readsBack(file))
		{
			throw new IllegalArgumentException("The rewritten scenario.md would read back differently:\n" + file);
		}
		return file;
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
	 * Returns the variables that the turns, assertion statements or {@code tool:} lines name.
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
