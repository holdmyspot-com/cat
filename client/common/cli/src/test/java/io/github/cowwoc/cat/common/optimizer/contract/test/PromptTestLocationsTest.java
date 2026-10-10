package io.github.cowwoc.cat.common.optimizer.contract.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.optimizer.contract.Contract;
import io.github.cowwoc.cat.common.optimizer.contract.PromptTestLocations;
import io.github.cowwoc.cat.common.optimizer.contract.PromptTestLocations.Location;

import org.testng.annotations.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Tests {@link PromptTestLocations}.
 */
public final class PromptTestLocationsTest
{
	/**
	 * The campaign target of the fixtures.
	 */
	private static final Path TARGET = Path.of(".cat/rules/main.md");

	/**
	 * Creates the test.
	 */
	public PromptTestLocationsTest()
	{
	}

	/**
	 * A target's test directory is the one in which the regression scenarios of the target are read: the project rules
	 * map into {@code .cat/tests}, guidance packaged as a module resource maps into the module's test resources
	 * without its top directory, and other guidance maps into {@code .cat/tests} by its project path.
	 */
	@Test
	public void testDirectoryFollowsScenarioLocations()
	{
		// Requirement: R-W11
		requireThat(PromptTestLocations.scenarioDirectory(Path.of(".cat/rules/java/style.md")), "rule").
			isEqualTo(Path.of(".cat/tests/java/style"));
		requireThat(PromptTestLocations.scenarioDirectory(
			Path.of("client/codex/cli/src/main/resources/rules/codex/read-file.md")), "moduleResource").
			isEqualTo(Path.of("client/codex/cli/src/test/resources/codex/read-file"));
		requireThat(PromptTestLocations.scenarioDirectory(Path.of("docs/AGENTS.md")), "otherGuidance").
			isEqualTo(Path.of(".cat/tests/docs/AGENTS"));
	}

	/**
	 * A prompt test that alone cites one scenario of the target's test directory is edited where it lies. A prompt test
	 * that cites nothing, a scenario of other guidance, a scenario that another prompt test also cites, or a scenario
	 * outside the project is created in the target's test directory under its identifier.
	 */
	@Test
	public void editedTestsStayAndOthersAreCreated()
	{
		// Requirement: R-W11
		Contract contract = ContractFixtures.citing(ContractFixtures.contract(), Map.of(
			"formatting", List.of(".cat/tests/main/tone_kept/scenario.md"),
			"plain_list", List.of(".cat/tests/other/list_given/scenario.md")));
		Map<String, Location> locations = PromptTestLocations.of(contract, TARGET);
		requireThat(locations, "locations").isEqualTo(Map.of(
			"formatting", new Location(Path.of(".cat/tests/main/tone_kept"), false),
			"plain_list", new Location(Path.of(".cat/tests/main/plain_list"), true),
			"safety", new Location(Path.of(".cat/tests/main/safety"), true)));

		Contract shared = ContractFixtures.citing(ContractFixtures.contract(), Map.of(
			"formatting", List.of(".cat/tests/main/tone_kept/scenario.md"),
			"plain_list", List.of(".cat/tests/main/tone_kept/scenario.md"),
			"safety", List.of("/home/user/.cat/tests/main/asks_first/scenario.md")));
		requireThat(PromptTestLocations.of(shared, TARGET), "shared").isEqualTo(Map.of(
			"formatting", new Location(Path.of(".cat/tests/main/formatting"), true),
			"plain_list", new Location(Path.of(".cat/tests/main/plain_list"), true),
			"safety", new Location(Path.of(".cat/tests/main/safety"), true)));
	}

	/**
	 * The paths that staging excludes are every prompt test's directory and every scenario that a prompt test cites
	 * inside the project: the directory of a {@code scenario.md}, or the scenario file of an earlier format.
	 */
	@Test
	public void excludedPathsCoverEveryCitedScenario()
	{
		// Requirement: R-W11
		Contract contract = ContractFixtures.citing(ContractFixtures.contract(), Map.of(
			"formatting", List.of(".cat/tests/main/tone_kept/scenario.md"),
			"plain_list", List.of(".cat/tests/other/list_given/scenario.md", ".cat/tests/other/old_list.md"),
			"safety", List.of("/home/user/.cat/tests/main/asks_first/scenario.md")));
		requireThat(PromptTestLocations.excluded(contract, TARGET), "excluded").isEqualTo(Set.of(
			Path.of(".cat/tests/main/tone_kept"), Path.of(".cat/tests/main/plain_list"),
			Path.of(".cat/tests/main/safety"), Path.of(".cat/tests/other/list_given"),
			Path.of(".cat/tests/other/old_list.md")));
	}
}
