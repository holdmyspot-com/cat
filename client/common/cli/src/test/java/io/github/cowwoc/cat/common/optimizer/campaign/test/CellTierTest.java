package io.github.cowwoc.cat.common.optimizer.campaign.test;

import static io.github.cowwoc.cat.common.optimizer.contract.test.TierFixtures.BIG_HIGH;
import static io.github.cowwoc.cat.common.optimizer.contract.test.TierFixtures.BIG_MEDIUM;
import static io.github.cowwoc.cat.common.optimizer.contract.test.TierFixtures.SAFETY;
import static io.github.cowwoc.cat.common.optimizer.contract.test.TierFixtures.SMALL_HIGH;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.optimizer.campaign.Campaign;
import io.github.cowwoc.cat.common.optimizer.campaign.CellTier;
import io.github.cowwoc.cat.common.optimizer.campaign.Pairing;
import io.github.cowwoc.cat.common.optimizer.campaign.Outcome;
import io.github.cowwoc.cat.common.optimizer.campaign.PairCounts;
import io.github.cowwoc.cat.common.optimizer.contract.Assertion;
import io.github.cowwoc.cat.common.optimizer.contract.CapabilityRanking;
import io.github.cowwoc.cat.common.optimizer.contract.Cell;
import io.github.cowwoc.cat.common.optimizer.contract.CertificationScope;
import io.github.cowwoc.cat.common.optimizer.contract.Contract;
import io.github.cowwoc.cat.common.optimizer.contract.DrawStream;
import io.github.cowwoc.cat.common.optimizer.contract.Generator;
import io.github.cowwoc.cat.common.optimizer.contract.ProfileCatalog;
import io.github.cowwoc.cat.common.optimizer.contract.DrawnTestCase;
import io.github.cowwoc.cat.common.optimizer.contract.TrialProfile;
import io.github.cowwoc.cat.common.optimizer.contract.TrialProfiles;
import io.github.cowwoc.cat.common.optimizer.contract.test.TierFixtures;
import io.github.cowwoc.cat.common.optimizer.statistics.DecisionBoundaries;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.testng.annotations.Test;

/**
 * Tests {@link CellTier}: the certification scope's cells, each test's weakest cell and the development cells.
 */
public final class CellTierTest
{
	private static final TrialProfile SOL_HIGH = new TrialProfile("gpt-6.1-sol", "high");

	/**
	 * Creates the test.
	 */
	public CellTierTest()
	{
	}

	/**
	 * Returns the identifiers of cells.
	 *
	 * @param cells the cells
	 * @return the identifiers in order
	 */
	private static List<String> ids(List<Cell> cells)
	{
		return cells.stream().map(Cell::id).toList();
	}

	/**
	 * Returns the identifier of a {@code formatting} cell.
	 *
	 * @param model the cell's model
	 * @param effort the cell's effort
	 * @return the identifier
	 */
	private static String formatting(String model, String effort)
	{
		return "formatting@" + model + "/" + effort;
	}

	/**
	 * Returns the identifier of a test's weakest cell.
	 *
	 * @param profiles the trial profiles
	 * @param scope the certification scope
	 * @param testId the test
	 * @return the identifier, or empty if the weakest cell depends on an unranked model
	 */
	private static Optional<String> weakest(TrialProfiles profiles, CertificationScope scope, String testId)
	{
		return CellTier.weakestCell(profiles, scope, testId).map(Cell::id);
	}

	/**
	 * Verifies each scope's weakest cell and cells under the Codex inventory and ranking: the weakest cell is the
	 * lowest effort of the least capable model, with no trial; the latest-versions scope leaves out the older version
	 * of a family; a described scope covers the described profiles and its weakest cell is the oldest version of the
	 * least capable described family.
	 */
	@Test
	public void weakestCellPerScope()
	{
		// Requirement: R-E22
		// Requirement: R-C15
		TrialProfiles profiles = TierFixtures.contract(TierFixtures.CODEX_PROFILES, SOL_HIGH).profiles();
		String luna = formatting("gpt-6-luna", "low");
		requireThat(weakest(profiles, CertificationScope.WEAKEST_CELL, "formatting"), "weakest").
			isEqualTo(Optional.of(luna));
		requireThat(weakest(profiles, CertificationScope.LATEST_VERSIONS, "formatting"), "latestWeakest").
			isEqualTo(Optional.of(luna));
		requireThat(ids(CellTier.scopeCells(profiles, CertificationScope.WEAKEST_CELL)), "weakestScope").
			isEqualTo(List.of(luna, SAFETY));

		List<String> latest = new ArrayList<>();
		for (Map.Entry<String, List<String>> model : TierFixtures.CODEX_EFFORTS.entrySet())
		{
			if (model.getKey().equals("gpt-6-sol"))
				continue;
			for (String effort : model.getValue())
				latest.add(formatting(model.getKey(), effort));
		}
		latest.add(SAFETY);
		requireThat(latest.size(), "latestCount").isEqualTo(16);
		requireThat(ids(CellTier.scopeCells(profiles, CertificationScope.LATEST_VERSIONS)), "latestScope").
			isEqualTo(latest);

		TrialProfiles described = TierFixtures.contract(TierFixtures.CODEX_PROFILES, TierFixtures.CODEX_RANKING,
			List.of(new TrialProfile("gpt-6.1-sol", "high"), new TrialProfile("gpt-6-sol", "")), SOL_HIGH).profiles();
		requireThat(weakest(described, CertificationScope.DESCRIBED, "formatting"), "describedWeakest").
			isEqualTo(Optional.of(formatting("gpt-6-sol", "low")));
		List<String> describedCells = new ArrayList<>();
		describedCells.add(formatting("gpt-6.1-sol", "high"));
		for (String effort : TierFixtures.CODEX_EFFORTS.get("gpt-6-sol"))
			describedCells.add(formatting("gpt-6-sol", effort));
		describedCells.add(SAFETY);
		requireThat(ids(CellTier.scopeCells(described, CertificationScope.DESCRIBED)), "describedScope").
			isEqualTo(describedCells);
	}

