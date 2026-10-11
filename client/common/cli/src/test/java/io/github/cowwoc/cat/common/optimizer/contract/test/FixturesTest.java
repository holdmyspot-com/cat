package io.github.cowwoc.cat.common.optimizer.contract.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.TemporaryDirectories;
import io.github.cowwoc.cat.common.optimizer.contract.Fixtures;
import io.github.cowwoc.cat.common.optimizer.contract.PromptTestFiles;
import io.github.cowwoc.cat.common.optimizer.contract.ScenarioFormatException;
import io.github.cowwoc.cat.common.optimizer.contract.TestCase;
import io.github.cowwoc.cat.common.optimizer.contract.TestValue;

import org.testng.annotations.Test;

import java.io.IOException;
import java.io.StringWriter;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;
import java.util.stream.Stream;

import tools.jackson.databind.json.JsonMapper;

/**
 * Tests reading a prompt test directory and copying its fixtures into a trial's workspace.
 */
public final class FixturesTest
{
	/**
	 * A scenario whose turn names {@code notes} and whose fixtures may use {@code before}.
	 */
	private static final String SCENARIO = """
		## Variables

		- **notes**: The notes file name
		- **before**: Log text before the entry

		## Scenario

		When was it published? See `docs/{{notes}}`.

		## Assertions

		### answer_given

		- **kind**: guard
		- **description**: The reply states {{expected_outcome}}.
		""";
	/**
	 * Bytes that are not valid UTF-8 and contain a placeholder's spelling.
	 */
	private static final byte[] BINARY = {(byte) 0xFF, (byte) 0xFE, '{', '{', 'n', 'o', 't', 'e', 's', '}', '}', 0};
	private final JsonMapper json = JsonMapper.builder().build();

	/**
	 * Creates the test fixture.
	 */
	public FixturesTest()
	{
	}

	/**
	 * Creates a prompt test directory with a text fixture, a binary fixture and the given extra files.
	 *
	 * @param cleanup removes the directory
	 * @return the prompt test directory
	 * @throws IOException if the directory cannot be written
	 */
	private static Path promptTest(TemporaryDirectories cleanup) throws IOException
	{
		Path directory = cleanup.add(Files.createTempDirectory(Path.of(System.getProperty("cat.work.temp")),
			"prompt-test"));
		Files.writeString(directory.resolve(PromptTestFiles.SCENARIO), SCENARIO);
		Path fixtures = Files.createDirectories(directory.resolve("fixtures/docs"));
		Files.writeString(fixtures.resolve("{{notes}}"), """
			{{before}}
			Release 4.2 was published on {{expected_outcome}}.
			Deploy with ${{ github.token }} and {{undeclared}}.
			""");
		Files.write(directory.resolve("fixtures/logo.png"), BINARY);
		return directory;
	}

	/**
	 * Reads a prompt test directory that is its own test root.
	 *
	 * @param directory the prompt test directory
	 * @return the prompt test's files
	 * @throws IOException             if a file cannot be read
	 * @throws ScenarioFormatException if the prompt test is refused
	 */
	private PromptTestFiles read(Path directory) throws IOException, ScenarioFormatException
	{
		return PromptTestFiles.read(json, directory, directory);
	}

	/**
	 * Returns a test case.
	 *
	 * @param notes the value of {@code notes}
	 * @return the test case, whose {@code before} is 3 generated log lines
	 */
	private static TestCase testCase(String notes)
	{
		Map<String, TestValue> values = new LinkedHashMap<>();
		values.put("notes", new TestValue.Text(notes));
		values.put("before", new TestValue.Generated("log_lines", 1, 11, Map.of("count", 3L)));
		return new TestCase(values, "2025-03-04");
	}

	/**
	 * Returns every regular file below a directory.
	 *
	 * @param root the directory
	 * @return the paths relative to {@code root}, with {@code /} separators
	 * @throws IOException if the directory cannot be listed
	 */
	private static Set<String> files(Path root) throws IOException
	{
		try (Stream<Path> paths = Files.walk(root))
		{
			return Set.copyOf(paths.filter(Files::isRegularFile).
				map(path -> root.relativize(path).toString().replace('\\', '/')).toList());
		}
	}

