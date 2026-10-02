package io.github.cowwoc.cat.common.optimizer.campaign;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import static io.github.cowwoc.cat.common.optimizer.contract.TierFixtures.BIG_HIGH;
import static io.github.cowwoc.cat.common.optimizer.contract.TierFixtures.BIG_LOW;
import static io.github.cowwoc.cat.common.optimizer.contract.TierFixtures.BIG_MEDIUM;
import static io.github.cowwoc.cat.common.optimizer.contract.TierFixtures.BIG_XHIGH;
import static io.github.cowwoc.cat.common.optimizer.contract.TierFixtures.SAFETY;
import static io.github.cowwoc.cat.common.optimizer.contract.TierFixtures.SMALL_HIGH;

import io.github.cowwoc.cat.common.optimizer.contract.Category;
import io.github.cowwoc.cat.common.optimizer.contract.Cell;
import io.github.cowwoc.cat.common.optimizer.contract.Contract;
import io.github.cowwoc.cat.common.optimizer.contract.ContractFixtures;
import io.github.cowwoc.cat.common.optimizer.contract.DrawStream;
import io.github.cowwoc.cat.common.optimizer.contract.Generator;
import io.github.cowwoc.cat.common.optimizer.contract.RiskRank;
import io.github.cowwoc.cat.common.optimizer.contract.TierFixtures;
import io.github.cowwoc.cat.common.optimizer.contract.TrialInstance;
import io.github.cowwoc.cat.common.optimizer.contract.TrialProfile;
import io.github.cowwoc.cat.common.optimizer.inventory.Sha256;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.testng.annotations.Test;

/**
 * Tests {@link Scheduler}.
 */
public final class SchedulerTest
{
	private static final Instant NOW = Instant.parse("2026-10-01T12:00:00Z");
	private static final Sha256 WORKING = CampaignBuilder.snapshot("candidate-1");

	/**
	 * Creates the test.
	 */
	public SchedulerTest()
	{
	}

	/**
	 * Returns the allocation decision of a rule that used no adopted outcomes, made at {@link #NOW}.
	 *
	 * @param rule the allocation rule
	 * @return the allocation decision
	 */
	private static Allocation allocation(AllocationRule rule)
	{
		return new Allocation(rule, 0, NOW);
	}

	/**
	 * Returns a development admission for working candidate 1 in series 1, chosen by the lowest exploratory lower
	 * bound.
	 *
	 * @param category the identifier of the cell receiving the trial
	 * @return the admission
	 */
	private static Admission development(String category)
	{
		return development(category, AllocationRule.LOWEST_EXPLORATORY_BOUND);
	}

	/**
	 * Returns a development admission for working candidate 1 in series 1.
	 *
	 * @param category the identifier of the cell receiving the trial
	 * @param rule the allocation rule that chose the cell
	 * @return the admission
	 */
	private static Admission development(String category, AllocationRule rule)
	{
		return new Admission.Assign(Purpose.DEVELOPMENT, 1, WORKING, category, null, allocation(rule));
	}

	/**
	 * Returns a formal admission for attempt 1 of working candidate 1.
	 *
	 * @param category the identifier of the cell receiving the trial
	 * @return the admission
	 */
	private static Admission formal(String category)
	{
		return new Admission.Assign(Purpose.FORMAL, 1, WORKING, category, null, allocation(AllocationRule.FORMAL));
	}

	/**
	 * Returns a builder of a confirmed campaign whose working candidate 1 has replaced the incumbent, and whose
	 * baseline is met, so that the baseline arm stays idle.
	 *
	 * @return the builder
	 */
	private static CampaignBuilder working()
	{
		return new CampaignBuilder().confirmed().working(1).readyBaseline();
	}

	/**
	 * Returns the cell identifiers of a plan's admissions.
	 *
	 * @param plan the plan
	 * @return the cell identifiers in admission order
	 */
	private static List<String> cellIds(List<Admission> plan)
	{
		List<String> cells = new ArrayList<>();
		for (Admission admission : plan)
			cells.add(admission.category());
		return cells;
	}

