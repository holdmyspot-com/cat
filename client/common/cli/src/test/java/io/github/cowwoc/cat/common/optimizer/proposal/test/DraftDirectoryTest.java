package io.github.cowwoc.cat.common.optimizer.proposal.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;

import io.github.cowwoc.cat.common.TemporaryDirectories;
import io.github.cowwoc.cat.common.optimizer.inventory.Sha256;
import io.github.cowwoc.cat.common.optimizer.proposal.DraftDirectory;
import io.github.cowwoc.cat.common.optimizer.proposal.RegressionScenarios;

import org.testng.annotations.Test;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Tests {@link DraftDirectory}.
 */
public final class DraftDirectoryTest
{
	/**
	 * Reads values files.
	 */
	private static final JsonMapper JSON = JsonMapper.builder().build();
	/**
	 * The recorded prompt test of the fixture project that lies in its own directory.
	 */
	private static final Path LONG_DOCUMENT = Path.of(".cat/tests/notes/long_document");
	/**
	 * The scenario of {@link #LONG_DOCUMENT}, which selects its own fixtures and the shared ones.
	 */
	private static final String SCENARIO = """
		# Long Document

		## Variables

		- **topic**: The topic that the reply summarizes

		## Fixtures

		- **patterns**: `fixtures/**`, `../fixtures/**`

		## Scenario

		Summarize {{topic}} from `doc.md`.

		## Assertions

		### summary_given

		- **kind**: guard
		- **description**: The reply summarizes {{topic}}.
		""";
	/**
	 * The values file of {@link #LONG_DOCUMENT}.
	 */
	private static final String VALUES = """
		{"format_version": 1, "test_cases": [{"values": {"topic": "tides"}, "expected_outcome": "A summary"}]}
		""";
	/**
	 * A recorded scenario in an earlier format, a file directly in the target's test directory.
	 */
	private static final String LOOSE = """
		# Old Case

		Ask for a summary of the tides and check that the reply gives one.
		""";

	/**
	 * Creates the test.
	 */
	public DraftDirectoryTest()
	{
	}

	/**
	 * Verifies that seeding copies each recorded prompt test of the target into the draft directory at its test path:
	 * its scenario, values file and own fixtures, and the shared fixtures that its patterns select, while a scenario in
	 * an earlier format becomes the {@code scenario.md} of a directory named after it, and nothing else is copied.
	 *
	 * @throws IOException if a file cannot be read or written
	 */
	@Test
	public void seedsRecordedTestsAtTheirPaths() throws IOException
	{
		try (TemporaryDirectories scope = new TemporaryDirectories())
		{
			Path project = project(scope);
			Path draft = scope.add(Files.createTempDirectory("draft-"));
			RegressionScenarios scenarios = scenarios(project);

			DraftDirectory.seed(scenarios, draft);

			requireThat(DraftDirectory.testDirectories(scenarios), "testDirectories").isEqualTo(Map.of(
				".cat/tests/notes/long_document/scenario.md", LONG_DOCUMENT,
				".cat/tests/notes/Old Case.md", Path.of(".cat/tests/notes/old_case")));
			requireThat(files(draft), "files").isEqualTo(List.of(
				".cat/tests/notes/fixtures/shared.md",
				".cat/tests/notes/long_document/fixtures/doc.md",
				".cat/tests/notes/long_document/scenario.md",
				".cat/tests/notes/long_document/values.json",
				".cat/tests/notes/old_case/scenario.md"));
			requireThat(Files.readString(draft.resolve(LONG_DOCUMENT).resolve("scenario.md")), "scenario").
				isEqualTo(SCENARIO);
			requireThat(Files.readString(draft.resolve(LONG_DOCUMENT).resolve("values.json")), "values").
				isEqualTo(VALUES);
			requireThat(Files.readString(draft.resolve(".cat/tests/notes/old_case/scenario.md")), "loose").
				isEqualTo(LOOSE);
		}
	}