	/**
	 * Verifies that reading a prompt test directory lists its fixtures, whether each is text and the declared
	 * variables that its content names.
	 *
	 * @throws IOException             if the directory cannot be written or read
	 * @throws ScenarioFormatException if the prompt test is refused
	 */
	@Test
	public void readsFixtures() throws IOException, ScenarioFormatException
	{
		// Requirement: R-C21
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			PromptTestFiles files = read(promptTest(cleanup));
			requireThat(files.fixtures(), "fixtures").isEqualTo(List.of(
				new PromptTestFiles.Fixture("docs/{{notes}}", true, Set.of("before")),
				new PromptTestFiles.Fixture("logo.png", false, Set.of())));
			requireThat(files.testCases(), "testCases").isEmpty();
		}
	}

	/**
	 * Verifies that copying replaces declared placeholders in names and text contents, expands the generated value,
	 * keeps other braces and copies a binary file unchanged.
	 *
	 * @throws IOException             if a file cannot be written or read
	 * @throws ScenarioFormatException if the prompt test is refused
	 */
	@Test
	public void replacesPlaceholders() throws IOException, ScenarioFormatException
	{
		// Requirement: R-C21
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			PromptTestFiles files = read(promptTest(cleanup));
			Path workspace = cleanup.add(Files.createTempDirectory(Path.of(System.getProperty("cat.work.temp")),
				"workspace"));
			Files.writeString(workspace.resolve("README.md"), "Project");
			TestCase testCase = testCase("release-notes.md");
			Fixtures.copy(files, testCase, workspace);

			StringWriter logs = new StringWriter();
			((TestValue.Generated) testCase.values().get("before")).write(logs);
			requireThat(files(workspace), "files").isEqualTo(Set.of("README.md", "docs/release-notes.md",
				"logo.png"));
			requireThat(Files.readString(workspace.resolve("docs/release-notes.md")), "notes").isEqualTo(logs + """

				Release 4.2 was published on 2025-03-04.
				Deploy with ${{ github.token }} and {{undeclared}}.
				""");
			requireThat(Files.readAllBytes(workspace.resolve("logo.png")), "logo").isEqualTo(BINARY);
		}
	}

	/**
	 * Returns a scenario that copies the files of the given fixture patterns.
	 *
	 * @param patterns the patterns, each written as a code span
	 * @return the scenario
	 */
	private static String withPatterns(String... patterns)
	{
		StringJoiner spans = new StringJoiner(", ");
		for (String pattern : patterns)
			spans.add("`" + pattern + "`");
		return "## Fixtures\n\n- **patterns**: " + spans + "\n\n" + SCENARIO;
	}

	/**
	 * Creates a test root that holds a guidance directory with shared fixtures and a prompt test below it with its own
	 * fixtures, and a {@code fixtures/} directory outside the test root.
	 *
	 * @param cleanup removes the directories
	 * @return the test root
	 * @throws IOException if a file cannot be written
	 */
	private static Path sharedFixtures(TemporaryDirectories cleanup) throws IOException
	{
		Path outside = cleanup.add(Files.createTempDirectory(Path.of(System.getProperty("cat.work.temp")),
			"test-root"));
		Files.createDirectories(outside.resolve("fixtures"));
		Files.writeString(outside.resolve("fixtures/outside.md"), "Not a fixture");
		Path root = Files.createDirectories(outside.resolve("tests"));
		Path guidance = Files.createDirectories(root.resolve("guidance"));
		Files.createDirectories(guidance.resolve("fixtures/docs"));
		Files.writeString(guidance.resolve("fixtures/AGENTS.md"), "Published on {{expected_outcome}}");
		Files.writeString(guidance.resolve("fixtures/docs/{{notes}}"), "{{before}}\nFrom the guidance");
		Files.writeString(guidance.resolve("fixtures/docs/glossary.md"), "Not named");
		Path directory = Files.createDirectories(guidance.resolve("case"));
		Files.createDirectories(directory.resolve("fixtures/docs"));
		Files.writeString(directory.resolve("fixtures/docs/{{notes}}"), "{{before}}\nFrom the case");
		Files.writeString(directory.resolve("fixtures/own.md"), "Own");
		return root;
	}

	/**
	 * Verifies that a prompt test copies the files that its fixture patterns name, in list order, so that the later
	 * pattern's file replaces an earlier one at the same workspace path, and that a shared file it does not name is
	 * not copied.
	 *
	 * @throws IOException             if a file cannot be written or read
	 * @throws ScenarioFormatException if the prompt test is refused
	 */
	@Test
	public void copiesPatternsInListOrder() throws IOException, ScenarioFormatException
	{
		// Requirement: R-C21
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = sharedFixtures(cleanup);
			Path directory = root.resolve("guidance/case");
			Files.writeString(directory.resolve(PromptTestFiles.SCENARIO),
				withPatterns("fixtures/**", "../fixtures/{AGENTS.md,docs/\\{\\{notes\\}\\}}"));

			PromptTestFiles files = PromptTestFiles.read(json, root, directory);
			requireThat(files.fixtures(), "fixtures").isEqualTo(List.of(
				new PromptTestFiles.Fixture("AGENTS.md", true, Set.of()),
				new PromptTestFiles.Fixture("docs/{{notes}}", true, Set.of("before")),
				new PromptTestFiles.Fixture("own.md", true, Set.of())));
			Path workspace = cleanup.add(Files.createTempDirectory(Path.of(System.getProperty("cat.work.temp")),
				"workspace"));
			Fixtures.copy(files, testCase("notes.md"), workspace);
			requireThat(files(workspace), "files").isEqualTo(Set.of("AGENTS.md", "docs/notes.md", "own.md"));
			requireThat(Files.readString(workspace.resolve("AGENTS.md")), "agents").
				isEqualTo("Published on 2025-03-04");
			requireThat(Files.readString(workspace.resolve("docs/notes.md")), "notes").endsWith("\nFrom the guidance");

			Files.writeString(directory.resolve(PromptTestFiles.SCENARIO),
				withPatterns("../fixtures/{AGENTS.md,docs/\\{\\{notes\\}\\}}", "fixtures/**"));
			Path reversed = cleanup.add(Files.createTempDirectory(Path.of(System.getProperty("cat.work.temp")),
				"workspace"));
			Fixtures.copy(PromptTestFiles.read(json, root, directory), testCase("notes.md"), reversed);
			requireThat(Files.readString(reversed.resolve("docs/notes.md")), "reversed").endsWith("\nFrom the case");
		}
	}

	/**
	 * Verifies that a fixture pattern that leaves the test root, and one that matches no file, are refused, while a
	 * prompt test without a {@code ## Fixtures} section and without a {@code fixtures/} directory has no fixtures.
	 *
	 * @throws IOException             if a file cannot be written or read
	 * @throws ScenarioFormatException if a valid prompt test is refused
	 */
	@Test
	public void refusesEscapingAndUnmatchedPatterns() throws IOException, ScenarioFormatException
	{
		// Requirement: R-C21
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = sharedFixtures(cleanup);
			Path directory = root.resolve("guidance/case");
			Path scenario = directory.resolve(PromptTestFiles.SCENARIO);
			Files.writeString(scenario, withPatterns("fixtures/**", "../../../fixtures/**"));
			try
			{
				PromptTestFiles.read(json, root, directory);
				throw new AssertionError("A pattern outside the test root was accepted");
			}
			catch (ScenarioFormatException e)
			{
				requireThat(e.getMessage(), "outsideRoot").contains("../../../fixtures/**").contains("test root");
			}

			Files.writeString(scenario, withPatterns("fixtures/**", "../fixtures/missing.md"));
			try
			{
				PromptTestFiles.read(json, root, directory);
				throw new AssertionError("A pattern that matches no file was accepted");
			}
			catch (ScenarioFormatException e)
			{
				requireThat(e.getMessage(), "matchesNothing").contains("../fixtures/missing.md").contains("no file");
			}

			Path bare = Files.createDirectories(root.resolve("guidance/bare"));
			Files.writeString(bare.resolve(PromptTestFiles.SCENARIO), SCENARIO);
			requireThat(PromptTestFiles.read(json, root, bare).fixtures(), "bare").isEmpty();
		}
	}

	/**
	 * Verifies that a fixture whose path exists in the workspace as a file is refused before any fixture is written.
	 *
	 * @throws IOException             if a file cannot be written or read
	 * @throws ScenarioFormatException if the prompt test is refused
	 */
	@Test
	public void refusesCollisionWithStagedSource() throws IOException, ScenarioFormatException
	{
		// Requirement: R-C21
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			PromptTestFiles files = read(promptTest(cleanup));
			Path workspace = cleanup.add(Files.createTempDirectory(Path.of(System.getProperty("cat.work.temp")),
				"workspace"));
			Files.createDirectories(workspace.resolve("docs"));
			Files.writeString(workspace.resolve("docs/notes.md"), "Project notes");
			try
			{
				Fixtures.copy(files, testCase("notes.md"), workspace);
				throw new AssertionError("A colliding fixture was copied");
			}
			catch (FileAlreadyExistsException e)
			{
				requireThat(e.getMessage(), "message").contains("docs/notes.md");
			}
			requireThat(files(workspace), "files").isEqualTo(Set.of("docs/notes.md"));
			requireThat(Files.readString(workspace.resolve("docs/notes.md")), "notes").isEqualTo("Project notes");

			Files.delete(workspace.resolve("docs/notes.md"));
			Files.delete(workspace.resolve("docs"));
			Files.writeString(workspace.resolve("docs"), "A file named like the fixture's directory");
			try
			{
				Fixtures.copy(files, testCase("notes.md"), workspace);
				throw new AssertionError("A fixture below a file was copied");
			}
			catch (FileAlreadyExistsException e)
			{
				requireThat(e.getMessage(), "message").contains("docs");
			}
			requireThat(files(workspace), "afterParent").isEqualTo(Set.of("docs"));
		}
	}

	/**
	 * Verifies that a test case whose value is not a valid file name is refused before any fixture is written.
	 *
	 * @throws IOException             if a file cannot be written or read
	 * @throws ScenarioFormatException if the prompt test is refused
	 */
	@Test
	public void refusesInvalidName() throws IOException, ScenarioFormatException
	{
		// Requirement: R-C21
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			PromptTestFiles files = read(promptTest(cleanup));
			Path workspace = cleanup.add(Files.createTempDirectory(Path.of(System.getProperty("cat.work.temp")),
				"workspace"));
			try
			{
				Fixtures.copy(files, testCase("../escape.md"), workspace);
				throw new AssertionError("An invalid file name was accepted");
			}
			catch (IllegalArgumentException e)
			{
				requireThat(e.getMessage(), "message").contains("../escape.md");
			}
			requireThat(files(workspace), "files").isEmpty();
		}
	}

	/**
	 * Verifies that a values file beside the scenario is read and its test cases validated, and that a malformed
	 * scenario is refused with its path.
	 *
	 * @throws IOException             if a file cannot be written or read
	 * @throws ScenarioFormatException if the prompt test is refused
	 */
	@Test
	public void readsValuesFile() throws IOException, ScenarioFormatException
	{
		// Requirement: R-C21, R-C22
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path directory = promptTest(cleanup);
			Files.writeString(directory.resolve("values.json"), """
				{
				  "format_version": 1,
				  "test_cases": [
				    {
				      "values": {
				        "notes": "notes.md",
				        "before": {"generator": "log_lines", "version": 1, "seed": 11, "count": 3}
				      },
				      "expected_outcome": "2025-03-04"
				    }
				  ]
				}
				""");
			requireThat(read(directory).testCases(), "testCases").
				isEqualTo(List.of(testCase("notes.md")));

			Files.writeString(directory.resolve("values.json"), """
				{"format_version": 1, "test_cases": [{"values": {"notes": "a/b"}, "expected_outcome": "x"}]}
				""");
			try
			{
				read(directory);
				throw new AssertionError("An invalid committed test case was accepted");
			}
			catch (ScenarioFormatException e)
			{
				requireThat(e.getMessage(), "message").contains("values.json");
			}

			Files.delete(directory.resolve("values.json"));
			Files.writeString(directory.resolve(PromptTestFiles.SCENARIO), "## Scenario\n\nHello.\n");
			try
			{
				read(directory);
				throw new AssertionError("A malformed scenario was accepted");
			}
			catch (ScenarioFormatException e)
			{
				requireThat(e.getMessage(), "message").contains(PromptTestFiles.SCENARIO);
			}
		}
	}
}
