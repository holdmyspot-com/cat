package io.github.cowwoc.cat.common.optimizer.proposal.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.TemporaryDirectories;
import io.github.cowwoc.cat.common.optimizer.contract.FixtureFile;
import io.github.cowwoc.cat.common.optimizer.contract.PromptTestFiles;
import io.github.cowwoc.cat.common.optimizer.proposal.RegressionScenarios;

import org.testng.annotations.Test;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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
		try (Stream<Path> paths = Files.walk(root.resolve("client")))
		{
			for (Path file : paths.filter(path -> path.getFileName().toString().equals("scenario.md")).toList())
			{
				String citation = root.relativize(file).toString();
				if (citation.contains("/src/test/resources/"))
					recorded.add(citation);
			}
		}
		Path tests = root.resolve(".cat/tests");
		if (Files.isDirectory(tests))
		{
			try (Stream<Path> paths = Files.walk(tests))
			{
				for (Path file : paths.filter(path -> path.toString().endsWith(".md")).toList())
					recorded.add(root.relativize(file).toString());
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
			requireThat(found.prompt(), "prompt").contains("Turn: Summarize.\nExpected: Be brief.");
		}
	}

	/**
	 * Verifies that a prompt test directory in the current format is read with its scenario, fixtures and committed
	 * test cases, and that a scenario in an earlier format is kept for conversion with the reason it does not parse.
	 * The contract author receives each scenario's format and the content of each text fixture.
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

				| Name | Description |
				|---|---|
				| notes | The notes file name |

				## Scenario

				Summarize `{{notes}}`.

				## Assertions

				### summary_given

				kind: effect

				The reply summarizes the notes.
				""");
			Files.createDirectories(current.resolve("fixtures"));
			Files.writeString(current.resolve("fixtures/{{notes}}"), "Release 4.2 shipped.");
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
			requireThat(texts, "texts").isEqualTo(List.of(new FixtureFile("{{notes}}", "Release 4.2 shipped.")));
			requireThat(read.prompt(), "prompt").
				contains("Citation: plugin/src/test/resources/common/reading/reported/scenario.md\n" +
					"Format: earlier, which you convert: ").
				contains("Citation: plugin/src/test/resources/common/reading/whole_file_read/scenario.md\n" +
					"Format: current\n").
				contains("Fixture `{{notes}}`:\n\n```\nRelease 4.2 shipped.\n```\n");
		}
	}

	/**
	 * Verifies that the contract author receives, with a prompt test, the text fixtures that it inherits from the
	 * {@code fixtures/} directories between its test root and its own directory, whether the test root is a module's
	 * test resources or {@code .cat/tests}.
	 *
	 * @throws IOException if the scenario tree cannot be created or read
	 */
	@Test
	public void readsInheritedFixtures() throws IOException
	{
		// Requirement: R-I3, R-C21
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory(Path.of(System.getProperty("cat.work.temp")),
				"inherited-fixtures"));
			Path guidance = Files.createDirectories(root.resolve("plugin/src/main/resources/rules/common")).
				resolve("reading.md");
			Files.writeString(guidance, "Read files whole.");
			Path rules = Files.createDirectories(root.resolve(".cat/rules"));
			Files.writeString(rules.resolve("wording.md"), "Be brief.");
			for (Path tests : List.of(root.resolve("plugin/src/test/resources"), root.resolve(".cat/tests")))
			{
				Files.createDirectories(tests.resolve("fixtures"));
				Files.writeString(tests.resolve("fixtures/shared.md"), "Shared by every guidance.");
			}
			Path reading = root.resolve("plugin/src/test/resources/common/reading");
			Files.createDirectories(reading.resolve("fixtures"));
			Files.writeString(reading.resolve("fixtures/AGENTS.md"), "Shared by the reading tests.");
			Path wording = root.resolve(".cat/tests/wording");
			for (Path directory : List.of(reading.resolve("whole_file_read"), wording.resolve("brief_reply")))
			{
				Files.createDirectories(directory);
				Files.writeString(directory.resolve("scenario.md"), """
					## Variables

					| Name | Description |
					|---|---|
					| topic | The topic to summarize |

					## Scenario

					Summarize {{topic}}.

					## Assertions

					### summary_given

					kind: effect

					The reply summarizes the topic.
					""");
			}

			List<RegressionScenarios.Scenario> found = RegressionScenarios.read(JSON, root, root.resolve("home")).
				scenarios();
			requireThat(found.size(), "found.size()").isEqualTo(2);
			for (RegressionScenarios.Scenario scenario : found)
			{
				if (!(scenario.format() instanceof RegressionScenarios.Current(PromptTestFiles _, var texts)))
					throw new AssertionError("Not the current format: " + scenario.format());
				List<FixtureFile> expected;
				if (scenario.citation().startsWith(".cat/tests/"))
					expected = List.of(new FixtureFile("shared.md", "Shared by every guidance."));
				else
				{
					expected = List.of(new FixtureFile("AGENTS.md", "Shared by the reading tests."),
						new FixtureFile("shared.md", "Shared by every guidance."));
				}
				requireThat(texts, "texts").withContext(scenario.citation(), "citation").isEqualTo(expected);
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