	/**
	 * Verifies that the weakest cell comes from the latest versions: an older version of the least capable family is
	 * neither the weakest cell nor in the latest-versions scope.
	 */
	@Test
	public void weakestCellAmongLatestVersions()
	{
		// Requirement: R-E22
		ProfileCatalog inventory = new ProfileCatalog("codex debug models", List.of(
			new TrialProfile("gpt-6-luna", "low"), new TrialProfile("gpt-6-luna", "high"),
			new TrialProfile("gpt-6.1-luna", "low"), new TrialProfile("gpt-6.1-luna", "high"),
			new TrialProfile("gpt-6-sol", "low")));
		TrialProfiles profiles = TierFixtures.contract(inventory, TierFixtures.CODEX_RANKING, List.of(), SOL_HIGH).
			profiles();
		requireThat(weakest(profiles, CertificationScope.WEAKEST_CELL, "formatting"), "weakest").
			isEqualTo(Optional.of(formatting("gpt-6.1-luna", "low")));
		requireThat(ids(CellTier.scopeCells(profiles, CertificationScope.LATEST_VERSIONS)), "latest").
			isEqualTo(List.of(formatting("gpt-6.1-luna", "low"), formatting("gpt-6.1-luna", "high"),
				formatting("gpt-6-sol", "low"), SAFETY));
	}

	/**
	 * Verifies that a test with one cell has that cell as its weakest cell and its scope under every choice,
	 * including a described scope that does not name its profile.
	 */
	@Test
	public void oneCellTestUsesItsCell()
	{
		// Requirement: R-E22
		// Requirement: R-C15
		TrialProfiles profiles = TierFixtures.contract(TierFixtures.CODEX_PROFILES, TierFixtures.CODEX_RANKING,
			List.of(new TrialProfile("gpt-6-luna", "")), SOL_HIGH).profiles();
		for (CertificationScope scope : CertificationScope.values())
		{
			requireThat(weakest(profiles, scope, "safety"), scope.name()).isEqualTo(Optional.of(SAFETY));
			requireThat(ids(CellTier.scopeCells(profiles, scope)), scope + ".scope").contains(SAFETY);
		}
	}

	/**
	 * Verifies that a ranking corrected in revision feedback, which the contract holds, decides the weakest cell, and
	 * that a family the ranking does not list leaves the weakest cell unknown.
	 */
	@Test
	public void rankingDecidesWeakestCell()
	{
		// Requirement: R-E22
		CapabilityRanking corrected = new CapabilityRanking(List.of("luna", "sol", "astra"),
			"the user's revision feedback");
		TrialProfiles profiles = TierFixtures.contract(TierFixtures.CODEX_PROFILES, corrected, List.of(), SOL_HIGH).
			profiles();
		requireThat(weakest(profiles, CertificationScope.WEAKEST_CELL, "formatting"), "corrected").
			isEqualTo(Optional.of(formatting("gpt-6-astra", "low")));

		CapabilityRanking partial = new CapabilityRanking(List.of("astra", "sol"), "the user's revision feedback");
		TrialProfiles unranked = TierFixtures.contract(TierFixtures.CODEX_PROFILES, partial, List.of(), SOL_HIGH).
			profiles();
		requireThat(weakest(unranked, CertificationScope.WEAKEST_CELL, "formatting"), "unranked").isEmpty();
		requireThat(weakest(unranked, CertificationScope.WEAKEST_CELL, "safety"), "oneCell").
			isEqualTo(Optional.of(SAFETY));
	}

