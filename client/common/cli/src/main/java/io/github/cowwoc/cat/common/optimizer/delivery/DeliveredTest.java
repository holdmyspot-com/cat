package io.github.cowwoc.cat.common.optimizer.delivery;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

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
 *                    them
 */
public record DeliveredTest(String id, Location location, Scenario scenario, String designGoals,
	List<TestCase> testCases, List<FixtureFile> fixtures)
{
	/**
	 * Creates a delivered test.
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
	}

	/**
	 * Returns the {@value PromptTestFiles#SCENARIO} that delivery writes for this prompt test: the recorded file
	 * rewritten when delivery edits a recorded scenario that the scenario format reads, and a new file otherwise.
	 *
	 * @param recorded the content of the {@value PromptTestFiles#SCENARIO} recorded in this test's directory, or empty
	 *                 if there is none
	 * @param appended the fixture patterns that delivery appends to the recorded file's patterns, in order; a new file
	 *                 selects only its own {@code fixtures} directory
	 * @return the file's content
	 * @throws NullPointerException if any of the arguments are null
	 */
	public String scenarioFile(Optional<String> recorded, List<String> appended)
	{
		requireThat(recorded, "recorded").isNotNull();
		requireThat(appended, "appended").isNotNull();
		if (!location.created() && recorded.isPresent())
		{
			try
			{
				return scenario.rewrite(recorded.get(), appended);
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
	 *   test cases followed by the added ones
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
			tests.add(new DeliveredTest(test.id(), locations.get(test.id()), test.scenario(), test.expectedBehaviour(),
				List.copyOf(testCases), test.fixtures()));
		}
		return tests;
	}
}
