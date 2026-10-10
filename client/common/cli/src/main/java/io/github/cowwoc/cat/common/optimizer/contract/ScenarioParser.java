package io.github.cowwoc.cat.common.optimizer.contract;

import io.github.cowwoc.cat.common.Identifier;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses the content of a {@code scenario.md}.
 * <p>
 * Headings inside fenced code blocks belong to the text around them. Every problem names its line.
 * <p>
 * <b>Thread Safety:</b> This class is not thread-safe.
 */
final class ScenarioParser
{
	private static final String VARIABLES = "Variables";
	private static final String SCENARIO = "Scenario";
	private static final String ASSERTIONS = "Assertions";
	/**
	 * The level-2 sections that a scenario may have.
	 */
	private static final String DESIGN_GOALS = "Design Goals";
	private static final Set<String> SECTIONS = Set.of(DESIGN_GOALS, VARIABLES, SCENARIO, ASSERTIONS);
	/**
	 * Matches a table separator cell, such as {@code ---} or {@code :---:}.
	 */
	private static final Pattern SEPARATOR_CELL = Pattern.compile(":?-+:?");
	/**
	 * Matches a key line of an assertion, capturing the key and its value.
	 */
	private static final Pattern KEY_LINE = Pattern.compile("([A-Za-z][A-Za-z_-]*):(.*)");
	/**
	 * Matches the opening of a fenced code block, capturing its fence.
	 */
	private static final Pattern FENCE = Pattern.compile("(`{3,}|~{3,}).*");
	/**
	 * The text that starts each problem's message, such as the file's path and a colon, or an empty string.
	 */
	private final String location;
	private final List<String> lines;
	/**
	 * Whether each line is part of a fenced code block, including its fences.
	 */
	private final boolean[] fenced;

	/**
	 * A level-2 section.
	 *
	 * @param heading the index of its heading line
	 * @param end     the index of the line after its last line
	 */
	private record Section(int heading, int end)
	{
	}

	/**
	 * Creates a parser.
	 *
	 * @param location the text that starts each problem's message, such as the file's path and a colon, or an empty
	 *                 string
	 * @param content  the content of a {@code scenario.md}
	 */
	ScenarioParser(String location, String content)
	{
		this.location = location;
		List<String> split = new ArrayList<>();
		for (String line : content.split("\n", -1))
		{
			if (line.endsWith("\r"))
				line = line.substring(0, line.length() - 1);
			split.add(line);
		}
		this.lines = split;
		this.fenced = fenced(split);
	}

	/**
	 * Marks the lines of fenced code blocks.
	 *
	 * @param lines the lines
	 * @return whether each line is part of a fenced code block, including its fences
	 */
	private static boolean[] fenced(List<String> lines)
	{
		boolean[] fenced = new boolean[lines.size()];
		String fence = null;
		for (int i = 0; i < lines.size(); ++i)
		{
			String line = lines.get(i).strip();
			if (fence == null)
			{
				Matcher opening = FENCE.matcher(line);
				if (opening.matches())
				{
					fence = opening.group(1);
					fenced[i] = true;
				}
			}
			else
			{
				fenced[i] = true;
				char marker = fence.charAt(0);
				if (line.length() >= fence.length() && line.chars().allMatch(character -> character == marker))
					fence = null;
			}
		}
		return fenced;
	}

	/**
	 * Parses the content.
	 *
	 * @return the scenario
	 * @throws ScenarioFormatException if the content does not follow the scenario format
	 */
	Scenario parse() throws ScenarioFormatException
	{
		Map<String, Section> sections = sections();
		for (String name : List.of(VARIABLES, SCENARIO, ASSERTIONS))
		{
			if (!sections.containsKey(name))
				throw new ScenarioFormatException(location + "The scenario has no ## " + name + " section");
		}
		List<VariableDeclaration> variables = variables(sections.get(VARIABLES));
		Set<String> names = new HashSet<>();
		for (VariableDeclaration variable : variables)
			names.add(variable.name());
		requireDeclared(sections.get(SCENARIO), names);
		requireDeclared(sections.get(ASSERTIONS), names);
		List<String> turns = turns(sections.get(SCENARIO));
		List<Assertion> assertions = assertions(sections.get(ASSERTIONS));
		try
		{
			return new Scenario(variables, turns, assertions);
		}
		catch (IllegalArgumentException e)
		{
			// Every rule of the constructor is checked above with its line
			throw new AssertionError("A checked scenario was refused", e);
		}
	}

