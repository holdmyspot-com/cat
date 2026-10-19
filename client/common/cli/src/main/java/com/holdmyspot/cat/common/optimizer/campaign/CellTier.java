package com.holdmyspot.cat.common.optimizer.campaign;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.optimizer.contract.CapabilityRanking;
import com.holdmyspot.cat.common.optimizer.contract.PromptTest;
import com.holdmyspot.cat.common.optimizer.contract.Cell;
import com.holdmyspot.cat.common.optimizer.contract.CertificationScope;
import com.holdmyspot.cat.common.optimizer.contract.Contract;
import com.holdmyspot.cat.common.optimizer.contract.ProfileCatalog;
import com.holdmyspot.cat.common.optimizer.contract.TrialProfile;
import com.holdmyspot.cat.common.optimizer.contract.TrialProfiles;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Selects the cells of the certification scope and the development cells. A cell tests one prompt test under one model
 * and reasoning profile.
 * <p>
 * Formal trials and the incumbent baseline run in the cells of the contract's {@link CertificationScope}. Development
 * runs in each test's development cells: its weakest cell, plus every cell where a certification attempt of the
 * lineage adopted a failure and every cell where a failure group of the lineage reproduced. The weakest cell is the
 * lowest effort of the least capable model by the contract's {@link CapabilityRanking}; the contract lists each
 * test's cells in inventory order, and the inventory lists each model's efforts from the lowest to the highest, so
 * a model's first cell in a test is its lowest effort.
 * <p>
 * A running attempt's formal trials go to its open cells: each test's development cells, and each other scope cell
 * once every development cell of its test and every scope cell of its test below it are certified.
 */
public final class CellTier
{
	/**
	 * The tool-output token limit of the stress setting that Codex offers.
	 */
	private static final int STRESS_TOOL_OUTPUT_TOKENS = 5000;

	/**
	 * Prevents instantiation.
	 */
	private CellTier()
	{
	}

	/**
	 * Returns the cells that a certification scope covers.
	 *
	 * @param profiles the trial profiles
	 * @param scope a certification scope
	 * @return the cells, in contract order
	 * @throws NullPointerException if any of the arguments are null
	 * @throws IllegalArgumentException if {@code scope} is {@link CertificationScope#WEAKEST_CELL} and a test's
	 *   weakest cell depends on a model whose family is unranked
	 */
	public static List<Cell> scopeCells(TrialProfiles profiles, CertificationScope scope)
	{
		requireThat(profiles, "profiles").isNotNull();
		requireThat(scope, "scope").isNotNull();
		List<Cell> scopeCells = new ArrayList<>();
		for (List<Cell> testCells : byTest(profiles.cells()))
		{
			if (scope == CertificationScope.WEAKEST_CELL)
			{
				String testId = testCells.getFirst().testId();
				scopeCells.add(weakestCell(profiles, scope, testId).orElseThrow(() ->
					new IllegalArgumentException("The weakest cell of the test '" + testId + "' depends on " +
						"a model whose family the capability ranking does not list")));
			}
			else
				scopeCells.addAll(candidates(profiles, scope, testCells));
		}
		return scopeCells;
	}

	/**
	 * Returns the cells of a contract's certification scope.
	 *
	 * @param contract a confirmed contract
	 * @return the cells, in contract order
	 * @throws NullPointerException if {@code contract} is null
	 * @throws IllegalArgumentException if the contract has no certification scope
	 */
	public static List<Cell> scopeCells(Contract contract)
	{
		requireThat(contract, "contract").isNotNull();
		return scopeCells(contract.profiles(), scope(contract));
	}

	/**
	 * Returns the identifiers of the cells of a contract's certification scope.
	 *
	 * @param contract a confirmed contract
	 * @return the identifiers, in contract order
	 * @throws NullPointerException if {@code contract} is null
	 * @throws IllegalArgumentException if the contract has no certification scope
	 */
	public static List<String> scopeCellIds(Contract contract)
	{
		return scopeCells(contract).stream().map(Cell::id).toList();
	}

	/**
	 * Returns a contract's certification scope.
	 *
	 * @param contract a contract
	 * @return the scope
	 * @throws IllegalArgumentException if the contract has no certification scope
	 */
	private static CertificationScope scope(Contract contract)
	{
		CertificationScope scope = contract.profiles().scope();
		if (scope == null)
			throw new IllegalArgumentException("The contract has no certification scope");
		return scope;
	}

