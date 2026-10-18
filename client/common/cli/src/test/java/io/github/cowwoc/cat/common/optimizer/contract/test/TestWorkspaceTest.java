package io.github.cowwoc.cat.common.optimizer.contract.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.TemporaryDirectories;
import io.github.cowwoc.cat.common.optimizer.contract.AssertionKind;
import io.github.cowwoc.cat.common.optimizer.contract.WorkspaceFile;
import io.github.cowwoc.cat.common.optimizer.contract.PromptTest;
import io.github.cowwoc.cat.common.optimizer.contract.RiskRank;
import io.github.cowwoc.cat.common.optimizer.contract.TestCase;
import io.github.cowwoc.cat.common.optimizer.contract.TestValue;
import io.github.cowwoc.cat.common.optimizer.contract.VariableDeclaration;
import io.github.cowwoc.cat.common.optimizer.contract.TestWorkspace;

import org.testng.annotations.Test;

import java.io.IOException;
import java.io.StringWriter;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Tests the fixture files that a prompt test of a proposal writes into each trial's workspace.
 */
public final class TestWorkspaceTest
{
	/**
	 * The fixture that holds generated log lines and the expected outcome.
	 */
	private static final WorkspaceFile NOTES = new WorkspaceFile("docs/{{notes}}", """
		{{before}}
		Release 4.2 was published on {{expected_outcome}}.
		""");

	/**
	 * Creates the test.
	 */
	public TestWorkspaceTest()
	{
	}

	/**
	 * Returns a prompt test whose turn names {@code notes} and whose fixture holds {@code before}.
	 *
	 * @param turn           the test's only turn
	 * @param workspaceFiles the test's workspace files
	 * @return the prompt test, with one test case from {@link #testCase()}
	 */
	private static PromptTest test(String turn, List<WorkspaceFile> workspaceFiles)
	{
		return new PromptTest("release_date", new RiskRank(1), List.of("r1"), List.of(),
			"The user asks when a release was published.", List.of(turn), Duration.ofMinutes(2),
			"The reply states the publication date.", List.of(),
			List.of(new VariableDeclaration("notes", "The notes file name."),
				new VariableDeclaration("before", "Log lines before the release entry.")), List.of(),
			List.of(testCase()), List.of(ContractFixtures.judged("states_date", AssertionKind.EFFECT,
				"The reply states {{expected_outcome}}.", "r1")), List.of(), workspaceFiles);
	}

	/**
	 * Returns a test case whose {@code before} is 3 generated log lines.
	 *
	 * @return the test case
	 */
	private static TestCase testCase()
	{
		Map<String, TestValue> values = new LinkedHashMap<>();
		values.put("notes", new TestValue.Text("release-notes.md"));
		values.put("before", new TestValue.Generated("log_lines", 1, 11, Map.of("count", 3L)));
		return new TestCase(values, "2025-03-04");
	}