	/**
	 * Returns the parts of the content that a scenario does not describe: the text before the first section, such as
	 * the title, and the {@code ## Design Goals} section. Each part ends with one blank line.
	 *
	 * @return the parts, in that order, or an empty string if the content has neither
	 * @throws ScenarioFormatException if a section is unknown or repeated, or a title follows a section
	 */
	String titleAndDesignGoals() throws ScenarioFormatException
	{
		Map<String, Section> sections = sections();
		int firstHeading = lines.size();
		if (!sections.isEmpty())
			firstHeading = sections.values().iterator().next().heading();
		StringBuilder kept = new StringBuilder(256);
		appendLines(kept, 0, firstHeading);
		Section designGoals = sections.get(DESIGN_GOALS);
		if (designGoals != null)
			appendLines(kept, designGoals.heading(), designGoals.end());
		return kept.toString();
	}

	/**
	 * Appends a range of lines without its trailing blank lines, followed by one blank line, unless the range holds only
	 * blank lines.
	 *
	 * @param out   the text to append to
	 * @param start the index of the first line
	 * @param end   the index of the line after the last line
	 */
	private void appendLines(StringBuilder out, int start, int end)
	{
		int last = end;
		while (last > start && lines.get(last - 1).isBlank())
			--last;
		if (last == start)
			return;
		for (int i = start; i < last; ++i)
			out.append(lines.get(i)).append('\n');
		out.append('\n');
	}

	/**
	 * Splits the content into its level-2 sections.
	 *
	 * @return the sections, by name
	 * @throws ScenarioFormatException if a section is unknown or repeated, or a title follows a section
	 */
	private Map<String, Section> sections() throws ScenarioFormatException
	{
		Map<String, Section> sections = new LinkedHashMap<>();
		String current = null;
		int heading = -1;
		for (int i = 0; i < lines.size(); ++i)
		{
			if (fenced[i])
				continue;
			String line = lines.get(i);
			if (line.startsWith("## "))
			{
				String name = line.substring("## ".length()).strip();
				if (!SECTIONS.contains(name))
				{
					throw error(i, "the section ## " + name + " is not part of the scenario format. Sections: ## Design " +
						"Goals, ## Variables, ## Scenario and ## Assertions");
				}
				if (sections.containsKey(name) || name.equals(current))
					throw error(i, "the section ## " + name + " appears twice");
				if (current != null)
					sections.put(current, new Section(heading, i));
				current = name;
				heading = i;
			}
			else if (line.startsWith("# ") && current != null)
				throw error(i, "the title must come before the first section");
		}
		if (current != null)
			sections.put(current, new Section(heading, lines.size()));
		return sections;
	}

	/**
	 * Parses the variables table.
	 *
	 * @param section the {@code ## Variables} section
	 * @return the declared variables, in table order
	 * @throws ScenarioFormatException if the table is malformed or declares no valid variable
	 */
	private List<VariableDeclaration> variables(Section section) throws ScenarioFormatException
	{
		int first = section.heading() + 1;
		while (first < section.end() && lines.get(first).isBlank())
			++first;
		if (first == section.end())
			throw error(section.heading(), "the ## Variables section has no table");
		int end = first;
		while (end < section.end() && !lines.get(end).isBlank())
			++end;
		for (int i = end; i < section.end(); ++i)
		{
			if (!lines.get(i).isBlank())
				throw error(i, "text follows the variables table; the section holds only the table");
		}

		List<String> header = cells(first);
		if (!header.equals(List.of("Name", "Description")))
			throw error(first, "the variables table's columns must be exactly Name and Description");
		if (first + 1 == end)
			throw error(first, "the variables table has no separator row");
		List<String> separator = cells(first + 1);
		if (separator.size() != 2 || !separator.stream().allMatch(cell -> SEPARATOR_CELL.matcher(cell).matches()))
			throw error(first + 1, "the variables table's second row must be its separator, such as |---|---|");

		List<VariableDeclaration> variables = new ArrayList<>();
		Set<String> names = new HashSet<>();
		for (int i = first + 2; i < end; ++i)
		{
			List<String> row = cells(i);
			if (row.size() != 2)
			{
				throw error(i, "a row of the variables table has " + row.size() + " cells, but it must have 2. Write " +
					"a literal | in a cell as \\|");
			}
			String name = row.get(0);
			if (!Identifier.SNAKE_CASE.matcher(name).matches())
				throw error(i, "the variable name '" + name + "' must be lowercase words joined by underscores");
			if (name.equals(TestCase.EXPECTED_OUTCOME))
			{
				throw error(i, "the variable name " + TestCase.EXPECTED_OUTCOME + " is reserved for the test case's " +
					"expected outcome");
			}
			if (!names.add(name))
				throw error(i, "the variable " + name + " is declared twice");
			if (row.get(1).isBlank())
				throw error(i, "the variable " + name + " has no description");
			variables.add(new VariableDeclaration(name, row.get(1)));
		}
		if (variables.isEmpty())
		{
			throw error(first, "the variables table declares no variable, so the prompt test has no test cases to " +
				"vary");
		}
		return variables;
	}

