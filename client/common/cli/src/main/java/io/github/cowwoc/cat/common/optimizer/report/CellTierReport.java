package io.github.cowwoc.cat.common.optimizer.report;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.optimizer.campaign.Campaign;
import io.github.cowwoc.cat.common.optimizer.campaign.CellTier;
import io.github.cowwoc.cat.common.optimizer.contract.Category;
import io.github.cowwoc.cat.common.optimizer.contract.Cell;
import io.github.cowwoc.cat.common.optimizer.contract.CertificationScope;
import io.github.cowwoc.cat.common.optimizer.contract.Contract;

import java.util.ArrayList;
import java.util.List;

/**
 * The cells of a confirmed campaign: its certification scope and, per category, the weakest cell and the development
 * cells.
 * <p>
 * <b>Thread Safety:</b> This class is immutable and thread-safe.
 *
 * @param scope the certification scope that the user chose
 * @param scopeCells the number of cells in the certification scope
 * @param categories each category's cells, in contract order
 */
public record CellTierReport(CertificationScope scope, int scopeCells, List<CategoryCells> categories)
{
	/**
	 * Creates a report.
	 *
	 * @param scope the certification scope that the user chose
	 * @param scopeCells the number of cells in the certification scope
	 * @param categories each category's cells, in contract order
	 * @throws NullPointerException if {@code scope} or {@code categories} are null
	 * @throws IllegalArgumentException if {@code scopeCells} is not positive
	 */
	public CellTierReport
	{
		requireThat(scope, "scope").isNotNull();
		requireThat(scopeCells, "scopeCells").isPositive();
		categories = List.copyOf(categories);
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
		List<CategoryCells> categories = new ArrayList<>();
		for (Category category : contract.categories())
		{
			List<String> cells = development.stream().filter(cell -> cell.categoryId().equals(category.id())).
				map(Cell::id).toList();
			categories.add(new CategoryCells(category.id(), CellTier.weakestCell(contract, category.id()).id(),
				cells));
		}
		return new CellTierReport(contract.profiles().scope(), CellTier.scopeCells(contract).size(), categories);
	}

	/**
	 * One category's weakest cell and development cells.
	 * <p>
	 * <b>Thread Safety:</b> This class is immutable and thread-safe.
	 *
	 * @param category the category's identifier
	 * @param weakestCell the identifier of the category's weakest cell
	 * @param developmentCells the identifiers of the category's development cells, in contract order
	 */
	public record CategoryCells(String category, String weakestCell, List<String> developmentCells)
	{
		/**
		 * Creates a category's cells.
		 *
		 * @param category the category's identifier
		 * @param weakestCell the identifier of the category's weakest cell
		 * @param developmentCells the identifiers of the category's development cells, in contract order
		 * @throws NullPointerException if any of the arguments are null
		 * @throws IllegalArgumentException if {@code category} or {@code weakestCell} are blank, or
		 *   {@code developmentCells} is empty
		 */
		public CategoryCells
		{
			requireThat(category, "category").isNotBlank();
			requireThat(weakestCell, "weakestCell").isNotBlank();
			requireThat(developmentCells, "developmentCells").isNotEmpty();
			developmentCells = List.copyOf(developmentCells);
		}
	}
}
