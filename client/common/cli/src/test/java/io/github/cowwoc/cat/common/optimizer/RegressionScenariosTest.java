package io.github.cowwoc.cat.common.optimizer;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.TemporaryDirectories;
import io.github.cowwoc.cat.common.optimizer.proposal.RegressionScenarios;

import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Audits recorded regression scenarios against their guidance files and storage location.
 */
public final class RegressionScenariosTest
{
	/**
	 * Verifies that the rules module contains neither scenarios nor retired suite manifests.
	 *
	 * @throws IOException if the rules module cannot be listed
	 */
	@Test
	public void scenariosLiveUnderTests() throws IOException
	{
		// Requirement: R-I3
		Path root = project();
		try (Stream<Path> paths = Files.walk(root.resolve("client/common/rules")))
		{
			List<Path> misplaced = paths.filter(Files::isRegularFile).filter(path ->
				path.getFileName().toString().equals("scenario.md") ||
					path.getFileName().toString().equals("suite.json")).toList();
			requireThat(misplaced, "misplaced").isEmpty();
		}
	}

	/**
	 * Verifies that each scenario directory names existing guidance, allowing its fixture subdirectories.
	 *
	 * @throws IOException if the scenario directories cannot be listed
	 */
	@Test
	public void directoriesNameExistingGuidance() throws IOException
	{
		// Requirement: R-I3
		Path root = project();
		Path tests = root.resolve(".cat/tests");
		requireThat(tests, "tests").isDirectory();
		List<Path> directories;
		try (Stream<Path> paths = Files.walk(tests))
		{
			directories = paths.filter(Files::isDirectory).filter(path -> !path.equals(tests)).toList();
		}
		List<Path> owners = directories.stream().filter(path -> hasGuidance(root, tests.relativize(path))).toList();
		requireThat(owners, "owners").isNotEmpty();
		List<Path> orphaned = new ArrayList<>();
		for (Path directory : directories)
		{
			if (owners.stream().noneMatch(owner -> directory.startsWith(owner) || owner.startsWith(directory)))
				orphaned.add(directory);
		}
		requireThat(orphaned, "orphaned").isEmpty();
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
			RegressionScenarios found = RegressionScenarios.read(root, root.resolve("home"));
			requireThat(found.scenarios().stream().map(RegressionScenarios.Scenario::citation).toList(), "citations").
				isEqualTo(List.of(".cat/tests/common/wording/reported/scenario.md"));
			requireThat(found.prompt(), "prompt").contains("Turn: Summarize.\nExpected: Be brief.");
		}
	}

	/**
	 * Indicates whether a scenario directory maps to a project rule or another project guidance file.
	 *
	 * @param root the project root
	 * @param relative the directory relative to the tests root
	 * @return whether a matching guidance file exists
	 */
	private static boolean hasGuidance(Path root, Path relative)
	{
		return Files.isRegularFile(root.resolve(relative + ".md")) ||
			Files.isRegularFile(root.resolve(".cat/rules").resolve(relative + ".md"));
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
