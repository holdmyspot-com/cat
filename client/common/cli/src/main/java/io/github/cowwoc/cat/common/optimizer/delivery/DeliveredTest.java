package io.github.cowwoc.cat.common.optimizer.delivery;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.PortablePaths;
import io.github.cowwoc.cat.common.optimizer.contract.AddedTestCase;
import io.github.cowwoc.cat.common.optimizer.contract.AddedTestCases;
import io.github.cowwoc.cat.common.optimizer.contract.Contract;
import io.github.cowwoc.cat.common.optimizer.contract.FixtureFile;
import io.github.cowwoc.cat.common.optimizer.contract.PromptTest;
import io.github.cowwoc.cat.common.optimizer.contract.PromptTestFiles;
import io.github.cowwoc.cat.common.optimizer.contract.PromptTestLocations;
import io.github.cowwoc.cat.common.optimizer.contract.PromptTestLocations.Location;
import io.github.cowwoc.cat.common.optimizer.contract.Scenario;
import io.github.cowwoc.cat.common.optimizer.contract.ScenarioFormatException;
import io.github.cowwoc.cat.common.optimizer.contract.TestCase;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * A confirmed prompt test that delivery writes as a regression scenario.
 *
 * @param id          the prompt test's identifier
 * @param location    the prompt test's directory, relative to the project, and whether delivery creates it
 * @param scenario    the prompt test's scenario
 * @param designGoals what a compliant response does, which a created scenario states as its design goals
 * @param testCases   every test case of the campaign for the prompt test, each once
 * @param fixtures    the files that the prompt test writes into each trial's workspace, as the contract author wrote
 *                    them; for a prompt test with a draft directory, each names its file there, which is also its path
 *                    relative to the project
 * @param draft       the {@value PromptTestFiles#SCENARIO} that the contract author wrote in the prompt test's draft
 *                    directory, which delivery writes as it is, or empty for a prompt test that the contract author
 *                    wrote into the proposal itself
 */
public record DeliveredTest(String id, Location location, Scenario scenario, String designGoals,
	List<TestCase> testCases, List<FixtureFile> fixtures, Optional<String> draft)
{
	/**
	 * Creates a delivered test that the contract author wrote into the proposal itself.
	 *
	 * @param id          the prompt test's identifier
	 * @param location    the prompt test's directory, relative to the project, and whether delivery creates it
	 * @param scenario    the prompt test's scenario
	 * @param designGoals what a compliant response does, which a created scenario states as its design goals
	 * @param testCases   every test case of the campaign for the prompt test, each once
	 * @param fixtures    the files that the prompt test writes into each trial's workspace
	 * @throws NullPointerException     if any of the arguments are null
	 * @throws IllegalArgumentException if {@code id} is blank, {@code designGoals} is blank or {@code testCases} is
	 *                                  empty
	 */
	public DeliveredTest(String id, Location location, Scenario scenario, String designGoals,
		List<TestCase> testCases, List<FixtureFile> fixtures)
	{
		this(id, location, scenario, designGoals, testCases, fixtures, Optional.empty());
	}

	/**
	 * Creates a delivered test.
	 *
	 * @param id          the prompt test's identifier
	 * @param location    the prompt test's directory, relative to the project, and whether delivery creates it
	 * @param scenario    the prompt test's scenario
	 * @param designGoals what a compliant response does, which a created scenario states as its design goals
	 * @param testCases   every test case of the campaign for the prompt test, each once
	 * @param fixtures    the files that the prompt test writes into each trial's workspace; for a prompt test with a
	 *                    draft directory, each names its file there
	 * @param draft       the {@value PromptTestFiles#SCENARIO} that the contract author wrote in the prompt test's draft
	 *                    directory, or empty for a prompt test that the contract author wrote into the proposal itself
	 * @throws NullPointerException     if any of the arguments are null
	 * @throws IllegalArgumentException if {@code id} is blank, {@code designGoals} is blank or {@code testCases} is
	 *                                  empty
	 */
	public DeliveredTest
	{
		requireThat(id, "id").isNotBlank();
		requireThat(location, "location").isNotNull();
		requireThat(scenario, "scenario").isNotNull();
		requireThat(designGoals, "designGoals").isNotBlank();
		requireThat(testCases, "testCases").isNotEmpty();
		testCases = List.copyOf(testCases);
		requireThat(fixtures, "fixtures").isNotNull();
		fixtures = List.copyOf(fixtures);
		requireThat(draft, "draft").isNotNull();
	}

	/**
	 * Returns the {@value PromptTestFiles#SCENARIO} that delivery writes for this prompt test: the file of its draft
	 * directory, if it has one; otherwise the recorded file rewritten when delivery edits a recorded scenario that the
	 * scenario format reads, and a new file otherwise.
	 *
	 * @param recorded the content of the {@value PromptTestFiles#SCENARIO} recorded in this test's directory, or null
	 *                 if there is none
	 * @param appended the fixture patterns that delivery appends to the recorded file's patterns, in order; a new file
	 *                 selects only its own {@code fixtures} directory
	 * @return the file's content
	 * @throws NullPointerException if {@code appended} is null
	 */
	public String scenarioFile(String recorded, List<String> appended)
	{
		requireThat(appended, "appended").isNotNull();
		if (draft.isPresent())
			return draft.get();
		if (!location.created() && recorded != null)
		{
			try
			{
				return scenario.rewrite(recorded, appended);
			}
			catch (ScenarioFormatException _)
			{
				// A recorded file that the scenario format cannot read is replaced by a new scenario
			}
		}
		return scenario.renderFile(id, designGoals);
	}

	/**
	 * Returns the prompt tests of a confirmed contract as delivery writes them.
	 *
	 * @param contract the confirmed contract
	 * @param added    the test cases that the campaign added beyond the contract
	 * @param target   the campaign target, relative to the project
	 * @return one delivered test per prompt test, in contract order, each holding the prompt test's committed and new
	 *   test cases followed by the added ones; a prompt test with a draft directory lies at its directory there, which
	 *   delivery edits if the test keeps the recorded {@value PromptTestFiles#SCENARIO} in it and creates otherwise
	 * @throws NullPointerException     if any of the arguments are null
	 * @throws IllegalArgumentException if {@code target} is absolute, or a prompt test cannot be written as a scenario
	 */
	public static List<DeliveredTest> of(Contract contract, AddedTestCases added, Path target)
	{
		requireThat(added, "added").isNotNull();
		Map<String, Location> locations = PromptTestLocations.of(contract, target);
		List<DeliveredTest> tests = new ArrayList<>();
		for (PromptTest test : contract.tests())
		{
			Set<TestCase> testCases = new LinkedHashSet<>(test.allTestCases());
			for (AddedTestCase addedTestCase : added.of(test.id()))
				testCases.add(addedTestCase.testCase());
			Location location = locations.get(test.id());
			Optional<String> draft = Optional.empty();
			if (test.draft().isPresent())
			{
				Path directory = test.draft().get().directory();
				String recorded = PortablePaths.text(directory.resolve(PromptTestFiles.SCENARIO));
				location = new Location(directory, !test.regressionScenarios().contains(recorded));
				draft = Optional.of(test.draft().get().scenario());
			}
			tests.add(new DeliveredTest(test.id(), location, test.scenario(), test.expectedBehaviour(),
				List.copyOf(testCases), test.fixtures(), draft));
		}
		return tests;
	}
}