	/**
	 * Verifies that targeted work comes first: reruns of assigned trials, then the diagnostic rerun.
	 */
	@Test
	public void targetedWorkComesFirst()
	{
		// Requirement: R-E3
		TrialInstance logs = CampaignBuilder.targeted("safety", "logs");
		Campaign campaign = working().development("formatting", 60, 0).
			development("safety", 60, 1).failure(logs).diagnosis(new Diagnosis(List.of(logs), 0, null, false)).
			slowStart(new SlowStart(4, 0)).build();
		campaign = TransitionSteps.assign(campaign, Purpose.DEVELOPMENT, "formatting");
		TrialKey assigned = TransitionSteps.last(campaign);
		requireThat(Scheduler.plan(campaign, NOW, 8), "plan").isEqualTo(List.of(new Admission.Rerun(assigned),
			new Admission.Assign(Purpose.DIAGNOSTIC, 1, WORKING, "safety", logs, allocation(AllocationRule.TARGETED))));
		requireThat(Scheduler.plan(campaign, NOW, 1), "capacityOne").isEqualTo(List.of(
			new Admission.Rerun(assigned)));
	}

	/**
	 * Verifies that the promotion runs of an author attempt are admitted, each with its snapshot.
	 */
	@Test
	public void promotionRunsAreAdmitted()
	{
		// Requirement: R-E3
		TrialInstance logs = CampaignBuilder.targeted("safety", "logs");
		TrialInstance formal = CampaignBuilder.targeted("formatting", "formal");
		Sha256 attempt = CampaignBuilder.snapshot("attempt");
		Campaign campaign = working().failure(logs).regression(formal, null).
			round(new Round(Map.of("safety", 0))).build();
		campaign = CampaignTransitions.apply(campaign, new CampaignEvent.SubmitAuthorAttempt("safety", attempt, List.of()));
		Allocation targeted = allocation(AllocationRule.TARGETED);
		requireThat(Scheduler.plan(campaign, NOW, 8), "plan").isEqualTo(List.of(
			new Admission.Assign(Purpose.PROMOTION, 1, attempt, "safety", logs, targeted),
			new Admission.Assign(Purpose.PROMOTION, 1, attempt, "safety", logs, targeted),
			new Admission.Assign(Purpose.PROMOTION, 1, attempt, "formatting", formal, targeted),
			new Admission.Assign(Purpose.PROMOTION, 1, WORKING, "formatting", formal, targeted)));

		campaign = TransitionSteps.assign(campaign, Purpose.PROMOTION, 1, attempt, logs);
		requireThat(Scheduler.plan(campaign, NOW, 8), "afterAssign").isEqualTo(List.of(
			new Admission.Rerun(TransitionSteps.last(campaign)),
			new Admission.Assign(Purpose.PROMOTION, 1, attempt, "safety", logs, targeted),
			new Admission.Assign(Purpose.PROMOTION, 1, attempt, "formatting", formal, targeted),
			new Admission.Assign(Purpose.PROMOTION, 1, WORKING, "formatting", formal, targeted)));
	}

	/**
	 * Verifies that development excludes a cell when it leads the least-sampled eligible cell by 50 assignments,
	 * but can choose it when that lead is 49.
	 */
	@Test
	public void coverageFloorLimitsLead()
	{
		// Requirement: R-E3
		// safety has the lower Wilson bound, but is 50 assignments ahead of formatting
		Campaign ahead = working().development("formatting", 40, 0).development("safety", 85, 5).
			slowStart(new SlowStart(1, 0)).build();
		requireThat(Scheduler.plan(ahead, NOW, 8), "atLead").isEqualTo(List.of(development("formatting",
			AllocationRule.COVERAGE_FLOOR)));

		Campaign within = working().development("formatting", 41, 0).development("safety", 85, 5).
			slowStart(new SlowStart(1, 0)).build();
		requireThat(Scheduler.plan(within, NOW, 8), "withinLead").isEqualTo(List.of(development("safety")));
	}

	/**
	 * Verifies that each new assignment records the rule that chose it, the evidence revision of the state it was
	 * planned from, and the planning time.
	 */
	@Test
	public void assignmentsRecordAllocationDecision()
	{
		// Requirement: R-E3, R-E1
		Instant later = NOW.plusSeconds(90);
		Campaign campaign = new CampaignBuilder().confirmed().working(1).development("formatting", 60, 0).
			development("safety", 60, 1).evidenceRevision(173).build();
		List<Admission> expected = List.of(
			new Admission.Assign(Purpose.DEVELOPMENT, 1, WORKING, "safety", null,
				new Allocation(AllocationRule.LOWEST_EXPLORATORY_BOUND, 173, later)),
			new Admission.Assign(Purpose.BASELINE, 1, CampaignBuilder.INCUMBENT, "formatting", null,
				new Allocation(AllocationRule.BASELINE_ARM, 173, later)),
			new Admission.Assign(Purpose.BASELINE, 1, CampaignBuilder.INCUMBENT, "safety", null,
				new Allocation(AllocationRule.BASELINE_ARM, 173, later)));
		requireThat(Scheduler.plan(campaign, later, 3), "plan").isEqualTo(expected);
	}