	/**
	 * Splits a table row into its cells.
	 *
	 * @param index the row's line index
	 * @return the stripped cells, with each {@code \|} replaced by {@code |}
	 * @throws ScenarioFormatException if the row does not start and end with {@code |}
	 */
	private List<String> cells(int index) throws ScenarioFormatException
	{
		String row = lines.get(index).strip();
		if (row.length() < 2 || !row.startsWith("|") || !row.endsWith("|") || row.endsWith("\\|"))
			throw error(index, "a table row must start and end with |");
		List<String> cells = new ArrayList<>();
		StringBuilder cell = new StringBuilder();
		for (int i = 1; i < row.length() - 1; ++i)
		{
			char character = row.charAt(i);
			if (character == '\\' && row.charAt(i + 1) == '|')
			{
				cell.append('|');
				++i;
			}
			else if (character == '|')
			{
				cells.add(cell.toString().strip());
				cell.setLength(0);
			}
			else
				cell.append(character);
		}
		cells.add(cell.toString().strip());
		return cells;
	}

	/**
	 * Rejects a placeholder for an undeclared variable in a section.
	 *
	 * @param section the section
	 * @param names   the declared variable names
	 * @throws ScenarioFormatException if a line of the section has such a placeholder
	 */
	private void requireDeclared(Section section, Set<String> names) throws ScenarioFormatException
	{
		for (int i = section.heading() + 1; i < section.end(); ++i)
		{
			String undeclared = Scenario.undeclared(names, lines.get(i));
			if (undeclared != null)
			{
				throw error(i, "the placeholder {{" + undeclared + "}} names no declared variable. Declared " +
					"variables: " + names);
			}
		}
	}

	/**
	 * Parses the turns.
	 *
	 * @param section the {@code ## Scenario} section
	 * @return the turns, in order
	 * @throws ScenarioFormatException if the section has no turn, a turn is empty, or the turn subsections are not
	 *                                 numbered from 1 in order
	 */
	private List<String> turns(Section section) throws ScenarioFormatException
	{
		List<Integer> headings = subsections(section);
		if (headings.isEmpty())
		{
			String turn = text(section.heading() + 1, section.end());
			if (turn.isEmpty())
				throw error(section.heading(), "the ## Scenario section has no turn");
			return List.of(turn);
		}
		requireBlank(section.heading() + 1, headings.getFirst(), "text comes before the first turn; put it in a turn");
		List<String> turns = new ArrayList<>();
		for (int k = 0; k < headings.size(); ++k)
		{
			int heading = headings.get(k);
			String title = lines.get(heading).substring("### ".length()).strip();
			String expected = "Turn " + (k + 1);
			if (!title.equals(expected))
			{
				throw error(heading, "the subsection ### " + title + " must be ### " + expected + ": turns are " +
					"numbered from 1 in order");
			}
			String turn = text(heading + 1, end(headings, k, section));
			if (turn.isEmpty())
				throw error(heading, expected + " is empty");
			turns.add(turn);
		}
		return turns;
	}

	/**
	 * Parses the assertions.
	 *
	 * @param section the {@code ## Assertions} section
	 * @return the assertions, in order
	 * @throws ScenarioFormatException if an assertion is malformed
	 */
	private List<Assertion> assertions(Section section) throws ScenarioFormatException
	{
		List<Integer> headings = subsections(section);
		int first;
		if (headings.isEmpty())
			first = section.end();
		else
			first = headings.getFirst();
		requireBlank(section.heading() + 1, first, "text comes before the first assertion; put it in an assertion " +
			"subsection, such as ### answer_given");
		if (headings.isEmpty())
			throw error(section.heading(), "the ## Assertions section has no assertion");
		List<Assertion> assertions = new ArrayList<>();
		Set<String> ids = new HashSet<>();
		for (int k = 0; k < headings.size(); ++k)
		{
			int heading = headings.get(k);
			String id = lines.get(heading).substring("### ".length()).strip();
			if (!Identifier.SNAKE_CASE.matcher(id).matches())
				throw error(heading, "the assertion id '" + id + "' must be lowercase words joined by underscores");
			if (id.equals(TestCase.EXPECTED_OUTCOME))
			{
				throw error(heading, "the assertion id " + TestCase.EXPECTED_OUTCOME + " is reserved for the " +
					"assertion that CAT adds");
			}
			if (!ids.add(id))
				throw error(heading, "the assertion " + id + " is declared twice");
			assertions.add(assertion(id, heading, end(headings, k, section)));
		}
		return assertions;
	}

