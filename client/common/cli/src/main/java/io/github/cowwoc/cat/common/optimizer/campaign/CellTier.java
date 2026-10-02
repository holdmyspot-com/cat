package io.github.cowwoc.cat.common.optimizer.campaign;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.optimizer.contract.Cell;
import io.github.cowwoc.cat.common.optimizer.contract.Contract;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Selects the cells that receive the working candidate's sampled development trials. A cell tests one category
 * under one model and reasoning profile.
 * <p>
 * A cell is active when it is its category's only cell, when it is the lowest or the highest effort that its model
 * has in its category, or when a candidate of the campaign has failed a trial in it. The contract lists each
 * category's cells in inventory order, and the inventory lists each model's efforts from the lowest to the highest,
 * so a model's first and last cell in a category are its lowest and its highest effort.
 */
public final class CellTier
{
	/**
	 * The representative trials of the first working candidate that every cell receives when development starts.
	 */
	public static final int SCREEN_TRIALS = 3;

	/**
	 * Prevents instantiation.
	 */
	private CellTier()
	{
	}

	/**
	 * Returns the active cells of a contract.
	 *
	 * @param contract the contract
	 * @param failedCells the identifiers of the cells in which a candidate of the campaign has failed a trial;
	 *   identifiers that the contract does not list are ignored
	 * @return the active cells, in contract order
	 * @throws NullPointerException if any of the arguments are null
	 */
	public static List<Cell> activeCells(Contract contract, Set<String> failedCells)
	{
		requireThat(contract, "contract").isNotNull();
		requireThat(failedCells, "failedCells").isNotNull();
		Map<ModelKey, Cell> lowest = new HashMap<>();
		Map<ModelKey, Cell> highest = new HashMap<>();
		for (Cell cell : contract.cells())
		{
			ModelKey model = ModelKey.of(cell);
			lowest.putIfAbsent(model, cell);
			highest.put(model, cell);
		}
		List<Cell> active = new ArrayList<>();
		for (Cell cell : contract.cells())
		{
			ModelKey model = ModelKey.of(cell);
			if (lowest.get(model).equals(cell) || highest.get(model).equals(cell) || failedCells.contains(cell.id()))
				active.add(cell);
		}
		return active;
	}

	/**
	 * Returns the identifiers of the active cells of a contract, grouped by category.
	 *
	 * @param contract the contract
	 * @param failedCells the identifiers of cells in which a candidate of the campaign has failed a trial;
	 *   identifiers absent from the contract are ignored
	 * @return the identifiers of each category's active cells, by category in contract order
	 * @throws NullPointerException if any of the arguments are null
	 */
	public static Map<String, List<String>> activeCellsByCategory(Contract contract, Set<String> failedCells)
	{
		Map<String, List<String>> byCategory = new LinkedHashMap<>();
		for (Cell cell : activeCells(contract, failedCells))
			byCategory.computeIfAbsent(cell.categoryId(), _ -> new ArrayList<>()).add(cell.id());
		return byCategory;
	}

	/**
	 * Returns the identifiers of each model's lowest-effort cell in each category.
	 *
	 * @param contract the contract
	 * @return the cell identifiers
	 * @throws NullPointerException if {@code contract} is null
	 */
	static Set<String> lowestEffortCells(Contract contract)
	{
		Set<ModelKey> models = new HashSet<>();
		Set<String> lowest = new HashSet<>();
		for (Cell cell : contract.cells())
		{
			if (models.add(ModelKey.of(cell)))
				lowest.add(cell.id());
		}
		return lowest;
	}

	/**
	 * Indicates whether every active cell of a contract meets the readiness gate's count conditions.
	 *
	 * @param contract the contract
	 * @param failedCells the identifiers of the cells in which a candidate of the campaign has failed a trial
	 * @param counts the working candidate's development counts per cell; a missing cell has no outcomes
	 * @return {@code true} if every active cell is ready
	 * @throws NullPointerException if any argument is null
	 */
	static boolean isActiveSetReady(Contract contract, Set<String> failedCells, Map<String, CellCounts> counts)
	{
		for (Cell cell : activeCells(contract, failedCells))
		{
			if (!ReadinessGate.isReady(counts.getOrDefault(cell.id(), CellCounts.EMPTY)))
				return false;
		}
		return true;
	}

	/**
	 * The cells of one model in one category.
	 *
	 * @param category the category's identifier
	 * @param model the model
	 */
	private record ModelKey(String category, String model)
	{
		/**
		 * Returns the key of a cell.
		 *
		 * @param cell the cell
		 * @return the key
		 */
		static ModelKey of(Cell cell)
		{
			return new ModelKey(cell.categoryId(), cell.profile().model());
		}
	}
}