	/**
	 * Verifies that each fixture is written at its path with the test case's values, the generated value's output
	 * and the expected outcome in place of the placeholders.
	 *
	 * @throws IOException if a file cannot be written or read
	 */
	@Test
	public void writesEachFixtureWithTheTestCaseValues() throws IOException
	{
		// Requirement: R-C21
		PromptTest test = test("When was the release in `docs/{{notes}}` published?", List.of(NOTES));
		TestCase testCase = testCase();
		TestWorkspace testWorkspace = TestWorkspace.of(test, testCase);
		requireThat(List.copyOf(testWorkspace.files().keySet()), "paths").isEqualTo(List.of("docs/release-notes.md"));
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path workspace = cleanup.add(Files.createTempDirectory(Path.of(System.getProperty("cat.work.temp")),
				"workspace"));
			testWorkspace.write(workspace);

			StringWriter logs = new StringWriter();
			((TestValue.Generated) testCase.values().get("before")).write(logs);
			requireThat(Files.readString(workspace.resolve("docs/release-notes.md")), "notes").
				isEqualTo(logs + "\nRelease 4.2 was published on 2025-03-04.\n");
		}
	}

	/**
	 * Verifies that a link fixture is written as a link whose target has the test case's values in place of its
	 * placeholders.
	 *
	 * @throws IOException if a file cannot be written or read
	 */
	@Test
	public void writesLinkFixtureAsLink() throws IOException
	{
		WorkspaceFile latest = new WorkspaceFile("latest", "docs/{{notes}}", ".cat/tests/release_date/workspace/latest",
			true);
		PromptTest test = test("When was the release in `latest` published?", List.of(NOTES, latest));
		TestWorkspace testWorkspace = TestWorkspace.of(test, testCase());
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path workspace = cleanup.add(Files.createTempDirectory(Path.of(System.getProperty("cat.work.temp")),
				"workspace"));
			testWorkspace.write(workspace);

			requireThat(Files.readSymbolicLink(workspace.resolve("latest")), "latest").
				isEqualTo(Path.of("docs/release-notes.md"));
			requireThat(Files.readString(workspace.resolve("latest")), "throughLatest").
				endsWith("Release 4.2 was published on 2025-03-04.\n");
		}
	}

	/**
	 * Verifies that a link fixture's target must lie inside the workspace, below its root and outside its Git
	 * directory.
	 */
	@Test
	public void refusesLinkTargetOutsideWorkspace()
	{
		for (String target : List.of("../../notes.md", "../../../notes.md", "/notes.md", "..\\notes.md", "a//b.md",
			"./b.md", "..", "../.git/config"))
		{
			try
			{
				new WorkspaceFile("docs/link.md", target, "", true);
				throw new AssertionError("The link target " + target + " was accepted");
			}
			catch (IllegalArgumentException e)
			{
				requireThat(e.getMessage(), "message").contains(target);
			}
		}
	}

	/**
	 * Verifies that writing refuses a fixture whose path exists already, without replacing it.
	 *
	 * @throws IOException if a file cannot be written or read
	 */
	@Test
	public void refusesAnExistingPath() throws IOException
	{
		// Requirement: R-C21
		PromptTest test = test("When was the release in `docs/{{notes}}` published?", List.of(NOTES));
		TestWorkspace testWorkspace = TestWorkspace.of(test, testCase());
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path workspace = cleanup.add(Files.createTempDirectory(Path.of(System.getProperty("cat.work.temp")),
				"workspace"));
			Files.createDirectories(workspace.resolve("docs"));
			Files.writeString(workspace.resolve("docs/release-notes.md"), "Project notes");
			try
			{
				testWorkspace.write(workspace);
				throw new AssertionError("A fixture replaced an existing file");
			}
			catch (FileAlreadyExistsException e)
			{
				requireThat(e.getMessage(), "message").contains("release-notes.md");
			}
			requireThat(Files.readString(workspace.resolve("docs/release-notes.md")), "notes").
				isEqualTo("Project notes");
		}
	}

	/**
	 * Verifies that a test without workspace files gives a trial nothing to write.
	 */
	@Test
	public void withoutFixturesWritesNothing()
	{
		// Requirement: R-C21
		PromptTest test = new PromptTest("plain", new RiskRank(1), List.of("r1"), List.of(),
			"The user asks for a list.", List.of("List three {{kind}}."), Duration.ofMinutes(2),
			"The answer is a three-item list.", List.of(),
			List.of(new VariableDeclaration("kind", "The kind of item.")), List.of(),
			List.of(ContractFixtures.plainList("colors")), List.of(ContractFixtures.judged("lists_three",
				AssertionKind.EFFECT, "The answer lists three items.", "r1")), List.of(), List.of());
		requireThat(TestWorkspace.of(test, ContractFixtures.plainList("colors")).isEmpty(), "isEmpty").isTrue();
	}

	/**
	 * Verifies that a generated value is refused outside fixture contents, because a turn would carry the whole
	 * generated text.
	 */
	@Test
	public void refusesGeneratedValueInTurn()
	{
		// Requirement: R-C21
		try
		{
			test("Read `docs/{{notes}}`. The log starts with {{before}}.", List.of(NOTES));
			throw new AssertionError("A generated value in a turn was accepted");
		}
		catch (IllegalArgumentException e)
		{
			requireThat(e.getMessage(), "message").contains("before");
		}
	}

	/**
	 * Verifies that a fixture path must stay inside the workspace and outside its Git directory.
	 */
	@Test
	public void refusesPathOutsideWorkspace()
	{
		// Requirement: R-C21
		for (String path : List.of("../notes.md", "docs/../../notes.md", "/notes.md", "docs\\notes.md", "docs//a.md",
			".git/config", ".git"))
		{
			try
			{
				new WorkspaceFile(path, "content");
				throw new AssertionError("The fixture path " + path + " was accepted");
			}
			catch (IllegalArgumentException e)
			{
				requireThat(e.getMessage(), "message").contains(path);
			}
		}
	}
}
