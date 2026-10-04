package io.github.cowwoc.cat.common.optimizer.campaign;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import static io.github.cowwoc.cat.common.optimizer.contract.TierFixtures.BIG_HIGH;
import static io.github.cowwoc.cat.common.optimizer.contract.TierFixtures.BIG_MEDIUM;
import static io.github.cowwoc.cat.common.optimizer.contract.TierFixtures.SAFETY;
import static io.github.cowwoc.cat.common.optimizer.contract.TierFixtures.SMALL_HIGH;

import io.github.cowwoc.cat.common.optimizer.contract.Category;
import io.github.cowwoc.cat.common.optimizer.contract.CertificationScope;
import io.github.cowwoc.cat.common.optimizer.contract.Contract;
import io.github.cowwoc.cat.common.optimizer.contract.ContractFixtures;
import io.github.cowwoc.cat.common.optimizer.contract.DrawStream;
import io.github.cowwoc.cat.common.optimizer.contract.Generator;
import io.github.cowwoc.cat.common.optimizer.contract.RiskRank;
import io.github.cowwoc.cat.common.optimizer.contract.TierFixtures;
import io.github.cowwoc.cat.common.optimizer.contract.TrialInstance;
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
	private static final Sha256 WORKING = Builder.snapshot("candidate-1");

	/**
	 * Creates the test.
	 */
	public SchedulerTest()
	{
	}

	/**
	 * An extended streak schedules one targeted promotion run, and an assigned extension resumes under its key.
	 */
	// Requirement: R-D3
	@Test
	public void extendedStreakSchedulesAndResumes()
	{
		Sha256 snapshot = Builder.snapshot("extended");
		Campaign campaign = TransitionSteps.submit(PromotionTest.streakRound(1), "safety", snapshot, List.of());
		TrialInstance instance = campaign.development().author().runs().getFirst().instance();
		campaign = TransitionSteps.adopt(campaign, Purpose.PROMOTION, 1, snapshot, instance, Verdict.PASS);
		campaign = TransitionSteps.adopt(campaign, Purpose.PROMOTION, 1, snapshot, instance, Verdict.PASS);
		List<Admission> plan = Scheduler.plan(campaign, NOW, 1);
		requireThat(plan, "extensionPlan").size().isEqualTo(1);
		Admission.Assign extension = (Admission.Assign) plan.getFirst();
		requireThat(extension.purpose(), "purpose").isEqualTo(Purpose.PROMOTION);
		requireThat(extension.instance().targeted(), "targeted").isTrue();
		requireThat(extension.allocation().rule(), "allocation").isEqualTo(AllocationRule.TARGETED);
		campaign = Transitions.apply(campaign, new CampaignEvent.Assign(extension));
		TrialKey key = TransitionSteps.last(campaign);
		requireThat(Scheduler.plan(campaign, NOW, 1), "resume").isEqualTo(List.of(new Admission.Rerun(key)));
		requireThat(campaign.development().count("safety"), "counts").isEqualTo(Builder.counts(60, 1));
		requireThat(campaign.attempts(), "allowance").isEmpty();
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
	 * @param cell the identifier of the cell receiving the trial
	 * @return the admission
	 */
	private static Admission development(String cell)
	{
		return development(cell, AllocationRule.LOWEST_EXPLORATORY_BOUND);
	}

	/**
	 * Returns a development admission for working candidate 1 in series 1.
	 *
	 * @param cell the identifier of the cell receiving the trial
	 * @param rule the allocation rule that chose the cell
	 * @return the admission
	 */
	private static Admission development(String cell, AllocationRule rule)
	{
		return new Admission.Assign(Purpose.DEVELOPMENT, 1, WORKING, cell, null, allocation(rule));
	}

	/**
	 * Returns a formal admission for attempt 1 of working candidate 1.
	 *
	 * @param cell the identifier of the cell receiving the trial
	 * @return the admission
	 */
	private static Admission formal(String cell)
	{
		return new Admission.Assign(Purpose.FORMAL, 1, WORKING, cell, null, allocation(AllocationRule.FORMAL));
	}

	/**
	 * Returns a builder of a confirmed campaign whose working candidate 1 has replaced the incumbent, and whose
	 * baseline is met, so that the baseline arm stays idle.
	 *
	 * @return the builder
	 */
	private static Builder working()
	{
		return new Builder().confirmed().working(1).readyBaseline();
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
			cells.add(admission.cell());
		return cells;
	}

	/**
	 * Verifies that targeted work comes first: reruns of assigned trials, then the diagnostic rerun.
	 */
	@Test
	public void targetedWorkComesFirst()
	{
		// Requirement: R-E3
		TrialInstance logs = Builder.targeted("safety", "logs");
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
		TrialInstance logs = Builder.targeted("safety", "logs");
		TrialInstance formal = Builder.targeted("formatting", "formal");
		Sha256 attempt = Builder.snapshot("attempt");
		Campaign campaign = working().failure(logs).regression(formal, null).
			round(new Round(Map.of("safety", 0))).build();
		campaign = TransitionSteps.submit(campaign, "safety", attempt, List.of());
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
		Campaign campaign = new Builder().confirmed().working(1).development("formatting", 60, 0).
			development("safety", 60, 1).evidenceRevision(173).build();
		List<Admission> expected = List.of(
			new Admission.Assign(Purpose.DEVELOPMENT, 1, WORKING, "safety", null,
				new Allocation(AllocationRule.LOWEST_EXPLORATORY_BOUND, 173, later)),
			new Admission.Assign(Purpose.BASELINE, 1, Builder.INCUMBENT, "formatting", null,
				new Allocation(AllocationRule.BASELINE_ARM, 173, later)),
			new Admission.Assign(Purpose.BASELINE, 1, Builder.INCUMBENT, "safety", null,
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
		Campaign formattingFirst = new Builder().confirmed(equalRisk("formatting", "safety"), 1).working(1).
			readyBaseline().build();
		requireThat(Scheduler.plan(formattingFirst, NOW, 8), "formattingFirst").
			isEqualTo(List.of(development("formatting")));
		Campaign safetyFirst = new Builder().confirmed(equalRisk("safety", "formatting"), 1).working(1).
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
		campaign = Transitions.apply(campaign, new CampaignEvent.Start(TransitionSteps.last(campaign)));
		requireThat(cellIds(Scheduler.plan(campaign, NOW, 8)), "plan").isEqualTo(List.of("safety"));
	}

	/**
	 * Verifies that the formal phase first gives each category one trial.
	 */
	@Test
	public void formalGivesEachCellOneTrial()
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
			certificationAttempt(1).cell("formatting", 50, 0).cell("safety", 50, 1).build();
		requireThat(Scheduler.plan(lower, NOW, 8), "lowerBound").isEqualTo(List.of(formal("safety")));

		Campaign fewer = working().slowStart(new SlowStart(1, 0)).
			certificationAttempt(1).cell("formatting", 0, 2).cell("safety", 0, 1).build();
		requireThat(Scheduler.plan(fewer, NOW, 8), "fewerOutcomes").isEqualTo(List.of(formal("safety")));

		Campaign order = working().slowStart(new SlowStart(1, 0)).
			certificationAttempt(1).cell("formatting", 50, 0).cell("safety", 50, 0).build();
		requireThat(Scheduler.plan(order, NOW, 8), "categoryOrder").isEqualTo(List.of(formal("formatting")));
	}

	/**
	 * Verifies that a decided cell receives no new formal trials and an undecided cell's remaining passes to
	 * certification limit its unadopted trials.
	 */
	@Test
	public void formalRespectsDecisionsAndCellLimit()
	{
		// Requirement: R-E3
		Campaign campaign = working().development("formatting", 200, 0).
			development("safety", 200, 0).slowStart(new SlowStart(8, 0)).certificationAttempt(1).
			cell("formatting", 100, 0).cell("safety", 97, 0).build();
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
		Builder builder = working().development("formatting", 10, 0);
		requireThat(Scheduler.plan(builder.build(), NOW, 8).isEmpty(), "open").isFalse();
		requireThat(Scheduler.plan(builder.build(), NOW, 0), "noCapacity").isEmpty();
		requireThat(Scheduler.plan(new Builder().working(1).build(), NOW, 8), "unconfirmed").isEmpty();
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
		Campaign development = new Builder().confirmed(contract, 1).working(1).readyBaseline().
			development(ContractFixtures.BIG_CELL, 60, 0).development(ContractFixtures.SMALL_CELL, 60, 1).
			development("safety", 100, 0).slowStart(new SlowStart(2, 0)).build();
		requireThat(cellIds(Scheduler.plan(development, NOW, 8)), "development").
			isEqualTo(List.of(ContractFixtures.SMALL_CELL, ContractFixtures.SMALL_CELL));

		Campaign formal = new Builder().confirmed(contract, 1).working(1).readyBaseline().
			slowStart(new SlowStart(1, 0)).certificationAttempt(1).cell(ContractFixtures.BIG_CELL, 50, 0).
			cell("safety", 50, 0).build();
		requireThat(cellIds(Scheduler.plan(formal, NOW, 8)), "formal").
			isEqualTo(List.of(ContractFixtures.SMALL_CELL));
	}

	/**
	 * Returns a builder of a confirmed campaign of {@link TierFixtures#tieredContract()} whose working candidate 1
	 * has replaced the incumbent, and whose baseline is met, so that the baseline arm stays idle.
	 *
	 * @return the builder
	 */
	private static Builder tiered()
	{
		return new Builder().confirmed(TierFixtures.tieredContract(), 1).working(1).readyBaseline();
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
		campaign = Transitions.apply(campaign, new CampaignEvent.Assign(next));
		return TransitionSteps.run(campaign, TransitionSteps.last(campaign), verdict);
	}

	/**
	 * Returns a builder of a confirmed campaign of {@link TierFixtures#tieredContract()} under a certification scope,
	 * whose working candidate 1 has replaced the incumbent.
	 *
	 * @param scope the certification scope
	 * @return the builder
	 */
	private static Builder tiered(CertificationScope scope)
	{
		return new Builder().confirmed(TierFixtures.tieredContract().withScope(scope), 1).working(1);
	}

	/**
	 * Returns a development failure of the tiered contract's {@code formatting} category.
	 *
	 * @param cell the identifier of the failing cell
	 * @return the failure's instance
	 */
	private static TrialInstance tieredFailure(String cell)
	{
		return new Generator(TierFixtures.tieredContract()).draw(cell,
			DrawStream.create(DrawStream.Kind.DEVELOPMENT, 1));
	}

	/**
	 * Verifies that development assigns representative trials only to development cells: each category's weakest
	 * cell, plus a cell where a certification attempt of the lineage adopted a failure. The other cells are empty and
	 * would have the lowest bound.
	 */
	@Test
	public void developmentSamplesOnlyDevelopmentCells()
	{
		// Requirement: R-E19
		// Requirement: R-E3
		List<String> developmentCells = List.of(SMALL_HIGH, SAFETY);
		for (String cell : developmentCells)
		{
			Builder builder = tiered().slowStart(new SlowStart(1, 0));
			for (String other : developmentCells)
				builder.development(other, 100, 0);
			Campaign campaign = builder.development(cell, 60, 0).build();
			requireThat(Scheduler.plan(campaign, NOW, 8), cell).isEqualTo(List.of(development(cell)));
		}

		Builder certified = tiered().slowStart(new SlowStart(1, 0));
		for (String cell : developmentCells)
			certified.development(cell, 100, 0);
		Campaign campaign = certified.development(BIG_MEDIUM, 60, 0).certificationAttempt(1).
			failure(tieredFailure(BIG_MEDIUM)).outcome(Outcome.INCONCLUSIVE).build();
		requireThat(Scheduler.plan(campaign, NOW, 8), "certificationFailure").
			isEqualTo(List.of(development(BIG_MEDIUM)));
	}

	/**
	 * Verifies that the coverage floor applies among the development cells only: a development cell 50 assignments
	 * ahead of the least-sampled development cell is excluded, and one 49 ahead is not, although the other cells have
	 * no assignments.
	 */
	@Test
	public void coverageFloorAmongDevelopmentCells()
	{
		// Requirement: R-E19
		// Requirement: R-E3
		// safety has the lower Wilson bound, but is 50 assignments ahead of the weakest formatting cell
		Campaign ahead = tiered().development(SMALL_HIGH, 45, 0).development(SAFETY, 90, 5).
			slowStart(new SlowStart(1, 0)).build();
		requireThat(Scheduler.plan(ahead, NOW, 8), "atLead").
			isEqualTo(List.of(development(SMALL_HIGH, AllocationRule.COVERAGE_FLOOR)));

		Campaign within = tiered().development(SMALL_HIGH, 46, 0).development(SAFETY, 90, 5).
			slowStart(new SlowStart(1, 0)).build();
		requireThat(Scheduler.plan(within, NOW, 8), "withinLead").isEqualTo(List.of(development(SAFETY)));
	}

	/**
	 * Verifies that the first working candidate samples only development cells, with no screen of the other cells,
	 * and that development assigns nothing once every development cell is ready, however empty the other cells are.
	 */
	@Test
	public void noScreenAndNoWidening()
	{
		// Requirement: R-E19
		Contract contract = TierFixtures.tieredContract();
		Campaign campaign = new Builder().confirmed(contract, 1).build();
		for (int trial = 0; trial < 18; ++trial)
			campaign = runNext(campaign, Verdict.PASS);
		requireThat(campaign.development().counts().keySet(), "sampled").isEqualTo(Set.of(SMALL_HIGH, SAFETY));

		Campaign ready = tiered().development(SMALL_HIGH, 100, 0).development(SAFETY, 100, 0).
			slowStart(new SlowStart(1, 0)).build();
		requireThat(Scheduler.plan(ready, NOW, 8), "ready").isEmpty();
	}

	/**
	 * Verifies that a formal failure in a cell that is not a development cell makes it one for the rest of the
	 * lineage.
	 */
	@Test
	public void formalFailureAddsDevelopmentCell()
	{
		// Requirement: R-E19
		Builder.AttemptBuilder attempt = tiered().slowStart(new SlowStart(1, 0)).certificationAttempt(1);
		for (String cell : TierFixtures.tieredContract().cellIds())
			attempt.cell(cell, 50, 0);
		Campaign campaign = attempt.cell(BIG_HIGH, 50, 1).build();
		requireThat(CellTier.developmentCellIds(campaign), "before").isEqualTo(List.of(SMALL_HIGH, SAFETY));
		requireThat(Scheduler.plan(campaign, NOW, 8), "formal").isEqualTo(List.of(formal(BIG_HIGH)));
		campaign = TransitionSteps.assign(campaign, Purpose.FORMAL, BIG_HIGH);
		campaign = TransitionSteps.run(campaign, TransitionSteps.last(campaign), Verdict.FAIL);
		requireThat(CellTier.developmentCellIds(campaign), "after").isEqualTo(List.of(BIG_HIGH, SMALL_HIGH, SAFETY));
	}

	/**
	 * Verifies that formal and baseline trials run in the certification scope's cells only: the weakest cell of each
	 * category under the weakest-cell scope, and every cell of the latest versions under the latest-versions scope.
	 */
	@Test
	public void formalAndBaselineCellsPerScope()
	{
		// Requirement: R-C15
		// Requirement: R-D7
		// Requirement: R-E3
		Campaign weakestFormal = tiered(CertificationScope.WEAKEST_CELL).readyBaseline().
			slowStart(new SlowStart(1, 0)).certificationAttempt(1).cell(SMALL_HIGH, 50, 0).cell(SAFETY, 50, 1).
			build();
		requireThat(weakestFormal.runningAttempt().orElseThrow().procedure().cells(), "weakestProcedure").
			isEqualTo(List.of(SMALL_HIGH, SAFETY));
		requireThat(Scheduler.plan(weakestFormal, NOW, 8), "weakestFormal").isEqualTo(List.of(formal(SAFETY)));

		requireThat(baselineCells(tiered(CertificationScope.WEAKEST_CELL).build()), "weakestBaseline").
			isEqualTo(Set.of(SMALL_HIGH, SAFETY));
		requireThat(baselineCells(tiered(CertificationScope.LATEST_VERSIONS).build()), "latestBaseline").
			isEqualTo(Set.copyOf(TierFixtures.tieredContract().cellIds()));
	}

	/**
	 * Returns the cells of a plan's baseline admissions.
	 *
	 * @param campaign the campaign
	 * @return the cells
	 */
	private static Set<String> baselineCells(Campaign campaign)
	{
		Set<String> cells = new LinkedHashSet<>();
		for (Admission admission : Scheduler.plan(campaign, NOW, 64))
		{
			if (admission instanceof Admission.Assign assign && assign.purpose() == Purpose.BASELINE)
				cells.add(assign.cell());
		}
		return cells;
	}

	/**
	 * Verifies that after an edit, the new working candidate starts again with counts at zero and receives
	 * representative trials only in the development cells, including a cell where certification failed.
	 */
	// Requirement: R-D3
	@Test
	public void restartInDevelopmentCellsAfterEdit()
	{
		// Requirement: R-E19
		Contract contract = TierFixtures.tieredContract();
		TrialInstance failing = new Generator(contract).markTargeted(tieredFailure(BIG_MEDIUM));
		Campaign campaign = tiered().development(SMALL_HIGH, 100, 0).development(SAFETY, 100, 0).
			development(BIG_MEDIUM, 0, 1).failure(failing).round(new Round(Map.of(BIG_MEDIUM, 0))).
			certificationAttempt(1).failure(tieredFailure(BIG_MEDIUM)).outcome(Outcome.INCONCLUSIVE).build();
		campaign = TransitionSteps.submit(campaign, BIG_MEDIUM, Builder.snapshot("attempt"), List.of());
		while (Promotion.decide(campaign.development().author(), campaign.lineage().regressionSet(),
			campaign.development().instanceOutcomes()) == Promotion.Decision.PENDING)
		{
			campaign = runNext(campaign, Verdict.PASS);
		}
		campaign = Transitions.apply(campaign, new CampaignEvent.AdvanceMinimality(Builder.snapshot("attempt")));
		requireThat(campaign.development().working().number(), "working").isEqualTo(2);
		requireThat(campaign.development().counts(), "counts").isEmpty();

		Set<String> sampled = new LinkedHashSet<>();
		for (int trial = 0; trial < 30; ++trial)
		{
			campaign = runNext(campaign, Verdict.PASS);
			sampled.addAll(campaign.development().counts().keySet());
		}
		requireThat(sampled, "sampled").isEqualTo(Set.of(BIG_MEDIUM, SMALL_HIGH, SAFETY));
	}
}
