package io.github.cowwoc.cat.common.optimizer.proposal.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.PortablePaths;
import io.github.cowwoc.cat.common.TemporaryDirectories;
import io.github.cowwoc.cat.common.optimizer.contract.FixtureFile;
import io.github.cowwoc.cat.common.optimizer.contract.PromptTestFiles;
import io.github.cowwoc.cat.common.optimizer.proposal.RegressionScenarios;

import org.testng.annotations.Test;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Audits recorded regression scenarios against their guidance files and storage location.
 */
public final class RegressionScenariosTest
{
	/**
	 * Lowercase letters and digits in words joined by single underscores, such as {@code reported_case}.
	 */
	private static final Pattern SNAKE_CASE = Pattern.compile("[a-z0-9]+(_[a-z0-9]+)*");
	/**
	 * Reads values files.
	 */
	private static final JsonMapper JSON = JsonMapper.builder().build();

	/**
	 * Verifies that discovery finds every recorded scenario of the repository, so that each one names existing
	 * guidance.
	 *
	 * @throws IOException if the scenario trees cannot be listed
	 */
	@Test
	public void everyScenarioNamesGuidance() throws IOException
	{
		// Requirement: R-I3
		Path root = project();
		List<String> recorded = new ArrayList<>();
		// Build output is skipped: the build writes into it while this test runs, and it holds no recorded scenario
		Files.walkFileTree(root.resolve("client"), new SimpleFileVisitor<>()
		{
			@Override
			public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes)
			{
				if (directory.getFileName().toString().equals("target"))
					return FileVisitResult.SKIP_SUBTREE;
				return FileVisitResult.CONTINUE;
			}

			@Override
			public FileVisitResult visitFile(Path file, BasicFileAttributes attributes)
			{
				String citation = PortablePaths.text(root.relativize(file));
				if (file.getFileName().toString().equals("scenario.md") && citation.contains("/src/test/resources/"))
					recorded.add(citation);
				return FileVisitResult.CONTINUE;
			}
		});
		Path tests = root.resolve(".cat/tests");
		if (Files.isDirectory(tests))
		{
			try (Stream<Path> paths = Files.walk(tests))
			{
				for (Path file : paths.filter(path -> path.toString().endsWith(".md")).toList())
					recorded.add(PortablePaths.text(root.relativize(file)));
			}
		}
		requireThat(recorded, "recorded").isNotEmpty();
		requireThat(citations(root), "citations").containsAll(recorded);
	}

	/**
	 * Verifies that every recorded scenario of the repository is a prompt test in the current format, so that the
	 * contract author never has to convert one.
	 *
	 * @throws IOException if the scenarios cannot be read
	 */
	@Test
	public void everyScenarioIsCurrent() throws IOException
	{
		// Requirement: R-I3
		Path root = project();
		List<String> refused = new ArrayList<>();
		for (RegressionScenarios.Scenario scenario : RegressionScenarios.read(JSON, root,
			root.resolve("client/target/no-home")).scenarios())
		{
			if (scenario.format() instanceof RegressionScenarios.Earlier(String problem))
				refused.add(scenario.citation() + ": " + problem);
		}
		requireThat(refused, "refused").isEmpty();
	}

	/**
	 * Verifies that every recorded scenario names its case and its assertions in snake_case.
	 *
	 * @throws IOException if the scenarios cannot be read
	 */
	@Test
	public void scenarioIdentifiersAreSnakeCase() throws IOException
	{
		Path root = project();
		List<String> misspelled = new ArrayList<>();
		for (RegressionScenarios.Scenario scenario : RegressionScenarios.read(JSON, root,
			root.resolve("client/target/no-home")).scenarios())
		{
			Path file = Path.of(scenario.citation());
			String name = file.getFileName().toString();
			String caseName;
			if (name.equals("scenario.md"))
				caseName = file.getParent().getFileName().toString();
			else
				caseName = name.substring(0, name.length() - ".md".length());
			if (!SNAKE_CASE.matcher(caseName).matches())
				misspelled.add(scenario.citation() + ": case " + caseName);
			boolean assertions = false;
			for (String line : scenario.content().lines().toList())
			{
				if (line.startsWith("## "))
					assertions = line.equals("## Assertions");
				else if (assertions && line.startsWith("### "))
				{
					String assertion = line.substring("### ".length()).strip();
					if (!SNAKE_CASE.matcher(assertion).matches())
						misspelled.add(scenario.citation() + ": assertion " + assertion);
				}
			}
		}
		requireThat(misspelled, "misspelled").isEmpty();
	}

	/**
	 * Verifies that no scenario of guidance packaged as a module resource lies under {@code .cat/tests}.
	 *
	 * @throws IOException if the scenarios cannot be read
	 */
	@Test
	public void moduleGuidanceScenariosStayInModule() throws IOException
	{
		// Requirement: R-I3
		Path root = project();
		List<String> misplaced = RegressionScenarios.read(JSON, root, root.resolve("client/target/no-home")).scenarios().
			stream().filter(scenario -> scenario.source().toString().contains("/src/main/resources/") &&
				scenario.citation().startsWith(".cat/tests/")).map(RegressionScenarios.Scenario::citation).toList();
		requireThat(misplaced, "misplaced").isEmpty();
	}

	/**
	 * Verifies that discovery reads a module guidance's scenarios from the module's test resources and leaves out the
	 * fixture Markdown beside them.
	 *
	 * @throws IOException if the module tree cannot be created or read
	 */
	@Test
	public void readsModuleScenario() throws IOException
	{
		// Requirement: R-I3
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory(Path.of(System.getProperty("cat.work.temp")),
				"module-scenarios"));
			Path guidance = Files.createDirectories(root.resolve("plugin/src/main/resources/rules/common")).
				resolve("wording.md");
			Files.writeString(guidance, "Be brief.");
			Path scenario = Files.createDirectories(root.resolve("plugin/src/test/resources/common/wording/reported"));
			Files.writeString(scenario.resolve("scenario.md"), "Turn: Summarize.\nExpected: Be brief.");
			Files.writeString(scenario.getParent().resolve("context.md"), "Fixture context");
			RegressionScenarios found = RegressionScenarios.read(JSON, root, root.resolve("home"));
			requireThat(found.scenarios().size(), "scenarios.size()").isEqualTo(1);
			RegressionScenarios.Scenario only = found.scenarios().getFirst();
			requireThat(only.source(), "source").isEqualTo(guidance.normalize());
			requireThat(only.citation(), "citation").
				isEqualTo("plugin/src/test/resources/common/wording/reported/scenario.md");
			requireThat(only.content(), "content").isEqualTo("Turn: Summarize.\nExpected: Be brief.");
		}
	}

	/**
	 * Verifies that a scenario of nested guidance belongs to that guidance alone, not to the guidance that encloses its
	 * directory.
	 *
	 * @throws IOException if the scenario tree cannot be created or read
	 */
	@Test
	public void nestedGuidanceKeepsItsScenarios() throws IOException
	{
		// Requirement: R-I3
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory(Path.of(System.getProperty("cat.work.temp")),
				"nested-scenarios"));
			Path rules = Files.createDirectories(root.resolve(".cat/rules/common/testing"));
			Files.writeString(rules.getParent().resolve("testing.md"), "Test behaviour.");
			Path doubles = rules.resolve("doubles.md");
			Files.writeString(doubles, "Prefer real collaborators.");
			Path scenario = Files.createDirectories(root.resolve(".cat/tests/common/testing/doubles/reported"));
			Files.writeString(scenario.resolve("scenario.md"), "Turn: Mock the clock.");
			RegressionScenarios found = RegressionScenarios.read(JSON, root, root.resolve("home"));
			requireThat(found.scenarios().stream().map(RegressionScenarios.Scenario::source).toList(), "sources").
				isEqualTo(List.of(doubles.normalize()));
		}
	}

	/**
	 * Verifies that discovery includes a scenario beside its fixtures and excludes fixture Markdown.
	 *
	 * @throws IOException if the owned scenario tree cannot be created or read
	 */
	@Test
	public void readsScenarioBesideFixtures() throws IOException
	{
		// Requirement: R-I3
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory(Path.of(System.getProperty("cat.work.temp")),
				"scenario-discovery"));
			Path rules = Files.createDirectories(root.resolve(".cat/rules/common"));
			Files.writeString(rules.resolve("wording.md"), "Be brief.");
			Path scenario = Files.createDirectories(root.resolve(".cat/tests/common/wording/reported"));
			Files.writeString(scenario.resolve("scenario.md"), "Turn: Summarize.\nExpected: Be brief.");
			Files.writeString(scenario.resolve("context.md"), "Fixture context");
			RegressionScenarios found = RegressionScenarios.read(JSON, root, root.resolve("home"));
			requireThat(found.scenarios().stream().map(RegressionScenarios.Scenario::citation).toList(), "citations").
				isEqualTo(List.of(".cat/tests/common/wording/reported/scenario.md"));
			requireThat(found.scenarios().getFirst().content(), "content").
				isEqualTo("Turn: Summarize.\nExpected: Be brief.");
		}
	}

	/**
	 * Verifies that a prompt test directory in the current format is read with its scenario, fixtures and committed
	 * test cases, and that a scenario in an earlier format is kept for conversion with the reason it does not parse;
	 * each text fixture keeps the file that it is read from.
	 *
	 * @throws IOException if the scenario tree cannot be created or read
	 */
	@Test
	public void readsCurrentAndEarlierFormats() throws IOException
	{
		// Requirement: R-I3, R-C20
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory(Path.of(System.getProperty("cat.work.temp")),
				"scenario-formats"));
			Path guidance = Files.createDirectories(root.resolve("plugin/src/main/resources/rules/common")).
				resolve("reading.md");
			Files.writeString(guidance, "Read files whole.");
			Path tests = Files.createDirectories(root.resolve("plugin/src/test/resources/common/reading"));
			Path current = Files.createDirectories(tests.resolve("whole_file_read"));
			Files.writeString(current.resolve("scenario.md"), """
				## Variables

				- **notes**: The notes file name

				## Scenario

				Summarize `{{notes}}`.

				## Assertions

				### summary_given

				- **kind**: effect
				- **description**: The reply summarizes the notes.
				""");
			Files.createDirectories(current.resolve("workspace"));
			Files.writeString(current.resolve("workspace/{{notes}}"), "Release 4.2 shipped.");
			Files.writeString(current.resolve("values.json"), """
				{"format_version": 1, "test_cases": [{"values": {"notes": "a.md"}, "expected_outcome": "4.2"}]}
				""");
			Path earlier = Files.createDirectories(tests.resolve("reported"));
			Files.writeString(earlier.resolve("scenario.md"), "## Scenario\n\n### Request 1\n\nSummarize.\n");

			RegressionScenarios read = RegressionScenarios.read(JSON, root, root.resolve("home"));
			List<RegressionScenarios.Scenario> found = read.scenarios();
			requireThat(found.stream().map(RegressionScenarios.Scenario::citation).toList(), "citations").
				isEqualTo(List.of("plugin/src/test/resources/common/reading/reported/scenario.md",
					"plugin/src/test/resources/common/reading/whole_file_read/scenario.md"));
			if (!(found.get(0).format() instanceof RegressionScenarios.Earlier(String problem)))
				throw new AssertionError("Not an earlier format: " + found.get(0).format());
			requireThat(problem, "problem").contains("## Variables");
			if (!(found.get(1).format() instanceof RegressionScenarios.Current(PromptTestFiles files, var texts)))
				throw new AssertionError("Not the current format: " + found.get(1).format());
			requireThat(files.scenario().turns(), "turns").isEqualTo(List.of("Summarize `{{notes}}`."));
			requireThat(files.fixtures().size(), "fixtures.size()").isEqualTo(1);
			requireThat(files.testCases().size(), "testCases.size()").isEqualTo(1);
			String source = "plugin/src/test/resources/common/reading/whole_file_read/workspace/{{notes}}";
			requireThat(texts, "texts").isEqualTo(List.of(new FixtureFile("{{notes}}", "Release 4.2 shipped.",
				source)));
		}
	}

	/**
	 * Verifies that the contract author receives, with a prompt test, the text fixtures that the links of its
	 * {@code workspace/} reach in shared directories, each with the file that it is copied from, that a test citing the
	 * prompt test twice receives each once, and that a prompt test without a {@code workspace/} receives none of the
	 * files that its ancestors hold.
	 *
	 * @throws IOException if the scenario tree cannot be created or read
	 */
	@Test
	public void readsSharedFixtures() throws IOException
	{
		// Requirement: R-I3, R-C21
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory(Path.of(System.getProperty("cat.work.temp")),
				"shared-fixtures"));
			Path guidance = Files.createDirectories(root.resolve("plugin/src/main/resources/rules/common")).
				resolve("reading.md");
			Files.writeString(guidance, "Read files whole.");
			Path rules = Files.createDirectories(root.resolve(".cat/rules"));
			Files.writeString(rules.resolve("wording.md"), "Be brief.");
			for (Path tests : List.of(root.resolve("plugin/src/test/resources"), root.resolve(".cat/tests")))
			{
				Files.createDirectories(tests.resolve("shared"));
				Files.writeString(tests.resolve("shared/shared.md"), "Shared by every guidance.");
			}
			Path reading = root.resolve("plugin/src/test/resources/common/reading");
			Files.createDirectories(reading.resolve("shared"));
			Files.writeString(reading.resolve("shared/AGENTS.md"), "Shared by the reading tests.");
			String scenario = """
				## Variables

				- **topic**: The topic to summarize

				## Scenario

				Summarize {{topic}}.

				## Assertions

				### summary_given

				- **kind**: effect
				- **description**: The reply summarizes the topic.
				""";
			Path wholeFileRead = Files.createDirectories(reading.resolve("whole_file_read"));
			Files.writeString(wholeFileRead.resolve("scenario.md"), scenario);
			Path workspace = Files.createDirectories(wholeFileRead.resolve("workspace"));
			Files.createSymbolicLink(workspace.resolve("AGENTS.md"), Path.of("../../shared/AGENTS.md"));
			Files.createSymbolicLink(workspace.resolve("shared.md"), Path.of("../../../../shared/shared.md"));
			Path briefReply = Files.createDirectories(root.resolve(".cat/tests/wording/brief_reply"));
			Files.writeString(briefReply.resolve("scenario.md"), scenario);

			RegressionScenarios read = RegressionScenarios.read(JSON, root, root.resolve("home"));
			List<RegressionScenarios.Scenario> found = read.scenarios();
			requireThat(found.size(), "found.size()").isEqualTo(2);
			for (RegressionScenarios.Scenario regression : found)
			{
				if (!(regression.format() instanceof RegressionScenarios.Current(PromptTestFiles _, var texts)))
					throw new AssertionError("Not the current format: " + regression.format());
				List<FixtureFile> expected;
				if (regression.citation().startsWith(".cat/tests/"))
					expected = List.of();
				else
				{
					expected = List.of(new FixtureFile("AGENTS.md", "Shared by the reading tests.",
							"plugin/src/test/resources/common/reading/shared/AGENTS.md"),
						new FixtureFile("shared.md", "Shared by every guidance.",
							"plugin/src/test/resources/shared/shared.md"));
				}
				requireThat(texts, "texts").withContext(regression.citation(), "citation").isEqualTo(expected);
				requireThat(read.recordedFixtures(List.of(regression.citation(), regression.citation())),
					"recordedFixtures").withContext(regression.citation(), "citation").isEqualTo(expected);
			}
		}
	}

	/**
	 * Returns the citation of every scenario that discovery finds in the repository.
	 *
	 * @param root the repository root
	 * @return the citations
	 * @throws IOException if the scenarios cannot be read
	 */
	private static List<String> citations(Path root) throws IOException
	{
		return RegressionScenarios.read(JSON, root, root.resolve("client/target/no-home")).scenarios().stream().
			map(RegressionScenarios.Scenario::citation).toList();
	}

	/**
	 * Returns the repository root supplied by the build.
	 *
	 * @return the repository root
	 */
	private static Path project()
	{
		String client = System.getProperty("cat.client.directory");
		requireThat(client, "cat.client.directory").isNotNull();
		return Path.of(client).toAbsolutePath().normalize().getParent();
	}
}
