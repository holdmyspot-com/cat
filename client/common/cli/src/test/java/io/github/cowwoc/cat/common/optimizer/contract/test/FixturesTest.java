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
import java.util.Optional;
import java.util.Set;
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
		Path fixtures = Files.createDirectories(directory.resolve("workspace/docs"));
		Files.writeString(fixtures.resolve("{{notes}}"), """
			{{before}}
			Release 4.2 was published on {{expected_outcome}}.
			Deploy with ${{ github.token }} and {{undeclared}}.
			""");
		Files.write(directory.resolve("workspace/logo.png"), BINARY);
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
		return PromptTestFiles.read(json, directory, directory, directory);
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
	 * Creates a project whose test root holds shared files beside a prompt test. The prompt test's {@code workspace/}
	 * holds a file of its own and links to a shared file, a shared directory, a project file and a project directory.
	 *
	 * @param cleanup removes the project
	 * @return the project directory
	 * @throws IOException if a file cannot be written
	 */
	private static Path linkedProject(TemporaryDirectories cleanup) throws IOException
	{
		Path project = cleanup.add(Files.createTempDirectory(Path.of(System.getProperty("cat.work.temp")), "project"));
		Files.createDirectories(project.resolve("src"));
		Files.writeString(project.resolve("src/Main.java"), "class Main {}");
		Files.createDirectories(project.resolve("docs/guide"));
		Files.writeString(project.resolve("docs/guide/intro.md"), "Intro to {{notes}}");
		Path logs = Files.createDirectories(project.resolve(".cat/tests/guidance/shared/logs"));
		Files.writeString(logs.resolveSibling("AGENTS.md"), "Published on {{expected_outcome}}");
		Files.writeString(logs.resolve("{{notes}}"), "{{before}}\nShared");
		Path directory = Files.createDirectories(project.resolve(".cat/tests/guidance/case"));
		Files.writeString(directory.resolve(PromptTestFiles.SCENARIO), SCENARIO);
		Path workspace = Files.createDirectories(directory.resolve(PromptTestFiles.WORKSPACE));
		Files.writeString(workspace.resolve("own.md"), "Own");
		link(workspace.resolve("AGENTS.md"), "../../shared/AGENTS.md");
		link(workspace.resolve("logs"), "../../shared/logs");
		Files.createDirectories(workspace.resolve("src"));
		link(workspace.resolve("src/Main.java"), "../../../../../../src/Main.java");
		link(workspace.resolve("guide"), "../../../../../docs/guide");
		return project;
	}

	/**
	 * Creates a symbolic link.
	 *
	 * @param link   the link
	 * @param target the target, relative to the link's directory
	 * @throws IOException if the link cannot be created
	 */
	private static void link(Path link, String target) throws IOException
	{
		Files.createSymbolicLink(link, Path.of(target));
	}

	/**
	 * Returns the directory of the prompt test that {@link #linkedProject(TemporaryDirectories)} creates.
	 *
	 * @param project the project
	 * @return the prompt test directory
	 */
	private static Path linkedTest(Path project)
	{
		return project.resolve(".cat/tests/guidance/case");
	}

	/**
	 * Verifies that a prompt test's {@code workspace/} files and the files that its links reach, inside or outside the
	 * test root, are its fixtures at their paths below {@code workspace/}, and that copying the fixtures replaces
	 * placeholders in the linked files.
	 *
	 * @throws IOException             if a file cannot be written or read
	 * @throws ScenarioFormatException if the prompt test is refused
	 */
	@Test
	public void readsWorkspaceLinks() throws IOException, ScenarioFormatException
	{
		// Requirement: R-C21, R-W15
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path project = linkedProject(cleanup);
			Path root = project.resolve(".cat/tests");
			PromptTestFiles files = PromptTestFiles.read(json, project, root, linkedTest(project));
			requireThat(files.fixtures(), "fixtures").isEqualTo(List.of(
				new PromptTestFiles.Fixture("AGENTS.md", true, Set.of()),
				new PromptTestFiles.Fixture("guide/intro.md", true, Set.of("notes")),
				new PromptTestFiles.Fixture("logs/{{notes}}", true, Set.of("before")),
				new PromptTestFiles.Fixture("own.md", true, Set.of()),
				new PromptTestFiles.Fixture("src/Main.java", true, Set.of())));
			requireThat(files.source(files.fixtures().get(2)), "shared").
				isEqualTo(root.resolve("guidance/shared/logs/{{notes}}"));
			requireThat(files.source(files.fixtures().get(1)), "project").
				isEqualTo(project.resolve("docs/guide/intro.md"));

			Path workspace = cleanup.add(Files.createTempDirectory(Path.of(System.getProperty("cat.work.temp")),
				"workspace"));
			Fixtures.copy(files, testCase("notes.md"), workspace);
			requireThat(files(workspace), "files").isEqualTo(Set.of("AGENTS.md", "guide/intro.md", "logs/notes.md",
				"own.md", "src/Main.java"));
			requireThat(Files.readString(workspace.resolve("AGENTS.md")), "agents").
				isEqualTo("Published on 2025-03-04");
			requireThat(Files.readString(workspace.resolve("guide/intro.md")), "intro").isEqualTo("Intro to notes.md");
			requireThat(Files.readString(workspace.resolve("logs/notes.md")), "notes").endsWith("\nShared");

			Path bare = Files.createDirectories(root.resolve("guidance/bare"));
			Files.writeString(bare.resolve(PromptTestFiles.SCENARIO), SCENARIO);
			PromptTestFiles none = PromptTestFiles.read(json, project, root, bare);
			requireThat(none.fixtures(), "noFixtures").isEmpty();
		}
	}

	/**
	 * Verifies that a link of a prompt test in a draft directory resolves at the test's path relative to the project:
	 * a target that the draft directory holds is read from it, and any other target from the project.
	 *
	 * @throws IOException             if a file cannot be written or read
	 * @throws ScenarioFormatException if the prompt test is refused
	 */
	@Test
	public void draftLinksResolveDraftFirst() throws IOException, ScenarioFormatException
	{
		// Requirement: R-W15
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path project = linkedProject(cleanup);
			Path draft = project.resolve("cat-draft");
			Path directory = Files.createDirectories(draft.resolve(".cat/tests/guidance/case"));
			Files.writeString(directory.resolve(PromptTestFiles.SCENARIO), SCENARIO);
			Path workspace = Files.createDirectories(directory.resolve(PromptTestFiles.WORKSPACE));
			link(workspace.resolve("AGENTS.md"), "../../shared/AGENTS.md");
			link(workspace.resolve("logs"), "../../shared/logs");
			Path drafted = Files.createDirectories(draft.resolve(".cat/tests/guidance/shared")).resolve("AGENTS.md");
			Files.writeString(drafted, "Drafted on {{expected_outcome}}");

			PromptTestFiles files = PromptTestFiles.readScenario(project, draft, draft.resolve(".cat/tests"),
				directory);

			requireThat(files.fixtures().stream().map(PromptTestFiles.Fixture::path).toList(), "fixtures").
				isEqualTo(List.of("AGENTS.md", "logs/{{notes}}"));
			requireThat(files.source(files.fixtures().get(0)), "fromDraft").isEqualTo(drafted);
			requireThat(files.source(files.fixtures().get(1)), "fromProject").
				isEqualTo(project.resolve(".cat/tests/guidance/shared/logs/{{notes}}"));
		}
	}

	/**
	 * Verifies that a link of {@code workspace/} whose target lies inside {@code workspace/} reaches each trial as a
	 * link with that target, its placeholders replaced, while a link that it reaches and whose own target lies outside
	 * {@code workspace/} reaches the trial as a copy.
	 *
	 * @throws IOException             if a file cannot be written or read
	 * @throws ScenarioFormatException if the prompt test is refused
	 */
	@Test
	public void keepsLinksInsideTheWorkspace() throws IOException, ScenarioFormatException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path project = linkedProject(cleanup);
			Path workspace = linkedTest(project).resolve(PromptTestFiles.WORKSPACE);
			link(workspace.resolve("a"), "b");
			link(workspace.resolve("b"), "../../shared/AGENTS.md");
			Files.createDirectories(workspace.resolve("docs"));
			link(workspace.resolve("docs/up.md"), "../own.md");
			link(workspace.resolve("latest"), "logs/{{notes}}");

			PromptTestFiles files = PromptTestFiles.read(json, project, project.resolve(".cat/tests"),
				linkedTest(project));
			requireThat(files.fixtures().stream().filter(fixture -> fixture.link().isPresent()).toList(), "links").
				isEqualTo(List.of(
					new PromptTestFiles.Fixture("a", false, Set.of(), Optional.of("b")),
					new PromptTestFiles.Fixture("docs/up.md", false, Set.of(), Optional.of("../own.md")),
					new PromptTestFiles.Fixture("latest", false, Set.of(), Optional.of("logs/{{notes}}"))));

			Path trial = cleanup.add(Files.createTempDirectory(Path.of(System.getProperty("cat.work.temp")),
				"workspace"));
			Fixtures.copy(files, testCase("notes.md"), trial);
			requireThat(Files.readSymbolicLink(trial.resolve("a")), "a").isEqualTo(Path.of("b"));
			requireThat(Files.isSymbolicLink(trial.resolve("b")), "bIsLink").isFalse();
			requireThat(Files.readString(trial.resolve("b")), "b").isEqualTo("Published on 2025-03-04");
			requireThat(Files.readString(trial.resolve("a")), "throughA").isEqualTo("Published on 2025-03-04");
			requireThat(Files.readSymbolicLink(trial.resolve("docs/up.md")), "up").isEqualTo(Path.of("../own.md"));
			requireThat(Files.readSymbolicLink(trial.resolve("latest")), "latest").
				isEqualTo(Path.of("logs/notes.md"));
			requireThat(Files.readString(trial.resolve("latest")), "throughLatest").endsWith("\nShared");
		}
	}

	/**
	 * Verifies that links inside {@code workspace/} that lead to each other in a cycle are refused.
	 *
	 * @throws IOException if a file cannot be written or read
	 */
	@Test
	public void refusesCycleInsideTheWorkspace() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path project = linkedProject(cleanup);
			Path workspace = linkedTest(project).resolve(PromptTestFiles.WORKSPACE);
			link(workspace.resolve("c"), "d");
			link(workspace.resolve("d"), "c");
			requireRefused(project, "workspace/c: ", "cycle");
		}
	}

	/**
	 * Verifies that a link is refused when it is absolute, its target is missing or lies outside the project, its
	 * target directory holds it, it forms a cycle, or it reaches a prompt test directory.
	 *
	 * @throws IOException if a file cannot be written or read
	 */
	@Test
	public void refusesBadLinks() throws IOException
	{
		// Requirement: R-W15
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path project = linkedProject(cleanup);
			Path workspace = linkedTest(project).resolve(PromptTestFiles.WORKSPACE);
			Files.createSymbolicLink(workspace.resolve("absolute"), project.resolve("src/Main.java"));
			requireRefused(project, "workspace/absolute: ", "absolute");
			Files.delete(workspace.resolve("absolute"));

			link(workspace.resolve("missing"), "../../shared/none.md");
			requireRefused(project, "workspace/missing: ", "does not exist");
			Files.delete(workspace.resolve("missing"));

			Files.writeString(project.resolveSibling(project.getFileName() + "-outside.md"), "Outside");
			link(workspace.resolve("outside"), "../../../../../../" + project.getFileName() + "-outside.md");
			requireRefused(project, "workspace/outside: ", "outside the project");
			Files.delete(workspace.resolve("outside"));
			Files.delete(project.resolveSibling(project.getFileName() + "-outside.md"));

			Path shared = project.resolve(".cat/tests/guidance/shared");
			link(shared.resolve("logs/up"), "..");
			requireRefused(project, "shared/logs/up: ", "holds the link");
			Files.delete(shared.resolve("logs/up"));

			Files.createDirectories(shared.resolve("a"));
			Files.createDirectories(shared.resolve("b"));
			link(shared.resolve("a/to-b"), "../b");
			link(shared.resolve("b/to-a"), "../a");
			link(workspace.resolve("a"), "../../shared/a");
			requireRefused(project, "shared/b/to-a: ", "cycle");
			Files.delete(workspace.resolve("a"));

			Path other = Files.createDirectories(project.resolve(".cat/tests/guidance/other"));
			Files.writeString(other.resolve(PromptTestFiles.SCENARIO), SCENARIO);
			Files.writeString(other.resolve("notes.md"), "Another test's file");
			link(workspace.resolve("other.md"), "../../other/notes.md");
			requireRefused(project, "workspace/other.md: ", "prompt test");
			Files.delete(workspace.resolve("other.md"));

			link(workspace.resolve("all"), "../../../guidance");
			requireRefused(project, "workspace/all: ", "holds the link");
			Files.delete(workspace.resolve("all"));

			Path nested = Files.createDirectories(shared.resolve("nested/inner"));
			Files.writeString(nested.resolve(PromptTestFiles.SCENARIO), SCENARIO);
			link(workspace.resolve("nested"), "../../shared/nested");
			requireRefused(project, "workspace/nested: ", "prompt test");
		}
	}

	/**
	 * Requires that reading the prompt test of {@link #linkedProject(TemporaryDirectories)} is refused.
	 *
	 * @param project  the project
	 * @param path     text that the message names the refused file with
	 * @param reason   text that the message gives as the reason
	 * @throws IOException if a file cannot be read
	 */
	private void requireRefused(Path project, String path, String reason) throws IOException
	{
		try
		{
			PromptTestFiles.read(json, project, project.resolve(".cat/tests"), linkedTest(project));
			throw new AssertionError("A bad link was accepted: " + path);
		}
		catch (ScenarioFormatException e)
		{
			requireThat(e.getMessage(), "message").contains(path).contains(reason);
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

	/**
	 * Verifies that a problem names the prompt test's files relative to the project, with {@code /} between names.
	 *
	 * @throws IOException if a file cannot be written or read
	 */
	@Test
	public void problemNamesFilesRelativeToProject() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path project = cleanup.add(Files.createTempDirectory("cat-fixtures-"));
			Path root = Files.createDirectories(project.resolve(".cat/tests"));
			Path directory = Files.createDirectories(root.resolve("guidance/case"));
			Files.writeString(directory.resolve(PromptTestFiles.SCENARIO), "not a scenario\n");
			try
			{
				PromptTestFiles.readScenario(project, root, directory);
				throw new AssertionError("A malformed scenario was accepted");
			}
			catch (ScenarioFormatException e)
			{
				requireThat(e.getMessage(), "malformed").startsWith(".cat/tests/guidance/case/scenario.md: ");
			}

			Files.writeString(directory.resolve(PromptTestFiles.SCENARIO), SCENARIO);
			Files.createDirectories(directory.resolve(PromptTestFiles.WORKSPACE));
			link(directory.resolve("workspace/missing.md"), "../../missing.md");
			try
			{
				PromptTestFiles.readScenario(project, root, directory);
				throw new AssertionError("A link to a missing file was accepted");
			}
			catch (ScenarioFormatException e)
			{
				requireThat(e.getMessage(), "missing").
					startsWith(".cat/tests/guidance/case/workspace/missing.md: ");
			}
		}
	}
}