	/**
	 * Verifies that the directory name of a scenario in an earlier format avoids a recorded prompt test directory and
	 * any other existing directory of the project.
	 *
	 * @throws IOException if a file cannot be read or written
	 */
	@Test
	public void convertedScenarioAvoidsTakenNames() throws IOException
	{
		try (TemporaryDirectories scope = new TemporaryDirectories())
		{
			Path project = project(scope);
			Files.writeString(project.resolve(".cat/tests/notes/Long Document.md"), LOOSE);
			Files.writeString(project.resolve(".cat/tests/notes/fixtures.md"), LOOSE);

			Map<String, Path> directories = DraftDirectory.testDirectories(scenarios(project));

			requireThat(directories.get(".cat/tests/notes/Long Document.md"), "longDocument").
				isEqualTo(Path.of(".cat/tests/notes/long_document_2"));
			requireThat(directories.get(".cat/tests/notes/fixtures.md"), "fixtures").
				isEqualTo(Path.of(".cat/tests/notes/fixtures_2"));
		}
	}

	/**
	 * Verifies that a revision's draft directory starts as a copy of the latest proposal's draft directory.
	 *
	 * @throws IOException if a file cannot be read or written
	 */
	@Test
	public void revisionCopiesTheLatestDraft() throws IOException
	{
		try (TemporaryDirectories scope = new TemporaryDirectories())
		{
			Path project = project(scope);
			Path latest = scope.add(Files.createTempDirectory("latest-"));
			DraftDirectory.seed(scenarios(project), latest);
			Path created = latest.resolve(".cat/tests/notes/new_case/scenario.md");
			Files.createDirectories(created.getParent());
			Files.writeString(created, SCENARIO);
			Path revision = scope.add(Files.createTempDirectory("revision-"));

			DraftDirectory.copy(latest, revision);

			requireThat(files(revision), "files").isEqualTo(files(latest));
			requireThat(Files.readString(revision.resolve(".cat/tests/notes/new_case/scenario.md")), "created").
				isEqualTo(SCENARIO);
		}
	}

	/**
	 * Verifies that a test revision's draft directory holds a copy of the prompt test that it revises, with the shared
	 * fixtures that its patterns select, and no other prompt test.
	 *
	 * @throws IOException if a file cannot be read or written
	 */
	@Test
	public void testRevisionCopiesOneTest() throws IOException
	{
		try (TemporaryDirectories scope = new TemporaryDirectories())
		{
			Path project = project(scope);
			Path latest = scope.add(Files.createTempDirectory("latest-"));
			DraftDirectory.seed(scenarios(project), latest);
			Path revision = scope.add(Files.createTempDirectory("revision-"));

			DraftDirectory.copyTest(latest, LONG_DOCUMENT, revision);

			requireThat(files(revision), "files").isEqualTo(List.of(
				".cat/tests/notes/fixtures/shared.md",
				".cat/tests/notes/long_document/fixtures/doc.md",
				".cat/tests/notes/long_document/scenario.md",
				".cat/tests/notes/long_document/values.json"));
		}
	}

	/**
	 * Verifies that a test revision's result becomes a new draft directory beside the stored one: a copy of it in which
	 * the revised prompt test's directory holds exactly the files that the test revision left, while the stored draft
	 * directory stays unchanged.
	 *
	 * @throws IOException if a file cannot be read or written
	 */
	@Test
	public void testRevisionReplacesOneTest() throws IOException
	{
		try (TemporaryDirectories scope = new TemporaryDirectories())
		{
			Path project = project(scope);
			Path heldOut = scope.add(Files.createTempDirectory("held_out-"));
			Path seeded = scope.add(Files.createTempDirectory("seeded-"));
			DraftDirectory.seed(scenarios(project), seeded);
			List<String> confirmedFiles = files(seeded);
			Path stored = DraftDirectory.store(seeded, heldOut);
			Path revision = scope.add(Files.createTempDirectory("revision-"));
			DraftDirectory.copyTest(stored, LONG_DOCUMENT, revision);
			Files.delete(revision.resolve(LONG_DOCUMENT).resolve("fixtures/doc.md"));
			write(revision.resolve(LONG_DOCUMENT).resolve("fixtures/other.md"), "Other notes.\n");

			Path revised = DraftDirectory.revise(stored, LONG_DOCUMENT, revision);

			requireThat(revised.getParent(), "parent").isEqualTo(stored.getParent());
			requireThat(revised, "revised").isNotEqualTo(stored);
			requireThat(files(revised), "files").isEqualTo(List.of(
				".cat/tests/notes/fixtures/shared.md",
				".cat/tests/notes/long_document/fixtures/other.md",
				".cat/tests/notes/long_document/scenario.md",
				".cat/tests/notes/long_document/values.json",
				".cat/tests/notes/old_case/scenario.md"));
			requireThat(files(stored), "stored").isEqualTo(confirmedFiles);
		}
	}