	/**
	 * Parses one assertion's body.
	 *
	 * @param id      the assertion's identifier
	 * @param heading the index of its heading line
	 * @param end     the index of the line after its body
	 * @return the assertion
	 * @throws ScenarioFormatException if the body does not start with its key lines or has no statement
	 */
	private Assertion assertion(String id, int heading, int end) throws ScenarioFormatException
	{
		int i = heading + 1;
		while (i < end && lines.get(i).isBlank())
			++i;
		if (i == end)
			throw error(heading, "the assertion " + id + " has no kind: line and no statement");
		AssertionKind kind = null;
		String tool = null;
		for (; i < end && !lines.get(i).isBlank(); ++i)
		{
			Matcher keyLine = KEY_LINE.matcher(lines.get(i).strip());
			if (!keyLine.matches())
			{
				if (kind == null)
					throw error(i, "an assertion's body must start with kind: effect or kind: guard");
				throw error(i, "a key line has the form key: value; put a blank line before the assertion's statement");
			}
			String key = keyLine.group(1);
			String value = keyLine.group(2).strip();
			if (kind == null)
			{
				if (!key.equals("kind"))
					throw error(i, "an assertion's body must start with kind: effect or kind: guard");
				kind = switch (value)
				{
					case "effect" -> AssertionKind.EFFECT;
					case "guard" -> AssertionKind.GUARD;
					default -> throw error(i, "kind: " + value + " must be kind: effect or kind: guard");
				};
			}
			else if (key.equals("tool"))
			{
				if (tool != null)
					throw error(i, "the assertion " + id + " has two tool: lines");
				if (value.isEmpty())
					throw error(i, "tool: names no built-in tool");
				try
				{
					BuiltInTool.parse(value);
				}
				catch (IllegalArgumentException e)
				{
					throw error(i, "tool: " + e.getMessage());
				}
				tool = value;
			}
			else
				throw error(i, "the key line " + key + ": is unknown. Key lines: kind: and tool:");
		}
		String statement = text(i, end);
		if (statement.isEmpty())
			throw error(heading, "the assertion " + id + " has no statement");
		return new Assertion(id, kind, tool, statement);
	}

	/**
	 * Returns the level-3 headings of a section.
	 *
	 * @param section the section
	 * @return the indexes of its {@code ###} heading lines, in order
	 */
	private List<Integer> subsections(Section section)
	{
		List<Integer> headings = new ArrayList<>();
		for (int i = section.heading() + 1; i < section.end(); ++i)
		{
			if (!fenced[i] && lines.get(i).startsWith("### "))
				headings.add(i);
		}
		return headings;
	}

	/**
	 * Returns the index of the line after a subsection.
	 *
	 * @param headings the indexes of the section's subsection headings
	 * @param position the subsection's position
	 * @param section  the section
	 * @return the next subsection's heading, or the section's end
	 */
	private static int end(List<Integer> headings, int position, Section section)
	{
		if (position + 1 < headings.size())
			return headings.get(position + 1);
		return section.end();
	}

	/**
	 * Rejects text in a range of lines.
	 *
	 * @param start   the index of the first line
	 * @param end     the index of the line after the last
	 * @param problem the problem to report
	 * @throws ScenarioFormatException if a line in the range is not blank
	 */
	private void requireBlank(int start, int end, String problem) throws ScenarioFormatException
	{
		for (int i = start; i < end; ++i)
		{
			if (!lines.get(i).isBlank())
				throw error(i, problem);
		}
	}

	/**
	 * Returns a range of lines without its leading and trailing blank lines.
	 *
	 * @param start the index of the first line
	 * @param end   the index of the line after the last
	 * @return the lines joined by {@code \n}, or an empty string if every line is blank
	 */
	private String text(int start, int end)
	{
		while (start < end && lines.get(start).isBlank())
			++start;
		while (end > start && lines.get(end - 1).isBlank())
			--end;
		return String.join("\n", lines.subList(start, end));
	}

	/**
	 * Returns a problem at a line.
	 *
	 * @param index   the line's index
	 * @param problem the problem
	 * @return the exception
	 */
	private ScenarioFormatException error(int index, String problem)
	{
		return new ScenarioFormatException(location + "Line " + (index + 1) + ": " + problem);
	}
}
