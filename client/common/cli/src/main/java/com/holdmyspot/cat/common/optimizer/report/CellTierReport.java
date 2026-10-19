package com.holdmyspot.cat.common.optimizer.report;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.optimizer.campaign.Campaign;
import com.holdmyspot.cat.common.optimizer.campaign.CellTier;
import com.holdmyspot.cat.common.optimizer.contract.PromptTest;
import com.holdmyspot.cat.common.optimizer.contract.Cell;
import com.holdmyspot.cat.common.optimizer.contract.CertificationScope;
import com.holdmyspot.cat.common.optimizer.contract.Contract;

import java.util.ArrayList;
import java.util.List;

/**
 * The cells of a confirmed campaign: its certification scope and, per test, the weakest cell and the development
 * cells.
 * <p>
 * <b>Thread Safety:</b> This class is immutable and thread-safe.
 *
 * @param scope the certification scope that the user chose
 * @param scopeCells the number of cells in the certification scope
 * @param tests each test's cells, in contract order
 */
public record CellTierReport(CertificationScope scope, int scopeCells, List<TestCells> tests)
{
	/**
	 * Creates a report.
	 *
	 * @param scope the certification scope that the user chose
	 * @param scopeCells the number of cells in the certification scope
	 * @param tests each test's cells, in contract order
	 * @throws NullPointerException if {@code scope} or {@code tests} are null
	 * @throws IllegalArgumentException if {@code scopeCells} is not positive
	 */
	public CellTierReport
	{
		requireThat(scope, "scope").isNotNull();
		requireThat(scopeCells, "scopeCells").isPositive();
		tests = List.copyOf(tests);
	}

	/**
	 * Returns the cells of a campaign.
	 *
	 * @param campaign the campaign
	 * @return the report, or {@code null} before a contract is confirmed
	 * @throws NullPointerException if {@code campaign} is null
	 */
	public static CellTierReport of(Campaign campaign)
	{
		requireThat(campaign, "campaign").isNotNull();
		if (!campaign.isConfirmed())
			return null;
		Contract contract = campaign.contract();
		List<Cell> development = CellTier.developmentCells(campaign);
		List<TestCells> tests = new ArrayList<>();
		for (PromptTest test : contract.tests())
		{
			List<String> cells = development.stream().filter(cell -> cell.testId().equals(test.id())).
				map(Cell::id).toList();
			tests.add(new TestCells(test.id(), CellTier.weakestCell(contract, test.id()).id(),
				cells));
		}
		return new CellTierReport(contract.profiles().scope(), CellTier.scopeCells(contract).size(), tests);
	}

	/**
	 * One test's weakest cell and development cells.
	 * <p>
	 * <b>Thread Safety:</b> This class is immutable and thread-safe.
	 *
	 * @param test the test's identifier
	 * @param weakestCell the identifier of the test's weakest cell
	 * @param developmentCells the identifiers of the test's development cells, in contract order
	 */
	public record TestCells(String test, String weakestCell, List<String> developmentCells)
	{
		/**
		 * Creates a test's cells.
		 *
		 * @param test the test's identifier
		 * @param weakestCell the identifier of the test's weakest cell
		 * @param developmentCells the identifiers of the test's development cells, in contract order
		 * @throws NullPointerException if any of the arguments are null
		 * @throws IllegalArgumentException if {@code test} or {@code weakestCell} are blank, or
		 *   {@code developmentCells} is empty
		 */
		public TestCells
		{
			requireThat(test, "test").isNotBlank();
			requireThat(weakestCell, "weakestCell").isNotBlank();
			requireThat(developmentCells, "developmentCells").isNotEmpty();
			developmentCells = List.copyOf(developmentCells);
		}
	}
}