	/**
	 * Verifies that a test revision's draft directory receives the test's shared fixtures outside its directory, and
	 * that the files that the test revision changed there are listed: a changed or removed shared fixture and a new
	 * file, but not a change inside the test's directory.
	 *
	 * @throws IOException if a file cannot be read or written
	 */
	@Test
	public void listsChangesOutsideTheTest() throws IOException
	{
		try (TemporaryDirectories scope = new TemporaryDirectories())
		{
			Path project = project(scope);
			Path confirmed = scope.add(Files.createTempDirectory("confirmed-"));
			DraftDirectory.seed(scenarios(project), confirmed);
			Path revision = scope.add(Files.createTempDirectory("revision-"));
			DraftDirectory.copyTest(confirmed, LONG_DOCUMENT, revision);
			Map<Path, Sha256> received = DraftDirectory.sharedFiles(confirmed, LONG_DOCUMENT);
			requireThat(received.keySet(), "received").
				isEqualTo(Set.of(Path.of(".cat/tests/notes/fixtures/shared.md")));
			requireThat(DraftDirectory.changedOutside(revision, LONG_DOCUMENT, received), "unchanged").isEmpty();

			write(revision.resolve(LONG_DOCUMENT).resolve("fixtures/doc.md"), "Changed.\n");
			write(revision.resolve(".cat/tests/notes/fixtures/shared.md"), "Changed.\n");
			write(revision.resolve(".cat/tests/notes/extra.md"), "New.\n");

			requireThat(DraftDirectory.changedOutside(revision, LONG_DOCUMENT, received), "changed").isEqualTo(List.of(
				Path.of(".cat/tests/notes/extra.md"), Path.of(".cat/tests/notes/fixtures/shared.md")));

			Files.delete(revision.resolve(".cat/tests/notes/fixtures/shared.md"));

			requireThat(DraftDirectory.changedOutside(revision, LONG_DOCUMENT, received), "removed").isEqualTo(List.of(
				Path.of(".cat/tests/notes/extra.md"), Path.of(".cat/tests/notes/fixtures/shared.md")));
		}
	}

	/**
	 * Verifies that storing a draft directory moves it into the held-out directory under a new name, so that a draft
	 * stored earlier stays whole until the caller removes it, and that removing the others keeps only the named one.
	 *
	 * @throws IOException if a file cannot be read or written
	 */
	@Test
	public void storeMovesDraftIntoHeldOut() throws IOException
	{
		try (TemporaryDirectories scope = new TemporaryDirectories())
		{
			Path project = project(scope);
			Path heldOut = scope.add(Files.createTempDirectory("held_out-"));
			Path first = scope.add(Files.createTempDirectory("first-"));
			DraftDirectory.seed(scenarios(project), first);
			List<String> seeded = files(first);
			Path second = scope.add(Files.createTempDirectory("second-"));
			DraftDirectory.copy(first, second);

			Path firstStored = DraftDirectory.store(first, heldOut);
			Path secondStored = DraftDirectory.store(second, heldOut);

			requireThat(Files.exists(first), "firstExists").isFalse();
			requireThat(Files.exists(second), "secondExists").isFalse();
			requireThat(firstStored.startsWith(heldOut), "firstInHeldOut").isTrue();
			requireThat(secondStored, "secondStored").isNotEqualTo(firstStored);
			requireThat(files(firstStored), "firstStoredFiles").isEqualTo(seeded);
			requireThat(files(secondStored), "secondStoredFiles").isEqualTo(seeded);

			DraftDirectory.keepOnly(heldOut, secondStored);

			requireThat(Files.exists(firstStored), "firstStoredExists").isFalse();
			requireThat(files(secondStored), "kept").isEqualTo(seeded);
		}
	}

