package io.github.cowwoc.cat.common.optimizer.contract;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.PortablePaths;
import java.io.BufferedReader;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import tools.jackson.databind.json.JsonMapper;

/**
 * A prompt test directory: its {@code scenario.md}, the files and links of its {@value #WORKSPACE} directory and the
 * test cases of its values file.
 *
 * @param root      the test root, which holds the prompt test directory or is that directory
 * @param directory the prompt test directory
 * @param scenario  the parsed scenario
 * @param fixtures  the fixture files, sorted by path
 * @param sources   the file that each fixture is copied from, by the fixture's path
 * @param testCases the test cases of the values file, or an empty list if the directory has none
 */
public record PromptTestFiles(Path root, Path directory, Scenario scenario, List<PromptTestFiles.Fixture> fixtures,
	Map<String, Path> sources, List<TestCase> testCases)
{
	/**
	 * The scenario's file name.
	 */
	public static final String SCENARIO = "scenario.md";
	/**
	 * The name of the directory whose contents each trial's workspace root receives.
	 */
	public static final String WORKSPACE = "workspace";
	/**
	 * The characters that a file name segment may not contain on some operating system.
	 */
	private static final String RESERVED_CHARACTERS = "/\\:*?\"<>|";

	/**
	 * One entry that each trial's workspace receives: a file of {@value #WORKSPACE}, a project file that a link there
	 * reaches, or a link of {@value #WORKSPACE} whose target lies inside {@value #WORKSPACE}.
	 *
	 * @param path         the path relative to {@value #WORKSPACE}, with {@code /} separators, which may contain
	 *                     placeholders
	 * @param text         {@code true} if the content is valid UTF-8, so that its placeholders are replaced;
	 *                     {@code false} if it is copied unchanged or the entry is a link
	 * @param contentNames the declared variables that the content names; empty if {@code text} is {@code false}
	 * @param link         the link's target relative to the link's directory, with {@code /} separators, which may
	 *                     contain placeholders; empty if the entry is a file
	 */
	public record Fixture(String path, boolean text, Set<String> contentNames, Optional<String> link)
	{
		/**
		 * Creates a fixture.
		 *
		 * @param path         the path relative to {@value #WORKSPACE}, with {@code /} separators
		 * @param text         {@code true} if the content is valid UTF-8
		 * @param contentNames the declared variables that the content names
		 * @param link         the link's target relative to the link's directory, or empty if the entry is a file
		 * @throws NullPointerException     if any of the arguments are null
		 * @throws IllegalArgumentException if {@code path} is blank, {@code contentNames} is not empty when
		 *                                  {@code text} is {@code false}, {@code text} is {@code true} for a link, or
		 *                                  {@code link} is blank
		 */
		public Fixture
		{
			requireThat(path, "path").isNotBlank();
			requireThat(contentNames, "contentNames").isNotNull();
			requireThat(link, "link").isNotNull();
			if (!text)
				requireThat(contentNames, "contentNames").isEmpty();
			if (link.isPresent())
			{
				requireThat(text, "text").isFalse();
				requireThat(link.get(), "link").isNotBlank();
			}
			contentNames = Set.copyOf(contentNames);
		}

		/**
		 * Creates a fixture for a file.
		 *
		 * @param path         the path relative to {@value #WORKSPACE}, with {@code /} separators
		 * @param text         {@code true} if the content is valid UTF-8
		 * @param contentNames the declared variables that the content names
		 * @throws NullPointerException     if {@code path} or {@code contentNames} are null
		 * @throws IllegalArgumentException if {@code path} is blank, or {@code contentNames} is not empty when
		 *                                  {@code text} is {@code false}
		 */
		public Fixture(String path, boolean text, Set<String> contentNames)
		{
			this(path, text, contentNames, Optional.empty());
		}
	}

	/**
	 * Creates the files of a prompt test.
	 *
	 * @param root      the test root, which holds the prompt test directory or is that directory
	 * @param directory the prompt test directory
	 * @param scenario  the parsed scenario
	 * @param fixtures  the fixture files, sorted by path
	 * @param sources   the file that each fixture is copied from, by the fixture's path
	 * @param testCases the test cases of the values file, or an empty list if the directory has none
	 * @throws NullPointerException     if any of the arguments are null
	 * @throws IllegalArgumentException if {@code directory} does not lie at or below {@code root}, or {@code sources}
	 *                                  does not name exactly the fixtures' paths
	 */
	public PromptTestFiles
	{
		requireThat(root, "root").isNotNull();
		requireThat(directory, "directory").isNotNull();
		if (!directory.startsWith(root))
			throw new IllegalArgumentException("directory must lie at or below root.\nroot: " + root +
				"\ndirectory: " + directory);
		requireThat(scenario, "scenario").isNotNull();
		fixtures = List.copyOf(fixtures);
		sources = Map.copyOf(sources);
		Set<String> paths = new HashSet<>();
		for (Fixture fixture : fixtures)
			paths.add(fixture.path());
		requireThat(sources.keySet(), "sources.keySet()").isEqualTo(paths, "paths");
		testCases = List.copyOf(testCases);
	}

	/**
	 * Reads a prompt test directory of the project.
	 * <p>
	 * The prompt test's fixtures are the files of its {@value #WORKSPACE} directory and the project files that its
	 * symbolic links reach, each at its path below {@value #WORKSPACE}.
	 *
	 * @param json      the JSON mapper that reads the values file
	 * @param project   the directory that problems name files relative to, with {@code /} between names
	 * @param root      the test root, which holds the prompt test directory or is that directory
	 * @param directory the prompt test directory
	 * @return the prompt test's files
	 * @throws NullPointerException     if any of the arguments are null
	 * @throws IllegalArgumentException if {@code directory} does not lie at or below {@code root}
	 * @throws IOException              if a file cannot be read
	 * @throws ScenarioFormatException  if the scenario or the values file does not follow the format,
	 *                                  {@value #WORKSPACE} holds a special file or a link that is absolute, whose
	 *                                  target is missing or lies outside the project, whose target directory holds
	 *                                  the link, that forms a cycle or that reaches a prompt test directory, or a
	 *                                  committed test case is not valid for the prompt test
	 */
	public static PromptTestFiles read(JsonMapper json, Path project, Path root, Path directory)
		throws IOException, ScenarioFormatException
	{
		requireThat(json, "json").isNotNull();
		PromptTestFiles files = readScenario(project, root, directory);
		Path valuesFile = directory.resolve(ValuesFile.NAME);
		if (!Files.exists(valuesFile, LinkOption.NOFOLLOW_LINKS))
			return files;
		List<TestCase> testCases;
		try (BufferedReader in = Files.newBufferedReader(valuesFile))
		{
			testCases = ValuesFile.read(json, in, display(project, valuesFile));
		}
		for (int i = 0; i < testCases.size(); ++i)
		{
			List<String> problems = files.problems(testCases.get(i));
			if (!problems.isEmpty())
			{
				throw new ScenarioFormatException(display(project, valuesFile) + ": test case " + (i + 1) +
					" is not valid for the prompt test: " + String.join("; ", problems));
			}
		}
		return new PromptTestFiles(root, directory, files.scenario(), files.fixtures(), files.sources(), testCases);
	}

	/**
	 * Reads the scenario and the {@value #WORKSPACE} directory of a prompt test directory of the project, leaving out
	 * its values file, whose test cases the caller reads.
	 *
	 * @param project   the project, which problems name files relative to, with {@code /} between names
	 * @param root      the test root, which holds the prompt test directory or is that directory
	 * @param directory the prompt test directory
	 * @return the prompt test's files, without test cases
	 * @throws NullPointerException     if any of the arguments are null
	 * @throws IllegalArgumentException if {@code directory} does not lie at or below {@code root}, or {@code root}
	 *                                  does not lie at or below {@code project}
	 * @throws IOException              if a file cannot be read
	 * @throws ScenarioFormatException  if the scenario does not follow the format, or {@value #WORKSPACE} holds a
	 *                                  special file or a link that is refused
	 * @see #read(JsonMapper, Path, Path, Path)
	 */
	public static PromptTestFiles readScenario(Path project, Path root, Path directory)
		throws IOException, ScenarioFormatException
	{
		return readScenario(project, project, root, directory);
	}

	/**
	 * Reads the scenario and the {@value #WORKSPACE} directory of a prompt test directory, leaving out its values file,
	 * whose test cases the caller reads. The prompt test lies in a tree that holds files at their paths relative to the
	 * project, such as a draft directory. A link resolves at the path where it lies relative to the tree: a target that
	 * the tree holds is read from it, and any other target from the project.
	 *
	 * @param project   the project, which problems name files relative to, with {@code /} between names
	 * @param tree      the directory that holds the prompt test at its path relative to the project: the project or a
	 *                  draft directory
	 * @param root      the test root, which holds the prompt test directory or is that directory
	 * @param directory the prompt test directory
	 * @return the prompt test's files, without test cases
	 * @throws NullPointerException     if any of the arguments are null
	 * @throws IllegalArgumentException if {@code directory} does not lie at or below {@code root}, or {@code root}
	 *                                  does not lie at or below {@code tree}
	 * @throws IOException              if a file cannot be read
	 * @throws ScenarioFormatException  if the scenario does not follow the format, or {@value #WORKSPACE} holds a
	 *                                  special file or a link that is refused
	 * @see #read(JsonMapper, Path, Path, Path)
	 */
	public static PromptTestFiles readScenario(Path project, Path tree, Path root, Path directory)
		throws IOException, ScenarioFormatException
	{
		requireThat(project, "project").isNotNull();
		requireThat(tree, "tree").isNotNull();
		requireThat(root, "root").isNotNull();
		requireThat(directory, "directory").isNotNull();
		if (!directory.startsWith(root))
			throw new IllegalArgumentException("directory must lie at or below root.\nroot: " + root +
				"\ndirectory: " + directory);
		if (!root.startsWith(tree))
			throw new IllegalArgumentException("root must lie at or below tree.\ntree: " + tree + "\nroot: " + root);
		Path scenarioFile = directory.resolve(SCENARIO);
		String scenarioName = display(project, scenarioFile);
		Scenario scenario = new ScenarioParser(scenarioName + ": ", Files.readString(scenarioFile)).parse();
		Set<String> declared = new HashSet<>();
		for (VariableDeclaration variable : scenario.variables())
			declared.add(variable.name());

		WorkspaceReader reader = new WorkspaceReader(project, tree, root, declared);
		reader.read(directory);
		return new PromptTestFiles(root, directory, scenario, reader.fixtures(), reader.sources(), List.of());
	}

	/**
	 * Returns the files and directories inside a test root that the symbolic links of a prompt test's
	 * {@value #WORKSPACE} directory reach, which staging leaves out of every trial so that only the prompt test's
	 * trials receive them.
	 *
	 * @param project   the project
	 * @param tree      the directory that holds the prompt test at its path relative to the project: the project or a
	 *                  draft directory
	 * @param directory the prompt test directory, relative to the project
	 * @return the targets, relative to the project, or an empty set if the directory lies in no test root or has no
	 *   {@value #WORKSPACE} directory
	 * @throws NullPointerException     if any of the arguments are null
	 * @throws IllegalArgumentException if {@code directory} is absolute
	 * @throws IOException              if a file cannot be read
	 * @throws ScenarioFormatException  if {@value #WORKSPACE} holds a special file or a link that is refused
	 */
	public static Set<Path> linkedTargets(Path project, Path tree, Path directory)
		throws IOException, ScenarioFormatException
	{
		requireThat(project, "project").isNotNull();
		requireThat(tree, "tree").isNotNull();
		requireThat(directory.isAbsolute(), "directory.isAbsolute()").isFalse();
		Path normalized = directory.normalize();
		Optional<Path> root = PromptTestLocations.rootOf(normalized);
		if (root.isEmpty())
			return Set.of();
		WorkspaceReader reader = new WorkspaceReader(project, tree, tree.resolve(root.get()), Set.of());
		reader.read(tree.resolve(normalized));
		return reader.linkedTargets();
	}

	/**
	 * Returns the name of a file in a problem.
	 *
	 * @param project the directory that problems name files relative to
	 * @param file    the file
	 * @return {@code file} relative to {@code project} with {@code /} between names if it lies below the project,
	 *   otherwise {@code file} as the platform writes it
	 */
	static String display(Path project, Path file)
	{
		if (file.startsWith(project))
			return PortablePaths.text(project.relativize(file));
		return file.toString();
	}

	/**
	 * Returns the file that a fixture is copied from.
	 *
	 * @param fixture one of this prompt test's fixtures
	 * @return the file
	 * @throws NullPointerException     if {@code fixture} is null
	 * @throws IllegalArgumentException if {@code fixture} is not one of this prompt test's fixtures
	 */
	public Path source(Fixture fixture)
	{
		requireThat(fixture, "fixture").isNotNull();
		if (!fixtures.contains(fixture))
			throw new IllegalArgumentException("The fixture is not one of this prompt test's: " + fixture.path());
		return sources.get(fixture.path());
	}

	/**
	 * Decodes bytes that are valid UTF-8.
	 *
	 * @param bytes the bytes
	 * @return the text, or {@code null} if the bytes are not valid UTF-8
	 */
	static String utf8(byte[] bytes)
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
	 * @param inText   the variables that its turns, assertion statements and assertions' {@code tool} values name
	 * @param fixtures  its fixtures
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
			fixture.link().ifPresent(link -> inNames.addAll(Placeholders.scenarioNames(link)));
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
		Map<String, String> textByName = textByName(testCase.values(), testCase.expectedOutcome());
		for (Fixture fixture : fixtures)
		{
			if (fixture.link().isEmpty())
				continue;
			String link = Placeholders.fillScenario(fixture.link().get(), textByName);
			for (String segment : link.split("/", -1))
			{
				if (segment.equals(".."))
					continue;
				String segmentProblem = segmentProblem(segment);
				if (segmentProblem != null)
				{
					problems.add("the target '" + link + "' of the link " + fixture.path() + " has the segment '" +
						segment + "', which " + segmentProblem);
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
	 * @param fixtures  the fixtures
	 * @param testCase the test case
	 * @return each fixture's path relative to the workspace root, in fixture order
	 */
	static Map<Fixture, String> substitute(List<Fixture> fixtures, TestCase testCase)
	{
		Map<String, String> textByName = textByName(testCase.values(), testCase.expectedOutcome());
		Map<Fixture, String> targets = new LinkedHashMap<>();
		for (Fixture fixture : fixtures)
			targets.put(fixture, Placeholders.fillScenario(fixture.path(), textByName));
		return targets;
	}

	/**
	 * Returns the text that replaces each placeholder of a fixture's path or link target.
	 *
	 * @param values          a test case's value of each variable, by name
	 * @param expectedOutcome the test case's expected outcome
	 * @return the value of each variable whose value is text, and the expected outcome, by placeholder name
	 */
	static Map<String, String> textByName(Map<String, TestValue> values, String expectedOutcome)
	{
		Map<String, String> textByName = new HashMap<>();
		for (Map.Entry<String, TestValue> entry : values.entrySet())
		{
			if (entry.getValue() instanceof TestValue.Text text)
				textByName.put(entry.getKey(), text.text());
		}
		textByName.put(TestCase.EXPECTED_OUTCOME, expectedOutcome);
		return textByName;
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
