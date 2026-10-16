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
	private static final String FIXTURES = "Fixtures";
	private static final String SCENARIO = "Scenario";
	private static final String ASSERTIONS = "Assertions";
	private static final String DESIGN_GOALS = "Design Goals";
	/**
	 * The level-2 sections that a scenario may have.
	 */
	private static final Set<String> SECTIONS = Set.of(DESIGN_GOALS, VARIABLES, FIXTURES, SCENARIO, ASSERTIONS);
	private static final String PATTERNS = "patterns";
	private static final String KIND = "kind";
	private static final String TOOL = "tool";
	private static final String ON_TOOL_FAILURE = "on_tool_failure";
	private static final String DESCRIPTION = "description";
	/**
	 * Matches a key-value pair {@code - **key**: value}, capturing the key and the value's first line.
	 */
	private static final Pattern ITEM = Pattern.compile("- \\*\\*([^*]+)\\*\\*:(.*)");
	/**
	 * The indentation of the lines that continue a pair's value.
	 */
	private static final String INDENT = "  ";
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
	 * Indicates whether each line is part of a fenced code block, including its fences.
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
	 * A key-value pair.
	 *
	 * @param key   the key
	 * @param line  the index of the line that holds the key
	 * @param end   the index of the line after its value
	 * @param value the value, with the indentation of its continuation lines removed, stripped
	 */
	private record Pair(String key, int line, int end, String value)
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
		if (sections.containsKey(FIXTURES))
			patterns(sections.get(FIXTURES));
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
	 * Returns the fixture patterns, without checking the other sections.
	 *
	 * @return the patterns of the {@code ## Fixtures} section, or {@value FixturePattern#DEFAULT} alone if the content
	 *         has no such section
	 * @throws ScenarioFormatException if a section is unknown or repeated, a title follows a section, or the
	 *                                 {@code ## Fixtures} section is malformed
	 */
	List<String> fixturePatterns() throws ScenarioFormatException
	{
		List<String> declared = declaredPatterns();
		if (declared.isEmpty())
			return List.of(FixturePattern.DEFAULT);
		return declared;
	}

	/**
	 * Returns the fixture patterns that the {@code ## Fixtures} section declares, without checking the other sections.
	 *
	 * @return the patterns, or an empty list if the content has no {@code ## Fixtures} section
	 * @throws ScenarioFormatException if a section is unknown or repeated, a title follows a section, or the
	 *                                 {@code ## Fixtures} section is malformed
	 */
	List<String> declaredPatterns() throws ScenarioFormatException
	{
		Section section = sections().get(FIXTURES);
		if (section == null)
			return List.of();
		return patterns(section);
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
						"Goals, ## Variables, ## Fixtures, ## Scenario and ## Assertions");
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
	 * Parses the variables.
	 *
	 * @param section the {@code ## Variables} section
	 * @return the declared variables, in order
	 * @throws ScenarioFormatException if the section holds prose or declares no valid variable
	 */
	private List<VariableDeclaration> variables(Section section) throws ScenarioFormatException
	{
		List<Pair> pairs = pairs(section.heading() + 1, section.end(), "the ## Variables section holds only " +
			"key-value pairs, such as - **notes**: The release-notes file name");
		if (pairs.isEmpty())
		{
			throw error(section.heading(), "the ## Variables section declares no variable, so the prompt test has no " +
				"test cases to vary");
		}
		List<VariableDeclaration> variables = new ArrayList<>();
		for (Pair pair : pairs)
		{
			String name = pair.key();
			if (!Identifier.SNAKE_CASE.matcher(name).matches())
				throw error(pair.line(), "the variable name '" + name + "' must be lowercase words joined by underscores");
			if (name.equals(TestCase.EXPECTED_OUTCOME))
			{
				throw error(pair.line(), "the variable name " + TestCase.EXPECTED_OUTCOME + " is reserved for the test " +
					"case's expected outcome");
			}
			if (pair.value().isEmpty())
				throw error(pair.line(), "the variable " + name + " has no description");
			variables.add(new VariableDeclaration(name, pair.value()));
		}
		return variables;
	}

	/**
	 * Parses the fixture patterns, written as code spans separated by commas or as nested items that each hold one.
	 *
	 * @param section the {@code ## Fixtures} section
	 * @return the patterns, in order
	 * @throws ScenarioFormatException if the section holds prose, a key other than {@code patterns}, or no pattern, or
	 *                                 a pattern is malformed
	 */
	private List<String> patterns(Section section) throws ScenarioFormatException
	{
		List<Pair> pairs = pairs(section.heading() + 1, section.end(), "the ## Fixtures section holds only " +
			"key-value pairs, such as - **patterns**: `fixtures/**`");
		requireKeys(pairs, Set.of(PATTERNS), PATTERNS);
		Pair pair = find(pairs, PATTERNS);
		if (pair == null)
			throw error(section.heading(), "the ## Fixtures section has no - **patterns**: pair");

		List<String> patterns = new ArrayList<>();
		if (firstValue(pair).isEmpty())
		{
			for (int i = pair.line() + 1; i < pair.end(); ++i)
			{
				String line = lines.get(i);
				if (line.isBlank())
					continue;
				String nested = line.substring(INDENT.length());
				List<String> spans = List.of();
				if (nested.startsWith("- "))
					spans = codeSpans(i, nested.substring("- ".length()));
				if (spans.size() != 1)
				{
					throw error(i, "a nested pattern must be a list item that holds one code span, such as " +
						"- `fixtures/**`");
				}
				patterns.add(pattern(i, spans.getFirst()));
			}
		}
		else
		{
			for (String span : codeSpans(pair.line(), pair.value()))
				patterns.add(pattern(pair.line(), span));
		}
		if (patterns.isEmpty())
			throw error(pair.line(), "the key patterns names no pattern, such as `fixtures/**`");
		return patterns;
	}

	/**
	 * Reads code spans separated by commas.
	 *
	 * @param index the index of the line that holds them
	 * @param text  the text
	 * @return the content of each span, in order
	 * @throws ScenarioFormatException if {@code text} holds anything other than code spans separated by commas
	 */
	private List<String> codeSpans(int index, String text) throws ScenarioFormatException
	{
		try
		{
			return CodeSpan.parseList(text);
		}
		catch (IllegalArgumentException e)
		{
			throw error(index, e.getMessage());
		}
	}

	/**
	 * Validates a fixture pattern.
	 *
	 * @param index   the index of the line that holds it
	 * @param pattern the pattern
	 * @return {@code pattern}
	 * @throws ScenarioFormatException if the pattern is malformed
	 */
	private String pattern(int index, String pattern) throws ScenarioFormatException
	{
		try
		{
			FixturePattern.parse(pattern);
		}
		catch (IllegalArgumentException e)
		{
			throw error(index, e.getMessage());
		}
		return pattern;
	}

	/**
	 * Reads the key-value pairs of a range of lines. Each pair is an item {@code - **key**: value} whose value continues
	 * on the following blank lines and lines indented by two spaces.
	 *
	 * @param start the index of the first line
	 * @param end   the index of the line after the last
	 * @param prose the problem to report for a line that is neither a pair nor part of one's value
	 * @return the pairs, in order
	 * @throws ScenarioFormatException if a line is neither a pair nor part of one's value, or a key appears twice
	 */
	private List<Pair> pairs(int start, int end, String prose) throws ScenarioFormatException
	{
		List<Pair> pairs = new ArrayList<>();
		Set<String> keys = new HashSet<>();
		String key = null;
		int keyLine = -1;
		List<String> value = new ArrayList<>();
		for (int i = start; i < end; ++i)
		{
			String line = lines.get(i);
			Matcher item = ITEM.matcher(line);
			if (item.matches())
			{
				if (key != null)
					pairs.add(new Pair(key, keyLine, i, String.join("\n", value).strip()));
				key = item.group(1);
				if (!keys.add(key))
					throw error(i, "the key " + key + " appears twice");
				keyLine = i;
				value.clear();
				value.add(item.group(2));
			}
			else if (line.isBlank())
				value.add("");
			else if (key != null && line.startsWith(INDENT))
				value.add(line.substring(INDENT.length()));
			else
				throw error(i, prose);
		}
		if (key != null)
			pairs.add(new Pair(key, keyLine, end, String.join("\n", value).strip()));
		return pairs;
	}

	/**
	 * Rejects a key that a block does not define.
	 *
	 * @param pairs   the block's pairs
	 * @param defined the keys that the block defines
	 * @param names   the defined keys, as the problem lists them
	 * @throws ScenarioFormatException if a pair's key is not defined
	 */
	private void requireKeys(List<Pair> pairs, Set<String> defined, String names) throws ScenarioFormatException
	{
		for (Pair pair : pairs)
		{
			if (!defined.contains(pair.key()))
				throw error(pair.line(), "the key " + pair.key() + " is not defined here. Keys: " + names);
		}
	}

	/**
	 * Finds a pair by its key.
	 *
	 * @param pairs the pairs
	 * @param key   the key
	 * @return the pair with that key, or {@code null} if no pair has it
	 */
	private static Pair find(List<Pair> pairs, String key)
	{
		for (Pair pair : pairs)
		{
			if (pair.key().equals(key))
				return pair;
		}
		return null;
	}

	/**
	 * Returns the part of a pair's value on its key's line.
	 *
	 * @param pair the pair
	 * @return the text after the key's colon, stripped
	 */
	private String firstValue(Pair pair)
	{
		Matcher item = ITEM.matcher(lines.get(pair.line()));
		if (!item.matches())
			throw new AssertionError("A pair's line is not an item: " + lines.get(pair.line()));
		return item.group(2).strip();
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
			String turn = turn(section.heading() + 1, section.end(), "the ## Scenario section");
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
			String turn = turn(heading + 1, end(headings, k, section), "### " + expected);
			if (turn.isEmpty())
				throw error(heading, expected + " is empty");
			turns.add(turn);
		}
		return turns;
	}

	/**
	 * Parses one turn, written as prose or as a {@code description} pair.
	 *
	 * @param start the index of the first line
	 * @param end   the index of the line after the last
	 * @param block the section or subsection that holds the turn, as the problem names it
	 * @return the turn, or an empty string if every line is blank
	 * @throws ScenarioFormatException if the turn mixes prose with pairs, has a key other than {@code description}, or
	 *                                 its {@code description} has no value
	 */
	private String turn(int start, int end, String block) throws ScenarioFormatException
	{
		int first = start;
		while (first < end && lines.get(first).isBlank())
			++first;
		if (first == end)
			return "";
		String mixed = block + " mixes prose with key-value pairs; write the turn either as prose or as " +
			"- **description**: followed by the turn";
		if (fenced[first] || !ITEM.matcher(lines.get(first)).matches())
		{
			for (int i = first; i < end; ++i)
			{
				if (!fenced[i] && ITEM.matcher(lines.get(i)).matches())
					throw error(i, mixed);
			}
			return text(start, end);
		}
		List<Pair> pairs = pairs(start, end, mixed);
		requireKeys(pairs, Set.of(DESCRIPTION), DESCRIPTION);
		Pair description = pairs.getFirst();
		if (description.value().isEmpty())
			throw error(description.line(), "the key description has no value; write the turn after it");
		return description.value();
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
	 * Parses one assertion's pairs.
	 *
	 * @param id      the assertion's identifier
	 * @param heading the index of its heading line
	 * @param end     the index of the line after its last line
	 * @return the assertion
	 * @throws ScenarioFormatException if the assertion holds prose or an undefined key, lacks its {@code kind} or
	 *                                 {@code description}, has an {@code on_tool_failure} without a {@code tool}, or
	 *                                 holds an invalid value
	 */
	private Assertion assertion(String id, int heading, int end) throws ScenarioFormatException
	{
		List<Pair> pairs = pairs(heading + 1, end, "the assertion " + id + " holds only key-value pairs, such as " +
			"- **kind**: effect");
		requireKeys(pairs, Set.of(KIND, TOOL, ON_TOOL_FAILURE, DESCRIPTION), "kind, tool, on_tool_failure and " +
			"description");

		Pair kindPair = find(pairs, KIND);
		if (kindPair == null)
		{
			throw error(heading, "the assertion " + id + " has no kind; add - **kind**: effect or " +
				"- **kind**: guard");
		}
		AssertionKind kind = switch (kindPair.value())
		{
			case "effect" -> AssertionKind.EFFECT;
			case "guard" -> AssertionKind.GUARD;
			default -> throw error(kindPair.line(), "the kind " + kindPair.value() + " must be effect or guard");
		};

		String tool = null;
		Pair toolPair = find(pairs, TOOL);
		if (toolPair != null)
			tool = tool(toolPair);

		OnToolFailure onToolFailure = OnToolFailure.FAIL;
		Pair onToolFailurePair = find(pairs, ON_TOOL_FAILURE);
		if (onToolFailurePair != null)
		{
			if (toolPair == null)
			{
				throw error(onToolFailurePair.line(), "the key on_tool_failure needs a tool whose failure it handles; " +
					"add - **tool**: or remove it");
			}
			onToolFailure = switch (onToolFailurePair.value())
			{
				case "fail" -> OnToolFailure.FAIL;
				case "judge" -> OnToolFailure.JUDGE;
				default -> throw error(onToolFailurePair.line(), "the on_tool_failure " + onToolFailurePair.value() +
					" must be fail or judge");
			};
		}

		Pair description = find(pairs, DESCRIPTION);
		if (description == null)
			throw error(heading, "the assertion " + id + " has no description; add - **description**: followed by it");
		if (description.value().isEmpty())
			throw error(description.line(), "the key description has no value");
		return new Assertion(id, kind, tool, onToolFailure, description.value());
	}

	/**
	 * Parses an assertion's {@code tool}, written as one code span.
	 *
	 * @param pair the {@code tool} pair
	 * @return the built-in tool and its arguments
	 * @throws ScenarioFormatException if the value is empty, is not one code span, or names no built-in tool or gives
	 *                                 it unsuitable arguments
	 */
	private String tool(Pair pair) throws ScenarioFormatException
	{
		if (pair.value().isEmpty())
			throw error(pair.line(), "the key tool names no built-in tool");
		List<String> spans;
		try
		{
			spans = CodeSpan.parseList(pair.value());
		}
		catch (IllegalArgumentException _)
		{
			spans = List.of();
		}
		if (spans.size() != 1)
			throw error(pair.line(), "the tool must be one code span, such as `read-in-full docs/{{notes}}`");
		String tool = spans.getFirst();
		try
		{
			BuiltInTool.parse(tool);
		}
		catch (IllegalArgumentException e)
		{
			throw error(pair.line(), "the tool " + e.getMessage());
		}
		return tool;
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