	/**
	 * Returns a test's weakest cell under a certification scope: among the test's cells that the scope
	 * allows, the lowest effort of the least capable model. For {@link CertificationScope#WEAKEST_CELL} and
	 * {@link CertificationScope#LATEST_VERSIONS} those are the cells of each model family's latest version, and for
	 * {@link CertificationScope#DESCRIBED} the described cells. A test with one cell uses it.
	 *
	 * @param profiles the trial profiles
	 * @param scope a certification scope
	 * @param testId the test
	 * @return the cell, or empty if it depends on a model whose family is unranked
	 * @throws NullPointerException if any of the arguments are null
	 * @throws IllegalArgumentException if the test has no cell
	 */
	public static Optional<Cell> weakestCell(TrialProfiles profiles, CertificationScope scope, String testId)
	{
		requireThat(profiles, "profiles").isNotNull();
		requireThat(scope, "scope").isNotNull();
		List<Cell> testCells = profiles.cellsOf(testId);
		if (testCells.isEmpty())
			throw new IllegalArgumentException("The test '" + testId + "' has no cell");
		List<Cell> candidates = candidates(profiles, scope, testCells);
		if (candidates.size() == 1)
			return Optional.of(candidates.getFirst());
		CapabilityRanking ranking = profiles.ranking();
		Cell weakest = null;
		for (Cell cell : candidates)
		{
			String model = cell.profile().model();
			if (!ranking.isRanked(model))
				return Optional.empty();
			// The inventory lists each model's efforts lowest first, so a model's first cell is its lowest effort
			if (weakest == null || ranking.compare(model, weakest.profile().model()) < 0)
				weakest = cell;
		}
		return Optional.ofNullable(weakest);
	}

	/**
	 * Returns a test's weakest cell under a contract's certification scope.
	 *
	 * @param contract a confirmed contract
	 * @param testId the test
	 * @return the cell
	 * @throws NullPointerException if any of the arguments are null
	 * @throws IllegalArgumentException if the contract has no certification scope or the test has no cell
	 */
	public static Cell weakestCell(Contract contract, String testId)
	{
		requireThat(contract, "contract").isNotNull();
		CertificationScope scope = scope(contract);
		// A confirmed contract ranks every inventory model, so the weakest cell is known
		return weakestCell(contract.profiles(), scope, testId).orElseThrow();
	}

	/**
	 * Returns the cells of a test that a certification scope allows.
	 *
	 * @param profiles the trial profiles
	 * @param scope a certification scope
	 * @param testCells the test's cells, in contract order
	 * @return the test's only cell; the described cells for {@link CertificationScope#DESCRIBED}; otherwise the
	 *   cells of each model family's latest version among the test's cells
	 */
	private static List<Cell> candidates(TrialProfiles profiles, CertificationScope scope, List<Cell> testCells)
	{
		if (testCells.size() == 1)
			return testCells;
		if (scope == CertificationScope.DESCRIBED)
			return testCells.stream().filter(profiles::isDescribed).toList();
		Map<String, String> latest = new HashMap<>();
		for (Cell cell : testCells)
		{
			String model = cell.profile().model();
			latest.merge(CapabilityRanking.family(model), model, (current, candidate) ->
			{
				if (CapabilityRanking.compareVersions(candidate, current) > 0)
					return candidate;
				return current;
			});
		}
		return testCells.stream().filter(cell ->
		{
			String model = cell.profile().model();
			return CapabilityRanking.compareVersions(model, latest.get(CapabilityRanking.family(model))) == 0;
		}).toList();
	}

	/**
	 * Groups cells by test.
	 *
	 * @param cells cells grouped by test
	 * @return each test's cells, in contract order
	 */
	private static Collection<List<Cell>> byTest(List<Cell> cells)
	{
		Map<String, List<Cell>> byTest = new LinkedHashMap<>();
		for (Cell cell : cells)
			byTest.computeIfAbsent(cell.testId(), _ -> new ArrayList<>()).add(cell);
		return byTest.values();
	}

	/**
	 * Returns the development cells of a lineage: each test's weakest cell, plus every cell in which a
	 * certification attempt of the lineage adopted a failure, every cell that ended an attempt of the lineage below
	 * control or below incumbent, and every stress cell in which a failure group of the lineage reproduced.
	 *
	 * @param contract the lineage's confirmed contract
	 * @param lineage the lineage's number
	 * @param attempts the campaign's certification attempts
	 * @return the cells, in contract order, each cell under the tool-output stress limit right after its contract cell
	 * @throws NullPointerException if any of the arguments are null
	 * @throws IllegalArgumentException if the contract has no certification scope
	 */
	public static List<Cell> developmentCells(Contract contract, int lineage, List<CertificationAttempt> attempts)
	{
		requireThat(contract, "contract").isNotNull();
		requireThat(attempts, "attempts").isNotNull();
		Set<String> cells = new HashSet<>();
		for (PromptTest test : contract.tests())
			cells.add(weakestCell(contract, test.id()).id());
		for (CertificationAttempt attempt : attempts)
		{
			if (attempt.lineage() != lineage)
				continue;
			for (Failure failure : attempt.failures())
				cells.add(failure.testCase().cellId());
			if (attempt.outcome() == Outcome.BELOW_CONTROL || attempt.outcome() == Outcome.BELOW_INCUMBENT)
			{
				for (String cell : attempt.procedure().cells())
				{
					DecisionTable.CellDecision decision = DecisionTable.cellDecision(attempt, cell);
					if (decision == DecisionTable.CellDecision.BELOW_CONTROL ||
						decision == DecisionTable.CellDecision.BELOW_INCUMBENT)
					{
						cells.add(cell);
					}
				}
			}
			for (FailureGroup group : attempt.groups())
			{
				if (group.status() == FailureGroup.Status.REPRODUCED)
					cells.add(group.round().cellId());
			}
		}
		return inContractOrder(contract, cells);
	}

