package io.github.cowwoc.cat.common.optimizer.contract.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.optimizer.contract.Cell;
import io.github.cowwoc.cat.common.optimizer.contract.Contract;
import io.github.cowwoc.cat.common.optimizer.contract.DrawStream;
import io.github.cowwoc.cat.common.optimizer.contract.DrawnTestCase;
import io.github.cowwoc.cat.common.optimizer.contract.Generator;
import io.github.cowwoc.cat.common.optimizer.contract.PromptTest;
import org.testng.annotations.Test;

/**
 * Tests the balanced benchmark distribution of a {@link Contract}.
 */
public final class DistributionTest
{
	/**
	 * Creates the test fixture.
	 */
	public DistributionTest()
	{
	}

	/**
	 * Verifies that a cell draws only test cases of its own prompt test, so that each prompt test is measured on its
	 * own.
	 */
	@Test
	public void eachCellDrawsOnlyItsTest()
	{
		// Requirement: R-C3
		// Requirement: R-C11
		Contract contract = ContractFixtures.contract();
		Generator generator = new Generator(contract);
		DrawStream stream = DrawStream.create(DrawStream.Kind.DEVELOPMENT, 99L);
		for (Cell cell : contract.cells())
		{
			PromptTest test = contract.testOf(cell);
			for (int i = 0; i < 200; ++i)
			{
				DrawnTestCase testCase = generator.draw(cell.id(), stream);
				requireThat(testCase.testId(), "testId").isEqualTo(test.id());
				requireThat(test.testCases(), "testCases").contains(testCase.testCase());
			}
		}
	}

	/**
	 * Verifies that the distribution is frozen: drawing and marking test cases leave the contract unchanged, and each
	 * drawn test case is one that its test lists.
	 */
	@Test
	public void weightsStayFrozenWhileDrawing()
	{
		// Requirement: R-C3
		Contract contract = ContractFixtures.contract();
		Generator generator = new Generator(contract);
		DrawStream stream = DrawStream.create(DrawStream.Kind.DEVELOPMENT, 5L);
		PromptTest test = contract.findTest("formatting").orElseThrow();
		for (int i = 0; i < 500; ++i)
		{
			DrawnTestCase testCase = generator.draw("formatting", stream);
			generator.markTargeted(testCase);
			requireThat(test.testCases(), "testCases").contains(testCase.testCase());
		}

		requireThat(contract, "contract").isEqualTo(ContractFixtures.contract());
	}
}