	/**
	 * Returns the fixture contract with both categories at the same risk rank, in a given order.
	 *
	 * @param order the category identifiers in contract order
	 * @return the contract
	 */
	private static Contract equalRisk(String... order)
	{
		Contract fixture = ContractFixtures.contract();
		List<Category> categories = new ArrayList<>();
		for (String id : order)
		{
			Category category = fixture.findCategory(id).orElseThrow();
			categories.add(new Category(category.id(), category.rationale(), new RiskRank(1), category.tests()));
		}
		return new Contract(fixture.requirements(), categories,
			ContractFixtures.profiles(fixture.requirements(), categories), fixture.judge(), fixture.fixtures(),
			fixture.limitations());
	}

	/**
	 * Verifies that categories tied on bound, outcomes and risk rank are admitted in contract order.
	 */
	@Test
	public void equalRiskRanksFollowContractOrder()
	{
		// Requirement: R-E3
		Campaign formattingFirst = new CampaignBuilder().confirmed(equalRisk("formatting", "safety"), 1).working(1).
			readyBaseline().build();
		requireThat(Scheduler.plan(formattingFirst, NOW, 8), "formattingFirst").
			isEqualTo(List.of(development("formatting")));
		Campaign safetyFirst = new CampaignBuilder().confirmed(equalRisk("safety", "formatting"), 1).working(1).
			readyBaseline().build();
		requireThat(Scheduler.plan(safetyFirst, NOW, 8), "safetyFirst").isEqualTo(List.of(development("safety")));
	}

	/**
	 * Verifies that development admits the cell with the lowest Wilson lower bound.
	 */
	@Test
	public void lowestWilsonBoundFirst()
	{
		// Requirement: R-E3
		Campaign campaign = working().development("formatting", 60, 0).
			development("safety", 60, 1).slowStart(new SlowStart(2, 0)).build();
		requireThat(cellIds(Scheduler.plan(campaign, NOW, 8)), "plan").isEqualTo(List.of("safety", "safety"));
	}

	/**
	 * Verifies the development tie-breaks: fewer outcomes, then the contract's risk rank.
	 */
	@Test
	public void developmentTieBreaks()
	{
		// Requirement: R-E3
		Campaign empty = working().build();
		requireThat(Scheduler.plan(empty, NOW, 8), "riskRank").isEqualTo(List.of(development("safety")));

		// safety has no passes in 1 outcome; formatting has no outcomes: both lower bounds are 0
		Campaign fewer = working().development("safety", 0, 1).build();
		requireThat(Scheduler.plan(fewer, NOW, 8), "fewerOutcomes").isEqualTo(List.of(development("formatting")));
	}

	/**
	 * Verifies that the window limits the working candidate's in-flight trials.
	 */
	@Test
	public void windowLimitsInFlightTrials()
	{
		// Requirement: R-E3
		Campaign campaign = working().development("formatting", 60, 0).
			development("safety", 60, 1).slowStart(new SlowStart(2, 0)).build();
		campaign = TransitionSteps.assign(campaign, Purpose.DEVELOPMENT, "formatting");
		campaign = CampaignTransitions.apply(campaign, new CampaignEvent.Start(TransitionSteps.last(campaign)));
		requireThat(cellIds(Scheduler.plan(campaign, NOW, 8)), "plan").isEqualTo(List.of("safety"));
	}

	/**
	 * Verifies that the formal phase first gives each category one trial.
	 */
	@Test
	public void formalGivesEachCategoryOneTrial()
	{
		// Requirement: R-E3
		Campaign campaign = working().slowStart(new SlowStart(1, 0)).
			certificationAttempt(1).build();
		requireThat(Scheduler.plan(campaign, NOW, 8), "first").isEqualTo(List.of(formal("formatting")));
		campaign = TransitionSteps.assign(campaign, Purpose.FORMAL, "formatting");
		// Development outcomes of the same snapshot raise the risk ceiling to allow a second trial
		campaign = working().development("formatting", 60, 0).slowStart(new SlowStart(2, 0)).
			trial(campaign.trials().getFirst()).certificationAttempt(1).build();
		requireThat(Scheduler.plan(campaign, NOW, 8), "second").isEqualTo(List.of(
			new Admission.Rerun(TransitionSteps.last(campaign)), formal("safety")));
	}

