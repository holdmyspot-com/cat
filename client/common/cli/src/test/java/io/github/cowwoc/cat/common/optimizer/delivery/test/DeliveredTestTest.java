package io.github.cowwoc.cat.common.optimizer.delivery.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.optimizer.contract.AddedTestCase;
import io.github.cowwoc.cat.common.optimizer.contract.AddedTestCases;
import io.github.cowwoc.cat.common.optimizer.contract.Contract;
import io.github.cowwoc.cat.common.optimizer.contract.PromptTest;
import io.github.cowwoc.cat.common.optimizer.contract.PromptTestLocations;
import io.github.cowwoc.cat.common.optimizer.contract.TestCase;
import io.github.cowwoc.cat.common.optimizer.contract.test.ContractFixtures;
import io.github.cowwoc.cat.common.optimizer.delivery.DeliveredTest;

import org.testng.annotations.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Tests {@link DeliveredTest}.
 */
public final class DeliveredTestTest
{
	/**
	 * Creates the test.
	 */
	public DeliveredTestTest()
	{
	}

	/**
	 * Verifies that each delivered prompt test holds every test case of the campaign once: the committed and new test
	 * cases of the confirmed contract, then the test cases that the campaign added, without repeating one that the
	 * contract already holds. Each prompt test keeps its scenario, design goals and location.
	 */
	@Test
	public void holdsEveryTestCaseOfTheCampaignOnce()
	{
		// Requirement: R-W11
		Contract contract = ContractFixtures.contract();
		PromptTest first = contract.tests().getFirst();
		List<AddedTestCase> fresh = ContractFixtures.addedTestCases(first, 1, "round");
		List<AddedTestCase> added = new ArrayList<>(fresh);
		added.add(new AddedTestCase(2, first.id(), first.allTestCases().getFirst()));
		Path target = Path.of("rules/main.md");

		List<DeliveredTest> tests = DeliveredTest.of(contract, new AddedTestCases(added), target);

		requireThat(tests.stream().map(DeliveredTest::id).toList(), "ids").
			isEqualTo(contract.tests().stream().map(PromptTest::id).toList());
		List<TestCase> expected = new ArrayList<>(first.allTestCases());
		for (AddedTestCase testCase : fresh)
			expected.add(testCase.testCase());
		DeliveredTest delivered = tests.getFirst();
		requireThat(delivered.testCases(), "testCases").isEqualTo(expected);
		requireThat(tests.get(1).testCases(), "otherTestCases").isEqualTo(contract.tests().get(1).allTestCases());
		requireThat(delivered.scenario(), "scenario").isEqualTo(first.scenario());
		requireThat(delivered.designGoals(), "designGoals").isEqualTo(first.expectedBehaviour());
		requireThat(delivered.location(), "location").
			isEqualTo(PromptTestLocations.of(contract, target).get(first.id()));
	}
}