	/**
	 * Verifies that the development cells are each test's weakest cell plus every cell where a certification
	 * attempt of the lineage adopted a failure, and that neither a development failure nor a failure of an earlier
	 * lineage's attempt adds a cell.
	 */
	@Test
	public void developmentCellsAddCertificationFailures()
	{
		// Requirement: R-E19
		Contract contract = TierFixtures.tieredContract();
		Generator generator = new Generator(contract);
		DrawnTestCase medium = generator.draw(BIG_MEDIUM, DrawStream.create(DrawStream.Kind.DEVELOPMENT, 1));
		DrawnTestCase high = generator.draw(BIG_HIGH, DrawStream.create(DrawStream.Kind.DEVELOPMENT, 1));

		Campaign initial = new Builder().confirmed(contract, 1).working(1).failure(high).build();
		requireThat(ids(CellTier.developmentCells(initial.contract(), initial.lineage().number(),
			initial.attempts())), "initial").isEqualTo(List.of(SMALL_HIGH, SAFETY));

		Campaign failed = new Builder().confirmed(contract, 1).working(1).certificationAttempt(1).failure(medium).
			outcome(Outcome.INCONCLUSIVE).build();
		requireThat(ids(CellTier.developmentCells(failed.contract(), failed.lineage().number(), failed.attempts())),
			"failed").isEqualTo(List.of(BIG_MEDIUM, SMALL_HIGH, SAFETY));

		Campaign revised = new Builder().confirmed(contract, 2).working(1).certificationAttempt(1).lineage(1).
			failure(medium).outcome(Outcome.INCONCLUSIVE).build();
		requireThat(ids(CellTier.developmentCells(revised.contract(), revised.lineage().number(),
			revised.attempts())), "revised").isEqualTo(List.of(SMALL_HIGH, SAFETY));
	}

	/**
	 * Verifies that a cell that ended its attempt below control becomes a development cell.
	 */
	@Test
	public void developmentCellsAddBelowControlCell()
	{
		// Requirement: R-E19, R-F7
		Contract contract = TierFixtures.tieredContract();
		Map<String, PairCounts> comparisons = new LinkedHashMap<>();
		for (Assertion assertion : contract.testOf(contract.findCell(BIG_MEDIUM).orElseThrow()).allAssertions())
			comparisons.put(assertion.id(), new PairCounts(0, 0, 8, 0));
		Builder start = new Builder().confirmed(contract, 1).working(1);
		Campaign below = start.certificationAttempt(1).boundaries(paired(start)).cell(BIG_MEDIUM, 100, 0).
			controlPairing(BIG_MEDIUM, new Pairing(List.of(), comparisons)).outcome(Outcome.BELOW_CONTROL).build();
		requireThat(ids(CellTier.developmentCells(below.contract(), below.lineage().number(), below.attempts())),
			"below").isEqualTo(List.of(BIG_MEDIUM, SMALL_HIGH, SAFETY));
	}

	/**
	 * Verifies that a cell that ended its attempt below incumbent becomes a development cell.
	 */
	@Test
	public void developmentCellsAddBelowIncumbentCell()
	{
		// Requirement: R-E19, R-F8
		Contract contract = TierFixtures.tieredContract();
		Map<String, PairCounts> passed = new LinkedHashMap<>();
		Map<String, PairCounts> below = new LinkedHashMap<>();
		for (Assertion assertion : contract.testOf(contract.findCell(BIG_MEDIUM).orElseThrow()).allAssertions())
		{
			passed.put(assertion.id(), new PairCounts(0, 8, 0, 0));
			below.put(assertion.id(), new PairCounts(0, 0, 8, 0));
		}
		Builder start = new Builder().confirmed(contract, 1).working(1);
		Campaign ended = start.certificationAttempt(1).boundaries(paired(start)).cell(BIG_MEDIUM, 100, 0).
			controlPairing(BIG_MEDIUM, new Pairing(List.of(), passed)).
			incumbentPairing(BIG_MEDIUM, new Pairing(List.of(), below)).outcome(Outcome.BELOW_INCUMBENT).build();
		requireThat(ids(CellTier.developmentCells(ended.contract(), ended.lineage().number(), ended.attempts())),
			"below").isEqualTo(List.of(BIG_MEDIUM, SMALL_HIGH, SAFETY));
	}

	/**
	 * Returns the standard boundaries of a campaign's certification scope at a minimum pass rate below 0.95, under
	 * which a certified minimum-pass-rate part still waits for the control and incumbent comparisons.
	 *
	 * @param start the builder of the campaign, whose contract is confirmed
	 * @return the boundaries
	 */
	private static DecisionBoundaries paired(Builder start)
	{
		return Boundaries.standard(CellTier.scopeCellIds(start.build().contract()).size(),
			Boundaries.PAIRED_MINIMUM_PASS_RATE);
	}
}