	/**
	 * Verifies that formal trials prioritize the lowest confidence bound, breaking ties by fewer outcomes and then
	 * contract order.
	 */
	@Test
	public void formalFurthestFromCertifyingFirst()
	{
		// Requirement: R-E3
		Campaign lower = working().slowStart(new SlowStart(1, 0)).
			certificationAttempt(1).category("formatting", 50, 0).category("safety", 50, 1).build();
		requireThat(Scheduler.plan(lower, NOW, 8), "lowerBound").isEqualTo(List.of(formal("safety")));

		Campaign fewer = working().slowStart(new SlowStart(1, 0)).
			certificationAttempt(1).category("formatting", 0, 2).category("safety", 0, 1).build();
		requireThat(Scheduler.plan(fewer, NOW, 8), "fewerOutcomes").isEqualTo(List.of(formal("safety")));

		Campaign order = working().slowStart(new SlowStart(1, 0)).
			certificationAttempt(1).category("formatting", 50, 0).category("safety", 50, 0).build();
		requireThat(Scheduler.plan(order, NOW, 8), "categoryOrder").isEqualTo(List.of(formal("formatting")));
	}

	/**
	 * Verifies that a decided cell receives no new formal trials and an undecided cell's remaining passes to
	 * certification limit its unadopted trials.
	 */
	@Test
	public void formalRespectsDecisionsAndCategoryLimit()
	{
		// Requirement: R-E3
		Campaign campaign = working().development("formatting", 200, 0).
			development("safety", 200, 0).slowStart(new SlowStart(8, 0)).certificationAttempt(1).
			category("formatting", 100, 0).category("safety", 97, 0).build();
		requireThat(Scheduler.plan(campaign, NOW, 8), "plan").isEqualTo(List.of(formal("safety"), formal("safety"),
			formal("safety")));
	}

	/**
	 * Verifies that nothing is admitted while the campaign cannot proceed.
	 */
	@Test
	public void blockedCampaignAdmitsNothing()
	{
		// Requirement: R-E3
		CampaignBuilder builder = working().development("formatting", 10, 0);
		requireThat(Scheduler.plan(builder.build(), NOW, 8).isEmpty(), "open").isFalse();
		requireThat(Scheduler.plan(builder.build(), NOW, 0), "noCapacity").isEmpty();
		requireThat(Scheduler.plan(new CampaignBuilder().working(1).build(), NOW, 8), "unconfirmed").isEmpty();
		requireThat(Scheduler.plan(working().lifecycle(Lifecycle.CANCELLED).
			build(), NOW, 8), "cancelled").isEmpty();
		requireThat(Scheduler.plan(working().revisionRequested().build(), NOW, 8),
			"revision").isEmpty();
		requireThat(Scheduler.plan(working().
			pending(new PendingDecision.UnownedChange("edited CLAUDE.md")).build(), NOW, 8), "pending").isEmpty();
	}

	/**
	 * Verifies that a category under two profiles is scheduled as two cells with separate counts: development admits
	 * the weaker cell, and the formal phase gives each cell its own first trial.
	 */
	@Test
	public void twoCellCategoryIsScheduledPerCell()
	{
		// Requirement: R-C11
		Contract contract = ContractFixtures.twoCellContract();
		Campaign development = new CampaignBuilder().confirmed(contract, 1).working(1).readyBaseline().
			development(ContractFixtures.BIG_CELL, 60, 0).development(ContractFixtures.SMALL_CELL, 60, 1).
			development("safety", 100, 0).slowStart(new SlowStart(2, 0)).build();
		requireThat(cellIds(Scheduler.plan(development, NOW, 8)), "development").
			isEqualTo(List.of(ContractFixtures.SMALL_CELL, ContractFixtures.SMALL_CELL));

		Campaign formal = new CampaignBuilder().confirmed(contract, 1).working(1).readyBaseline().
			slowStart(new SlowStart(1, 0)).certificationAttempt(1).category(ContractFixtures.BIG_CELL, 50, 0).
			category("safety", 50, 0).build();
		requireThat(cellIds(Scheduler.plan(formal, NOW, 8)), "formal").
			isEqualTo(List.of(ContractFixtures.SMALL_CELL));
	}