	/**
	 * Selects cells from a contract and from the contract's cells under the tool-output stress limit.
	 *
	 * @param contract a confirmed contract
	 * @param ids the identifiers of the cells to select
	 * @return the selected cells, in contract order, each cell under the stress limit right after its contract cell
	 */
	private static List<Cell> inContractOrder(Contract contract, Set<String> ids)
	{
		List<Cell> selected = new ArrayList<>();
		for (Cell cell : contract.cells())
		{
			if (ids.contains(cell.id()))
				selected.add(cell);
			Cell limited = cell.withToolOutputTokenLimit(STRESS_TOOL_OUTPUT_TOKENS);
			if (ids.contains(limited.id()))
				selected.add(limited);
		}
		return selected;
	}

	/**
	 * Returns the stress settings that a harness offers for a cell, in the order that reproduction rounds try them:
	 * the cell of the same test and model at the next lower reasoning effort, when one exists, and then, in
	 * Codex, the cell itself under a tool-output token limit of 5000. A failure is likelier under each.
	 *
	 * @param contract a confirmed contract
	 * @param harness the harness that runs the campaign
	 * @param cellId the identifier of a contract cell
	 * @return the stress cells, in the order that rounds try them
	 * @throws NullPointerException if any of the arguments are null
	 * @throws IllegalArgumentException if the contract has no such cell
	 */
	public static List<Cell> stressCells(Contract contract, Harness harness, String cellId)
	{
		requireThat(contract, "contract").isNotNull();
		requireThat(harness, "harness").isNotNull();
		requireThat(cellId, "cellId").isNotNull();
		Cell cell = contract.cells().stream().filter(candidate -> candidate.id().equals(cellId)).findFirst().
			orElseThrow(() -> new IllegalArgumentException("The contract has no cell '" + cellId + "'"));

		List<Cell> stress = new ArrayList<>();
		// The contract lists a test's cells in inventory order, and the inventory lists each model's efforts
		// lowest first, so the model's previous cell is its next lower effort
		Cell lower = null;
		for (Cell other : contract.profiles().cellsOf(cell.testId()))
		{
			if (other.equals(cell))
				break;
			if (other.profile().model().equals(cell.profile().model()))
				lower = other;
		}
		if (lower != null)
			stress.add(lower);
		switch (harness)
		{
			case CODEX -> stress.add(cell.withToolOutputTokenLimit(STRESS_TOOL_OUTPUT_TOKENS));
			case CLAUDE ->
			{
				// Claude offers no tool-output token limit
			}
		}
		return stress;
	}

	/**
	 * Returns the development cells of a campaign's current lineage.
	 *
	 * @param campaign a campaign with a confirmed contract
	 * @return the cells, in contract order
	 * @throws NullPointerException if {@code campaign} is null
	 * @throws IllegalStateException if no contract has been confirmed
	 */
	public static List<Cell> developmentCells(Campaign campaign)
	{
		requireThat(campaign, "campaign").isNotNull();
		return developmentCells(campaign.contract(), campaign.lineage().number(), campaign.attempts());
	}

	/**
	 * Returns the identifiers of the development cells of a campaign's current lineage.
	 *
	 * @param campaign a campaign with a confirmed contract
	 * @return the identifiers, in contract order
	 * @throws NullPointerException if {@code campaign} is null
	 * @throws IllegalStateException if no contract has been confirmed
	 */
	public static List<String> developmentCellIds(Campaign campaign)
	{
		return developmentCells(campaign).stream().map(Cell::id).toList();
	}

	/**
	 * Returns the given cells together with the development cells of a campaign's current lineage, each cell once.
	 *
	 * @param campaign a campaign with a confirmed contract
	 * @param cellIds the identifiers of cells of the campaign's contract
	 * @return the identifiers, in contract order, each development cell under the tool-output stress limit right
	 *   after its contract cell
	 * @throws NullPointerException if any of the arguments are null
	 * @throws IllegalStateException if no contract has been confirmed
	 */
	public static List<String> withDevelopmentCellIds(Campaign campaign, List<String> cellIds)
	{
		requireThat(campaign, "campaign").isNotNull();
		requireThat(cellIds, "cellIds").isNotNull();
		Set<String> ids = new HashSet<>(cellIds);
		ids.addAll(developmentCellIds(campaign));
		return inContractOrder(campaign.contract(), ids).stream().map(Cell::id).toList();
	}

