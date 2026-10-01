package io.github.cowwoc.cat.common.optimizer.campaign;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.optimizer.contract.Category;
import io.github.cowwoc.cat.common.optimizer.contract.Contract;
import io.github.cowwoc.cat.common.optimizer.contract.ContractFixtures;
import io.github.cowwoc.cat.common.optimizer.contract.RiskRank;
import io.github.cowwoc.cat.common.optimizer.contract.TrialInstance;
import io.github.cowwoc.cat.common.optimizer.inventory.Sha256;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
	 * @param category the category
	 * @return the admission
	 */
	private static Admission development(String category)
	{
		return development(category, AllocationRule.LOWEST_EXPLORATORY_BOUND);
	}

	/**
	 * Returns a development admission for working candidate 1 in series 1.
	 *
	 * @param category the category
	 * @param rule the allocation rule that chose the category
	 * @return the admission
	 */
	private static Admission development(String category, AllocationRule rule)
	{
		return new Admission.Assign(Purpose.DEVELOPMENT, 1, WORKING, category, null, allocation(rule));
	}

	/**
	 * Returns a formal admission for attempt 1 of working candidate 1.
	 *
	 * @param category the category
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
	 * Returns the categories of a plan.
	 *
	 * @param plan the plan
	 * @return the categories in order
	 */
	private static List<String> categories(List<Admission> plan)
	{
		List<String> categories = new ArrayList<>();
		for (Admission admission : plan)
			categories.add(admission.category());
		return categories;
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
		campaign = CampaignTransitions.apply(campaign, new CampaignEvent.SubmitAuthorAttempt("safety", attempt));
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
	 * Verifies the development coverage floor: no category gets more than 50 representative assignments ahead of the
	 * least-sampled eligible category.
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
	 * Verifies that development admits the category with the lowest Wilson lower bound.
	 */
	@Test
	public void lowestWilsonBoundFirst()
	{
		// Requirement: R-E3
		Campaign campaign = working().development("formatting", 60, 0).
			development("safety", 60, 1).slowStart(new SlowStart(2, 0)).build();
		requireThat(categories(Scheduler.plan(campaign, NOW, 8)), "plan").isEqualTo(List.of("safety", "safety"));
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

		// formatting has no passes in 1 outcome; safety has none at all: both lower bounds are 0
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
		requireThat(categories(Scheduler.plan(campaign, NOW, 8)), "plan").isEqualTo(List.of("safety"));
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
	 * Verifies that the formal phase then admits the category furthest from certifying, with ties going to fewer
	 * outcomes and then category order.
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
	 * Verifies that a decided category is no longer admitted and that the category limit bounds in-flight trials.
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
		requireThat(categories(Scheduler.plan(development, NOW, 8)), "development").
			isEqualTo(List.of(ContractFixtures.SMALL_CELL, ContractFixtures.SMALL_CELL));

		Campaign formal = new CampaignBuilder().confirmed(contract, 1).working(1).readyBaseline().
			slowStart(new SlowStart(1, 0)).certificationAttempt(1).category(ContractFixtures.BIG_CELL, 50, 0).
			category("safety", 50, 0).build();
		requireThat(categories(Scheduler.plan(formal, NOW, 8)), "formal").
			isEqualTo(List.of(ContractFixtures.SMALL_CELL));
	}
}
