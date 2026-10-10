package io.github.cowwoc.cat.common.optimizer.contract;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import tools.jackson.databind.json.JsonMapper;

/**
 * A prompt test directory: its {@code scenario.md}, the files under its {@code fixtures/} directory and the test
 * cases of its values file.
 *
 * @param directory the prompt test directory
 * @param scenario  the parsed scenario
 * @param fixtures  the fixture files, sorted by path
 * @param testCases the test cases of the values file, or an empty list if the directory has none
 */
public record PromptTestFiles(Path directory, Scenario scenario, List<PromptTestFiles.Fixture> fixtures,
	List<TestCase> testCases)
{
	/**
	 * The scenario's file name.
	 */
	public static final String SCENARIO = "scenario.md";
	/**
	 * The name of the directory that holds the fixtures.
	 */
	public static final String FIXTURES = "fixtures";
	/**
	 * The characters that a file name segment may not contain on some operating system.
	 */
	private static final String RESERVED_CHARACTERS = "/\\:*?\"<>|";

	/**
	 * One file under {@code fixtures/}.
	 *
	 * @param path         the path relative to {@code fixtures/}, with {@code /} separators, which may contain
	 *                     placeholders
	 * @param text         {@code true} if the content is valid UTF-8, so that its placeholders are replaced;
	 *                     {@code false} if it is copied unchanged
	 * @param contentNames the declared variables that the content names; empty if {@code text} is {@code false}
	 */
	public record Fixture(String path, boolean text, Set<String> contentNames)
	{
		/**
		 * Creates a fixture.
		 *
		 * @param path         the path relative to {@code fixtures/}, with {@code /} separators
		 * @param text         {@code true} if the content is valid UTF-8
		 * @param contentNames the declared variables that the content names
		 * @throws NullPointerException     if {@code path} or {@code contentNames} are null
		 * @throws IllegalArgumentException if {@code path} is blank, or {@code contentNames} is not empty when
		 *                                  {@code text} is {@code false}
		 */
		public Fixture
		{
			requireThat(path, "path").isNotBlank();
			requireThat(contentNames, "contentNames").isNotNull();
			if (!text)
				requireThat(contentNames, "contentNames").isEmpty();
			contentNames = Set.copyOf(contentNames);
		}
	}

	/**
	 * Creates the files of a prompt test.
	 *
	 * @param directory the prompt test directory
	 * @param scenario  the parsed scenario
	 * @param fixtures  the fixture files, sorted by path
	 * @param testCases the test cases of the values file, or an empty list if the directory has none
	 * @throws NullPointerException if any of the arguments are null
	 */
	public PromptTestFiles
	{
		requireThat(directory, "directory").isNotNull();
		requireThat(scenario, "scenario").isNotNull();
		fixtures = List.copyOf(fixtures);
		testCases = List.copyOf(testCases);
	}

	/**
	 * Reads a prompt test directory.
	 *
	 * @param json      the JSON mapper that reads the values file
	 * @param directory the prompt test directory
	 * @return the prompt test's files
	 * @throws NullPointerException    if any of the arguments are null
	 * @throws IOException             if a file cannot be read
	 * @throws ScenarioFormatException if the scenario or the values file does not follow the format, a fixture is a
	 *                                 symbolic link, or a committed test case is not valid for the prompt test
	 */
	public static PromptTestFiles read(JsonMapper json, Path directory) throws IOException, ScenarioFormatException
	{
		requireThat(json, "json").isNotNull();
		requireThat(directory, "directory").isNotNull();
		Path scenarioFile = directory.resolve(SCENARIO);
		Scenario scenario = new ScenarioParser(scenarioFile + ": ", Files.readString(scenarioFile)).parse();
		Set<String> declared = new HashSet<>();
		for (VariableDeclaration variable : scenario.variables())
			declared.add(variable.name());
		List<Fixture> fixtures = readFixtures(directory.resolve(FIXTURES), declared);

		Path valuesFile = directory.resolve(ValuesFile.NAME);
		if (!Files.exists(valuesFile, LinkOption.NOFOLLOW_LINKS))
			return new PromptTestFiles(directory, scenario, fixtures, List.of());
		List<TestCase> testCases;
		try (BufferedReader in = Files.newBufferedReader(valuesFile))
		{
			testCases = ValuesFile.read(json, in, valuesFile.toString());
		}
		PromptTestFiles files = new PromptTestFiles(directory, scenario, fixtures, List.of());
		for (int i = 0; i < testCases.size(); ++i)
		{
			List<String> problems = files.problems(testCases.get(i));
			if (!problems.isEmpty())
			{
				throw new ScenarioFormatException(valuesFile + ": test case " + (i + 1) + " is not valid for the " +
					"prompt test: " + String.join("; ", problems));
			}
		}
		return new PromptTestFiles(directory, scenario, fixtures, testCases);
	}

	/**
	 * Lists the fixtures of a prompt test.
	 *
	 * @param root     the {@code fixtures/} directory, which need not exist
	 * @param declared the declared variable names
	 * @return the fixtures, sorted by path
	 * @throws IOException             if a fixture cannot be read
	 * @throws ScenarioFormatException if a fixture is a symbolic link or another file that is neither a regular file
	 *                                 nor a directory
	 */
	private static List<Fixture> readFixtures(Path root, Set<String> declared)
		throws IOException, ScenarioFormatException
	{
		if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS))
			return List.of();
		if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS))
			throw new ScenarioFormatException(root + ": " + FIXTURES + " must be a directory");
		List<Path> paths;
		try (Stream<Path> walk = Files.walk(root))
		{
			paths = walk.toList();
		}
		List<Fixture> fixtures = new ArrayList<>();
		for (Path path : paths)
		{
			BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class,
				LinkOption.NOFOLLOW_LINKS);
			if (attributes.isDirectory())
				continue;
			if (!attributes.isRegularFile())
			{
				throw new ScenarioFormatException(path + ": a fixture must be a regular file, but this is a symbolic " +
					"link or another special file");
			}
			String relative = root.relativize(path).toString().replace(path.getFileSystem().getSeparator(), "/");
			String content = utf8(Files.readAllBytes(path));
			if (content == null)
				fixtures.add(new Fixture(relative, false, Set.of()));
			else
			{
				Set<String> names = new HashSet<>(Placeholders.scenarioNames(content));
				names.retainAll(declared);
				fixtures.add(new Fixture(relative, true, names));
			}
		}
		fixtures.sort(Comparator.comparing(Fixture::path));
		return fixtures;
	}

	/**
	 * Decodes bytes that are valid UTF-8.
	 *
	 * @param bytes the bytes
	 * @return the text, or {@code null} if the bytes are not valid UTF-8
	 */
	private static String utf8(byte[] bytes)
	{
		try
		{
			return StandardCharsets.UTF_8.newDecoder().
				onMalformedInput(CodingErrorAction.REPORT).
				onUnmappableCharacter(CodingErrorAction.REPORT).
				decode(ByteBuffer.wrap(bytes)).toString();
		}
		catch (CharacterCodingException _)
		{
			return null;
		}
	}

	/**
	 * Returns why a test case is not valid for this prompt test.
	 *
	 * @param testCase the test case
	 * @return the problems, or an empty list if the test case is valid
	 * @throws NullPointerException if {@code testCase} is null
	 */
	public List<String> problems(TestCase testCase)
	{
		requireThat(testCase, "testCase").isNotNull();
		Set<String> declared = new LinkedHashSet<>();
		for (VariableDeclaration variable : scenario.variables())
			declared.add(variable.name());
		return problems(declared, scenario.namedInText(), fixtures, testCase);
	}

	/**
	 * Returns why a test case is not valid for a prompt test.
	 *
	 * @param declared the names of the prompt test's variables
	 * @param inText   the variables that its turns, assertion statements and {@code tool:} lines name
	 * @param fixtures its fixtures
	 * @param testCase the test case
	 * @return the problems, or an empty list if the test case is valid
	 */
	static List<String> problems(Set<String> declared, Set<String> inText, List<Fixture> fixtures,
		TestCase testCase)
	{
		List<String> problems = new ArrayList<>();
		for (String name : declared)
		{
			if (!testCase.values().containsKey(name))
				problems.add("the test case gives no value for the variable " + name);
		}
		for (String name : testCase.values().keySet())
		{
			if (!declared.contains(name))
				problems.add("the test case gives a value for " + name + ", which the scenario does not declare");
		}

		Set<String> inNames = new HashSet<>();
		Set<String> inContents = new HashSet<>();
		for (Fixture fixture : fixtures)
		{
			inNames.addAll(Placeholders.scenarioNames(fixture.path()));
			inContents.addAll(fixture.contentNames());
		}
		for (Map.Entry<String, TestValue> entry : testCase.values().entrySet())
		{
			String name = entry.getKey();
			if (!(entry.getValue() instanceof TestValue.Generated) || !declared.contains(name))
				continue;
			if (inText.contains(name))
			{
				problems.add("the generated value of " + name + " appears in the scenario's text, but a generated " +
					"value may appear only in fixture contents");
			}
			if (inNames.contains(name))
			{
				problems.add("the generated value of " + name + " appears in a fixture's name, but a generated value " +
					"may appear only in fixture contents");
			}
			if (!inContents.contains(name))
				problems.add("the generated value of " + name + " appears in no fixture's content");
		}
		for (String name : inNames)
		{
			if (testCase.values().get(name) instanceof TestValue.Text text)
			{
				String segmentProblem = segmentProblem(text.text());
				if (segmentProblem != null)
				{
					problems.add("the value '" + text.text() + "' of " + name + " appears in a fixture's name, but it " +
						segmentProblem);
				}
			}
		}
		if (!problems.isEmpty())
			return problems;

		Map<Fixture, String> targets = substitute(fixtures, testCase);
		for (String target : targets.values())
		{
			for (String segment : target.split("/", -1))
			{
				String segmentProblem = segmentProblem(segment);
				if (segmentProblem != null)
				{
					problems.add("the fixture path '" + target + "' has the segment '" + segment + "', which " +
						segmentProblem);
				}
			}
		}
		Map<String, String> fixtureByTarget = new HashMap<>();
		for (Map.Entry<Fixture, String> entry : targets.entrySet())
		{
			String earlier = fixtureByTarget.putIfAbsent(entry.getValue(), entry.getKey().path());
			if (earlier != null)
			{
				problems.add("the fixtures " + earlier + " and " + entry.getKey().path() + " both land at " +
					entry.getValue());
			}
		}
		for (String target : fixtureByTarget.keySet())
		{
			for (int slash = target.indexOf('/'); slash != -1; slash = target.indexOf('/', slash + 1))
			{
				String parent = target.substring(0, slash);
				String file = fixtureByTarget.get(parent);
				if (file != null)
				{
					problems.add("the fixture " + file + " lands at " + parent + ", which the fixture " +
						fixtureByTarget.get(target) + " needs as a directory");
				}
			}
		}
		return problems;
	}

	/**
	 * Returns why a text is not one valid file name segment on every operating system.
	 *
	 * @param segment the text
	 * @return the problem, phrased to follow "it", or {@code null} if the text is a valid segment
	 */
	private static String segmentProblem(String segment)
	{
		if (segment.isEmpty())
			return "is empty";
		if (segment.equals(".") || segment.equals(".."))
			return "is not a file name";
		for (int i = 0; i < segment.length(); ++i)
		{
			char character = segment.charAt(i);
			if (character < 0x20 || character == 0x7F)
				return "contains a control character";
			if (RESERVED_CHARACTERS.indexOf(character) != -1)
				return "contains '" + character + "', which is not allowed in a file name";
		}
		return null;
	}

	/**
	 * Replaces the placeholders of every fixture's path.
	 *
	 * @param fixtures the fixtures
	 * @param testCase the test case
	 * @return each fixture's path relative to the workspace root, in fixture order
	 */
	static Map<Fixture, String> substitute(List<Fixture> fixtures, TestCase testCase)
	{
		Map<String, String> textByName = new HashMap<>();
		for (Map.Entry<String, TestValue> entry : testCase.values().entrySet())
		{
			if (entry.getValue() instanceof TestValue.Text text)
				textByName.put(entry.getKey(), text.text());
		}
		textByName.put(TestCase.EXPECTED_OUTCOME, testCase.expectedOutcome());
		Map<Fixture, String> targets = new LinkedHashMap<>();
		for (Fixture fixture : fixtures)
			targets.put(fixture, Placeholders.fillScenario(fixture.path(), textByName));
		return targets;
	}

	/**
	 * Returns where each fixture lands in a trial's workspace for a test case.
	 *
	 * @param testCase a valid test case
	 * @return each fixture's path relative to the workspace root, with {@code /} separators, in fixture order
	 * @throws NullPointerException     if {@code testCase} is null
	 * @throws IllegalArgumentException if {@link #problems(TestCase)} rejects the test case
	 */
	public Map<Fixture, String> targets(TestCase testCase)
	{
		List<String> problems = problems(testCase);
		if (!problems.isEmpty())
		{
			throw new IllegalArgumentException("The test case is not valid for the prompt test: " +
				String.join("; ", problems));
		}
		return Collections.unmodifiableMap(substitute(fixtures, testCase));
	}
}