	/**
	 * Returns the open cells of a running certification attempt: in each test, its development cells, and each
	 * other cell of the attempt once every development cell of the test and every cell of the test below it
	 * are certified.
	 * <p>
	 * A cell is below another when its model ranks no higher by the contract's {@link CapabilityRanking}, its effort
	 * is no higher, and the two differ. The inventory lists each model's efforts from the lowest to the highest, and
	 * an effort is higher than another when some model lists it later, directly or through other efforts, and no model
	 * lists it earlier. A cell whose model is unranked, or whose effort no model lists in order with the other cell's,
	 * is neither below nor above it.
	 *
	 * @param campaign a campaign with a confirmed contract
	 * @param attempt the campaign's running attempt
	 * @return the open cells, in the attempt's cell order
	 * @throws NullPointerException if any of the arguments are null
	 * @throws IllegalArgumentException if the contract lacks a cell of the attempt
	 */
	static List<Cell> openCells(Campaign campaign, CertificationAttempt attempt)
	{
		requireThat(campaign, "campaign").isNotNull();
		requireThat(attempt, "attempt").isNotNull();
		TrialProfiles profiles = campaign.contract().profiles();
		List<Cell> cells = new ArrayList<>();
		Set<String> certified = new HashSet<>();
		for (String id : attempt.procedure().cells())
		{
			cells.add(profiles.findCell(id).orElseThrow(() -> new IllegalArgumentException("The contract lacks " +
				"the cell '" + id + "' of certification attempt " + attempt.index())));
			if (DecisionTable.cellDecision(attempt, id) == DecisionTable.CellDecision.CERTIFIED)
				certified.add(id);
		}
		Set<String> development = new HashSet<>(developmentCellIds(campaign));
		Map<String, Set<String>> higherEfforts = higherEfforts(profiles.inventory());
		List<Cell> open = new ArrayList<>();
		for (Cell cell : cells)
		{
			if (development.contains(cell.id()) ||
				cells.stream().noneMatch(other -> other.testId().equals(cell.testId()) &&
					!other.equals(cell) && !certified.contains(other.id()) && (development.contains(other.id()) ||
					isBelow(profiles.ranking(), higherEfforts, other.profile(), cell.profile()))))
			{
				open.add(cell);
			}
		}
		return open;
	}

	/**
	 * Indicates whether a trial profile is below another: its model ranks no higher, its effort is no higher, and
	 * the two differ.
	 *
	 * @param ranking the capability ranking
	 * @param higherEfforts each effort's higher efforts
	 * @param profile a profile
	 * @param other another profile
	 * @return {@code true} if {@code profile} is below {@code other}
	 */
	private static boolean isBelow(CapabilityRanking ranking, Map<String, Set<String>> higherEfforts,
		TrialProfile profile, TrialProfile other)
	{
		if (profile.equals(other) || !ranking.isRanked(profile.model()) || !ranking.isRanked(other.model()) ||
			ranking.compare(profile.model(), other.model()) > 0)
		{
			return false;
		}
		String effort = profile.effort();
		String otherEffort = other.effort();
		return effort.equals(otherEffort) || (higherEfforts.getOrDefault(effort, Set.of()).contains(otherEffort) &&
			!higherEfforts.getOrDefault(otherEffort, Set.of()).contains(effort));
	}

	/**
	 * Returns the efforts that the inventory ranks above each effort: those that some model lists after it, directly
	 * or through other efforts.
	 *
	 * @param inventory the profile inventory, which lists each model's efforts from the lowest to the highest
	 * @return each effort's higher efforts
	 */
	private static Map<String, Set<String>> higherEfforts(ProfileCatalog inventory)
	{
		Map<String, List<String>> byModel = new LinkedHashMap<>();
		for (TrialProfile profile : inventory.profiles())
			byModel.computeIfAbsent(profile.model(), _ -> new ArrayList<>()).add(profile.effort());
		Map<String, Set<String>> higher = new HashMap<>();
		for (List<String> efforts : byModel.values())
		{
			for (int i = 0; i < efforts.size(); ++i)
			{
				Set<String> above = higher.computeIfAbsent(efforts.get(i), _ -> new HashSet<>());
				above.addAll(efforts.subList(i + 1, efforts.size()));
			}
		}
		boolean changed = true;
		while (changed)
		{
			changed = false;
			for (Set<String> above : higher.values())
			{
				for (String effort : List.copyOf(above))
					changed |= above.addAll(higher.getOrDefault(effort, Set.of()));
			}
		}
		return higher;
	}
}