	/**
	 * Returns a builder of a confirmed campaign of {@link TierFixtures#tieredContract()} whose working candidate 1
	 * has replaced the incumbent, and whose baseline is met, so that the baseline arm stays idle.
	 *
	 * @return the builder
	 */
	private static CampaignBuilder tiered()
	{
		return new CampaignBuilder().confirmed(TierFixtures.tieredContract(), 1).working(1).readyBaseline();
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
	 * Assigns the first new admission of a plan, then runs and completes it.
	 *
	 * @param campaign the campaign
	 * @param verdict the judged outcome
	 * @return the updated campaign
	 */
	private static Campaign runNext(Campaign campaign, Verdict verdict)
	{
		Admission.Assign next = Scheduler.plan(campaign, NOW, 8).stream().
			filter(admission -> admission instanceof Admission.Assign).map(Admission.Assign.class::cast).
			findFirst().orElseThrow();
		campaign = CampaignTransitions.apply(campaign, new CampaignEvent.Assign(next));
		return TransitionSteps.run(campaign, TransitionSteps.last(campaign), verdict);
	}

	/**
	 * Verifies the active cells: the cell of a category that binds one profile, each model's lowest and highest effort
	 * in inventory order, and every cell in which a candidate has failed.
	 */
	@Test
	public void activeCellsBoundLowestHighestFailed()
	{
		// Requirement: R-E19
		Contract contract = TierFixtures.tieredContract();
		requireThat(ids(CellTier.activeCells(contract, Set.of())), "initial").
			isEqualTo(List.of(BIG_LOW, BIG_XHIGH, SMALL_HIGH, SAFETY));
		// A failed cell that the contract no longer lists is ignored
		requireThat(ids(CellTier.activeCells(contract, Set.of(BIG_MEDIUM, "retired@big/low"))), "failed").
			isEqualTo(List.of(BIG_LOW, BIG_MEDIUM, BIG_XHIGH, SMALL_HIGH, SAFETY));
	}

	/**
	 * Verifies that under the recorded Codex inventory of 44 profiles, a category that binds nothing starts with 16
	 * active cells: the lowest and the highest effort of each of the 8 models.
	 */
	@Test
	public void codexUnboundCategoryHasSixteenActive()
	{
		// Requirement: R-E19
		Contract contract = TierFixtures.contract(TierFixtures.CODEX_PROFILES, new TrialProfile("gpt-6.1-sol", "high"));
		List<Cell> formatting = contract.cells().stream().filter(cell -> cell.categoryId().equals("formatting")).
			toList();
		requireThat(formatting.size(), "cells").isEqualTo(44);
		List<String> expected = new ArrayList<>();
		for (Map.Entry<String, List<String>> model : TierFixtures.CODEX_EFFORTS.entrySet())
		{
			expected.add("formatting@" + model.getKey() + "/" + model.getValue().getFirst());
			expected.add("formatting@" + model.getKey() + "/" + model.getValue().getLast());
		}
		List<String> active = CellTier.activeCells(contract, Set.of()).stream().
			filter(cell -> cell.categoryId().equals("formatting")).map(Cell::id).toList();
		requireThat(active, "active").isEqualTo(expected);
		requireThat(active.size(), "activeCount").isEqualTo(16);
	}

	/**
	 * Verifies that development assigns representative trials only to active cells, including a cell that became
	 * active by failing.
	 */
	@Test
	public void developmentSamplesOnlyActiveCells()
	{
		// Requirement: R-E19
		// Requirement: R-E3
		List<String> active = List.of(BIG_LOW, BIG_XHIGH, SMALL_HIGH, SAFETY);
		for (String cell : active)
		{
			CampaignBuilder builder = tiered().slowStart(new SlowStart(1, 0));
			for (String other : active)
				builder.development(other, 100, 0);
			// The inactive cells are empty and would have the lowest bound
			Campaign campaign = builder.development(cell, 60, 0).build();
			requireThat(Scheduler.plan(campaign, NOW, 8), cell).isEqualTo(List.of(development(cell)));
		}

		CampaignBuilder failed = tiered().slowStart(new SlowStart(1, 0)).failedCell(BIG_MEDIUM);
		for (String cell : active)
			failed.development(cell, 100, 0);
		Campaign campaign = failed.development(BIG_MEDIUM, 60, 0).build();
		requireThat(Scheduler.plan(campaign, NOW, 8), "failedCell").isEqualTo(List.of(development(BIG_MEDIUM)));
	}

	/**
	 * Verifies the screen: when development starts, every cell receives 3 representative trials of the first working
	 * candidate, counting unadopted trials; afterwards development samples only active cells; the screen runs once per
	 * campaign; and a cell in which the screen finds a failure becomes active.
	 */
	@Test
	public void screenGivesEveryCellThreeTrials()
	{
		// Requirement: R-E19
		Contract contract = TierFixtures.tieredContract();
		Campaign campaign = new CampaignBuilder().confirmed(contract, 1).build();
		for (int trial = 0; trial < 6 * CellTier.SCREEN_TRIALS; ++trial)
			campaign = runNext(campaign, Verdict.PASS);
		for (String cell : contract.cellIds())
			requireThat(campaign.development().count(cell).outcomes(), cell).isEqualTo(CellTier.SCREEN_TRIALS);
		for (int trial = 0; trial < 20; ++trial)
			campaign = runNext(campaign, Verdict.PASS);
		requireThat(campaign.development().count(BIG_MEDIUM).outcomes(), "bigMedium").
			isEqualTo(CellTier.SCREEN_TRIALS);
		requireThat(campaign.development().count(BIG_HIGH).outcomes(), "bigHigh").isEqualTo(CellTier.SCREEN_TRIALS);

		// The screen goes on while a cell has fewer than 3 trials, ahead of a weaker active cell
		CampaignBuilder screening = new CampaignBuilder().confirmed(contract, 1).slowStart(new SlowStart(1, 0));
		for (String cell : contract.cellIds())
			screening.development(cell, 3, 0);
		Campaign incomplete = screening.development(BIG_MEDIUM, 2, 0).development(SAFETY, 2, 1).build();
		requireThat(cellIds(Scheduler.plan(incomplete, NOW, 8)), "incomplete").isEqualTo(List.of(BIG_MEDIUM));

		// An unadopted trial counts toward the screen
		CampaignBuilder inFlight = new CampaignBuilder().confirmed(contract, 1).slowStart(new SlowStart(2, 0));
		for (String cell : contract.cellIds())
			inFlight.development(cell, 3, 0);
		Campaign started = TransitionSteps.assign(inFlight.development(BIG_MEDIUM, 2, 0).build(),
			Purpose.DEVELOPMENT, BIG_MEDIUM);
		started = CampaignTransitions.apply(started, new CampaignEvent.Start(TransitionSteps.last(started)));
		requireThat(cellIds(Scheduler.plan(started, NOW, 8)), "inFlight").isEqualTo(List.of(SAFETY));

		// Only the first working candidate is screened
		CampaignBuilder later = tiered().slowStart(new SlowStart(1, 0));
		for (String cell : contract.cellIds())
			later.development(cell, 3, 0);
		Campaign second = later.development(BIG_MEDIUM, 2, 0).development(SAFETY, 2, 1).build();
		requireThat(cellIds(Scheduler.plan(second, NOW, 8)), "laterCandidate").isEqualTo(List.of(SAFETY));

		// The screen runs once per campaign: the incumbent's next series, after a contract revision, is not screened
		CampaignBuilder revised = new CampaignBuilder().confirmed(contract, 1).series(2).slowStart(new SlowStart(1, 0));
		for (String cell : contract.cellIds())
			revised.development(cell, 3, 0);
		Campaign nextSeries = revised.development(BIG_MEDIUM, 2, 0).development(SAFETY, 2, 1).build();
		requireThat(cellIds(Scheduler.plan(nextSeries, NOW, 8)), "nextSeries").isEqualTo(List.of(SAFETY));

		// A failure in the screen makes its cell active
		Campaign screened = TransitionSteps.assign(new CampaignBuilder().confirmed(contract, 1).build(),
			Purpose.DEVELOPMENT, BIG_HIGH);
		screened = TransitionSteps.run(screened, TransitionSteps.last(screened), Verdict.FAIL);
		requireThat(screened.failedCells(), "failedCells").isEqualTo(Set.of(BIG_HIGH));
		requireThat(ids(CellTier.activeCells(contract, screened.failedCells())), "active").
			isEqualTo(List.of(BIG_LOW, BIG_HIGH, BIG_XHIGH, SMALL_HIGH, SAFETY));
	}

	/**
	 * Verifies that within the coverage floor, each model's lowest-effort active cell receives representative
	 * assignments first, even ahead of a weaker active cell, until it leads that cell by the floor.
	 */
	@Test
	public void lowestEffortFirstWithinFloor()
	{
		// Requirement: R-E19
		// Requirement: R-E3
		Campaign within = tiered().development(SMALL_HIGH, 100, 0).development(SAFETY, 100, 0).
			development(BIG_LOW, 40, 0).slowStart(new SlowStart(1, 0)).build();
		requireThat(Scheduler.plan(within, NOW, 8), "withinFloor").
			isEqualTo(List.of(development(BIG_LOW, AllocationRule.COVERAGE_FLOOR)));

		Campaign atFloor = tiered().development(SMALL_HIGH, 100, 0).development(SAFETY, 100, 0).
			development(BIG_LOW, 50, 0).slowStart(new SlowStart(1, 0)).build();
		requireThat(Scheduler.plan(atFloor, NOW, 8), "atFloor").isEqualTo(List.of(development(BIG_XHIGH)));
	}

	/**
	 * Verifies widening: once every active cell meets the readiness gate's count conditions, every cell becomes
	 * eligible and the empty cells count as 0; an active cell that misses the count conditions keeps development in
	 * the active cells.
	 */
	@Test
	public void wideningAtCountConditions()
	{
		// Requirement: R-E19
		CampaignBuilder builder = tiered().development(BIG_LOW, 100, 0).development(SMALL_HIGH, 100, 0).
			development(SAFETY, 100, 0).slowStart(new SlowStart(1, 0));
		Campaign campaign = builder.development(BIG_XHIGH, 99, 0).build();
		requireThat(Scheduler.plan(campaign, NOW, 8), "before").isEqualTo(List.of(development(BIG_XHIGH)));
		campaign = TransitionSteps.assign(campaign, Purpose.DEVELOPMENT, BIG_XHIGH);
		campaign = TransitionSteps.run(campaign, TransitionSteps.last(campaign), Verdict.PASS);
		requireThat(campaign.development().widened(), "widened").isTrue();
		requireThat(cellIds(Scheduler.plan(campaign, NOW, 1)), "after").isEqualTo(List.of(BIG_MEDIUM));

		// 98 passes of 100, 2 failures in the second batch: the count conditions are not met
		Campaign missed = builder.development(BIG_XHIGH, 97, List.of(0, 2)).build();
		missed = TransitionSteps.assign(missed, Purpose.DEVELOPMENT, BIG_XHIGH);
		missed = TransitionSteps.run(missed, TransitionSteps.last(missed), Verdict.PASS);
		requireThat(missed.development().widened(), "notWidened").isFalse();
		requireThat(cellIds(Scheduler.plan(missed, NOW, 1)), "notReady").isEqualTo(List.of(BIG_XHIGH));
	}

	/**
	 * Verifies that a failure in a cell that development sampled after widening makes the cell active, and that the
	 * failure is handled like any other development failure.
	 */
	@Test
	public void failureInNewlySampledCellActivatesIt()
	{
		// Requirement: R-E19
		Campaign campaign = tiered().widened().development(BIG_LOW, 100, 0).development(BIG_XHIGH, 100, 0).
			development(SMALL_HIGH, 100, 0).development(SAFETY, 100, 0).slowStart(new SlowStart(1, 0)).build();
		requireThat(Scheduler.plan(campaign, NOW, 8), "plan").isEqualTo(List.of(development(BIG_MEDIUM)));
		campaign = TransitionSteps.assign(campaign, Purpose.DEVELOPMENT, BIG_MEDIUM);
		campaign = TransitionSteps.run(campaign, TransitionSteps.last(campaign), Verdict.FAIL);
		requireThat(campaign.failedCells(), "failedCells").isEqualTo(Set.of(BIG_MEDIUM));
		requireThat(ids(CellTier.activeCells(campaign.contract(), campaign.failedCells())), "active").
			isEqualTo(List.of(BIG_LOW, BIG_MEDIUM, BIG_XHIGH, SMALL_HIGH, SAFETY));
		requireThat(campaign.development().diagnosis(), "diagnosis").isNotNull();
	}

	/**
	 * Verifies that after an edit, the new working candidate starts again with counts at zero, not widened, and
	 * receives representative trials only in the active cells, including the cell that failed.
	 */
	@Test
	public void restartInActiveSetAfterEdit()
	{
		// Requirement: R-E19
		Contract contract = TierFixtures.tieredContract();
		TrialInstance failing = new Generator(contract).markTargeted(new Generator(contract).draw(BIG_MEDIUM,
			DrawStream.create(DrawStream.Kind.DEVELOPMENT, 1)));
		Campaign campaign = tiered().widened().failedCell(BIG_MEDIUM).development(BIG_LOW, 100, 0).
			development(BIG_XHIGH, 100, 0).development(SMALL_HIGH, 100, 0).development(SAFETY, 100, 0).
			development(BIG_MEDIUM, 0, 1).failure(failing).round(new Round(Map.of(BIG_MEDIUM, 0))).build();
		campaign = CampaignTransitions.apply(campaign, new CampaignEvent.SubmitAuthorAttempt(BIG_MEDIUM,
			CampaignBuilder.snapshot("attempt"), List.of()));
		while (Promotion.decide(campaign.development().author(), campaign.lineage().regressionSet()) ==
			Promotion.Decision.PENDING)
		{
			campaign = runNext(campaign, Verdict.PASS);
		}
		campaign = CampaignTransitions.apply(campaign, new CampaignEvent.AdvanceMinimality(
			CampaignBuilder.snapshot("attempt")));
		requireThat(campaign.development().working().number(), "working").isEqualTo(2);
		requireThat(campaign.development().widened(), "widened").isFalse();
		requireThat(campaign.development().counts(), "counts").isEmpty();

		Set<String> sampled = new LinkedHashSet<>();
		while (!sampled.contains(BIG_MEDIUM) || !sampled.contains(BIG_XHIGH))
		{
			campaign = runNext(campaign, Verdict.PASS);
			sampled.addAll(campaign.development().counts().keySet());
		}
		requireThat(sampled, "sampled").isEqualTo(Set.of(BIG_LOW, BIG_MEDIUM, BIG_XHIGH, SMALL_HIGH, SAFETY));
	}

	/**
	 * Verifies that tiering leaves formal allocation, the baseline arm and readiness covering every cell, and that a
	 * formal failure makes its cell active.
	 */
	@Test
	public void formalAndBaselineCoverEveryCell()
	{
		// Requirement: R-E19
		// Requirement: R-E3
		Contract contract = TierFixtures.tieredContract();
		CampaignBuilder.AttemptBuilder attempt = tiered().slowStart(new SlowStart(1, 0)).certificationAttempt(1);
		for (String cell : contract.cellIds())
			attempt.category(cell, 50, 0);
		Campaign formal = attempt.category(BIG_HIGH, 50, 1).build();
		Admission.Assign expected = new Admission.Assign(Purpose.FORMAL, 1, WORKING, BIG_HIGH, null,
			allocation(AllocationRule.FORMAL));
		requireThat(Scheduler.plan(formal, NOW, 8), "formal").isEqualTo(List.of(expected));
		formal = TransitionSteps.assign(formal, Purpose.FORMAL, BIG_HIGH);
		formal = TransitionSteps.run(formal, TransitionSteps.last(formal), Verdict.FAIL);
		requireThat(formal.failedCells(), "formalFailure").isEqualTo(Set.of(BIG_HIGH));

		Campaign baseline = new CampaignBuilder().confirmed(contract, 1).working(1).development(BIG_LOW, 60, 0).
			build();
		Set<String> baselineCells = new LinkedHashSet<>();
		for (Admission admission : Scheduler.plan(baseline, NOW, 16))
		{
			if (admission instanceof Admission.Assign assign && assign.purpose() == Purpose.BASELINE)
				baselineCells.add(assign.category());
		}
		requireThat(baselineCells, "baseline").isEqualTo(Set.copyOf(contract.cellIds()));

		CampaignBuilder ready = tiered().widened();
		for (String cell : contract.cellIds())
			ready.development(cell, 100, 0);
		requireThat(ReadinessGate.isCommitDue(ready.build()), "ready").isTrue();
		requireThat(ReadinessGate.isCommitDue(ready.development(BIG_HIGH, 60, 0).build()), "inactiveNotReady").
			isFalse();
	}
}