	/**
	 * Verifies that a draft directory stays where the session left it until CAT stores it, so that a session that
	 * reaches its time limit leaves its files for diagnosis.
	 *
	 * @throws IOException if a file cannot be read or written
	 */
	@Test
	public void unstoredDraftKeepsItsFiles() throws IOException
	{
		try (TemporaryDirectories scope = new TemporaryDirectories())
		{
			Path project = project(scope);
			Path run = scope.add(Files.createTempDirectory("run-"));
			Path draft = run.resolve(DraftDirectory.name(run));
			DraftDirectory.seed(scenarios(project), draft);
			Path written = draft.resolve(".cat/tests/notes/new_case/scenario.md");
			Files.createDirectories(written.getParent());
			Files.writeString(written, SCENARIO);
			Path heldOut = scope.add(Files.createTempDirectory("held_out-"));

			DraftDirectory.keepOnly(heldOut, heldOut.resolve("drafts/1"));

			requireThat(Files.readString(written), "written").isEqualTo(SCENARIO);
			requireThat(files(draft).size(), "files").isEqualTo(6);
		}
	}

	/**
	 * Verifies that a draft directory's name avoids the files of the session's project.
	 *
	 * @throws IOException if a file cannot be written
	 */
	@Test
	public void nameAvoidsProjectFiles() throws IOException
	{
		try (TemporaryDirectories scope = new TemporaryDirectories())
		{
			Path project = scope.add(Files.createTempDirectory("project-"));
			requireThat(DraftDirectory.name(project), "free").isEqualTo("cat-draft");

			Files.writeString(project.resolve("cat-draft"), "a project file");

			requireThat(DraftDirectory.name(project), "taken").isEqualTo("cat-draft-2");
		}
	}

	/**
	 * Creates the fixture project: a rule with a recorded prompt test in its own directory, shared fixtures, and a
	 * scenario in an earlier format.
	 *
	 * @param scope the temporary directories of the test
	 * @return the project directory
	 * @throws IOException if a file cannot be written
	 */
	private static Path project(TemporaryDirectories scope) throws IOException
	{
		Path project = scope.add(Files.createTempDirectory("project-")).toRealPath();
		write(project.resolve(".cat/rules/notes.md"), "Summarize documents whole.\n");
		write(project.resolve(LONG_DOCUMENT).resolve("scenario.md"), SCENARIO);
		write(project.resolve(LONG_DOCUMENT).resolve("values.json"), VALUES);
		write(project.resolve(LONG_DOCUMENT).resolve("fixtures/doc.md"), "The tides rise twice a day.\n");
		write(project.resolve(".cat/tests/notes/fixtures/shared.md"), "Shared notes.\n");
		write(project.resolve(".cat/tests/notes/Old Case.md"), LOOSE);
		return project;
	}

	/**
	 * Writes a file, creating its directory.
	 *
	 * @param file    the file
	 * @param content its content
	 * @throws IOException if the file cannot be written
	 */
	private static void write(Path file, String content) throws IOException
	{
		Files.createDirectories(file.getParent());
		Files.writeString(file, content, UTF_8);
	}

	/**
	 * Reads the recorded scenarios of the fixture project's rule.
	 *
	 * @param project the fixture project
	 * @return the scenarios
	 * @throws IOException if a scenario cannot be read
	 */
	private static RegressionScenarios scenarios(Path project) throws IOException
	{
		return RegressionScenarios.read(JSON, project, project).of(project.resolve(".cat/rules/notes.md"));
	}

	/**
	 * Lists the regular files below a directory.
	 *
	 * @param directory the directory
	 * @return each file relative to {@code directory}, with {@code /} separators, sorted
	 * @throws IOException if the directory cannot be listed
	 */
	private static List<String> files(Path directory) throws IOException
	{
		List<String> files = new ArrayList<>();
		try (Stream<Path> paths = Files.walk(directory))
		{
			for (Path path : paths.filter(Files::isRegularFile).toList())
				files.add(directory.relativize(path).toString().replace('\\', '/'));
		}
		files.sort(null);
		return files;
	}
}
