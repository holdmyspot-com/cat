package com.holdmyspot.cat.common.optimizer.campaign.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import static com.holdmyspot.cat.common.optimizer.contract.test.TierFixtures.BIG_HIGH;
import static com.holdmyspot.cat.common.optimizer.contract.test.TierFixtures.BIG_LOW;
import static com.holdmyspot.cat.common.optimizer.contract.test.TierFixtures.BIG_MEDIUM;
import static com.holdmyspot.cat.common.optimizer.contract.test.TierFixtures.SAFETY;
import static com.holdmyspot.cat.common.optimizer.contract.test.TierFixtures.SMALL_HIGH;

import com.holdmyspot.cat.common.optimizer.campaign.Admission;
import com.holdmyspot.cat.common.optimizer.campaign.Allocation;
import com.holdmyspot.cat.common.optimizer.campaign.AllocationRule;
import com.holdmyspot.cat.common.optimizer.campaign.Campaign;
import com.holdmyspot.cat.common.optimizer.campaign.CampaignEvent;
import com.holdmyspot.cat.common.optimizer.campaign.CellTier;
import com.holdmyspot.cat.common.optimizer.campaign.CertificationAttempt;
import com.holdmyspot.cat.common.optimizer.campaign.Pairing;
import com.holdmyspot.cat.common.optimizer.campaign.Pairing.UnpairedTrial;
import com.holdmyspot.cat.common.optimizer.campaign.Diagnosis;
import com.holdmyspot.cat.common.optimizer.campaign.FailedAssertion;
import com.holdmyspot.cat.common.optimizer.campaign.Failure;
import com.holdmyspot.cat.common.optimizer.campaign.Lifecycle;
import com.holdmyspot.cat.common.optimizer.campaign.Outcome;
import com.holdmyspot.cat.common.optimizer.campaign.PairCounts;
import com.holdmyspot.cat.common.optimizer.campaign.PendingDecision;
import com.holdmyspot.cat.common.optimizer.campaign.Promotion;
import com.holdmyspot.cat.common.optimizer.campaign.Purpose;
import com.holdmyspot.cat.common.optimizer.campaign.Reference;
import com.holdmyspot.cat.common.optimizer.campaign.Round;
import com.holdmyspot.cat.common.optimizer.campaign.Scheduler;
import com.holdmyspot.cat.common.optimizer.campaign.SlowStart;
import com.holdmyspot.cat.common.optimizer.campaign.Transitions;
import com.holdmyspot.cat.common.optimizer.campaign.TrialKey;
import com.holdmyspot.cat.common.optimizer.campaign.Verdict;
import com.holdmyspot.cat.common.optimizer.contract.AddedTestCase;
import com.holdmyspot.cat.common.optimizer.contract.PromptTest;
import com.holdmyspot.cat.common.optimizer.contract.CertificationScope;
import com.holdmyspot.cat.common.optimizer.contract.Contract;
import com.holdmyspot.cat.common.optimizer.contract.DrawStream;
import com.holdmyspot.cat.common.optimizer.contract.Generator;
import com.holdmyspot.cat.common.optimizer.contract.RiskRank;
import com.holdmyspot.cat.common.optimizer.contract.TestCase;
import com.holdmyspot.cat.common.optimizer.contract.DrawnTestCase;
import com.holdmyspot.cat.common.optimizer.contract.TrialProfile;
import com.holdmyspot.cat.common.optimizer.contract.test.ContractFixtures;
import com.holdmyspot.cat.common.optimizer.contract.test.TierFixtures;
import com.holdmyspot.cat.common.optimizer.inventory.Sha256;
import com.holdmyspot.cat.common.optimizer.statistics.ConfidenceBounds;
import com.holdmyspot.cat.common.optimizer.statistics.SignificanceLevel;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
		DrawnTestCase testCase = campaign.development().promptAuthor().runs().getFirst().testCase();
		campaign = TransitionSteps.adopt(campaign, Purpose.PROMOTION, 1, snapshot, testCase, Verdict.PASS);
		campaign = TransitionSteps.adopt(campaign, Purpose.PROMOTION, 1, snapshot, testCase, Verdict.PASS);
		List<Admission> plan = Scheduler.plan(campaign, NOW, 1);
		requireThat(plan, "extensionPlan").size().isEqualTo(1);
		Admission.Assign extension = (Admission.Assign) plan.getFirst();
		requireThat(extension.purpose(), "purpose").isEqualTo(Purpose.PROMOTION);
		requireThat(extension.testCase().targeted(), "targeted").isTrue();
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
	 * Returns a control admission for attempt 1.
	 *
	 * @param testCase the test case of the candidate formal trial that the control trial pairs with
	 * @return the admission
	 */
	private static Admission control(DrawnTestCase testCase)
	{
		return new Admission.Assign(Purpose.CONTROL, 1, Builder.CONTROL, testCase.cellId(), testCase,
			allocation(AllocationRule.PAIRED));
	}

	/**
	 * Returns an incumbent admission for attempt 1.
	 *
	 * @param testCase the test case of the candidate formal trial that the incumbent trial pairs with
	 * @return the admission
	 */
	private static Admission incumbent(DrawnTestCase testCase)
	{
		return new Admission.Assign(Purpose.INCUMBENT, 1, Builder.INCUMBENT, testCase.cellId(), testCase,
			allocation(AllocationRule.PAIRED));
	}

	/**
	 * Returns a candidate formal admission for attempt 1 of working candidate 1 that a control comparison needs.
	 *
	 * @param cell the identifier of the cell receiving the trial
	 * @return the admission
	 */
	private static Admission pairedFormal(String cell)
	{
		return new Admission.Assign(Purpose.FORMAL, 1, WORKING, cell, null, allocation(AllocationRule.PAIRED));
	}

	/**
	 * Returns control comparisons of {@code safety} in which each assertion has the same pairs.
	 *
	 * @param pairs the pairs of each assertion
	 * @param unpaired the test cases of the candidate formal trials that wait for their control trials, in
	 *   assignment order
	 * @return the comparisons
	 */
	private static Pairing safetyPairing(PairCounts pairs, List<DrawnTestCase> unpaired)
	{
		Map<String, PairCounts> comparisons = new LinkedHashMap<>();
		comparisons.put("asks_before_deleting", pairs);
		comparisons.put(TestCase.EXPECTED_OUTCOME, pairs);
		List<UnpairedTrial> trials = new ArrayList<>();
		for (DrawnTestCase testCase : unpaired)
			trials.add(new UnpairedTrial(testCase, Set.of("asks_before_deleting", TestCase.EXPECTED_OUTCOME)));
		return new Pairing(trials, comparisons);
	}

	/**
	 * Returns a builder of an attempt in which {@code formatting} and {@code plain_list} certified, with a window of
	 * 8 trials.
	 *
	 * @return the builder
	 */
	private static Builder.AttemptBuilder controlAttempt()
	{
		return working().development("formatting", 200, 0).development("plain_list", 200, 0).
			development("safety", 200, 0).slowStart(new SlowStart(8, 0)).certificationAttempt(1).
			boundaries(Boundaries.standard(3, Boundaries.PAIRED_MINIMUM_PASS_RATE)).cell("formatting", 100, 0).
			cell("plain_list", 100, 0);
	}

	/**
	 * Verifies that no control or incumbent trial runs in a cell whose minimum-pass-rate part has not certified.
	 */
	@Test
	public void noReferenceTrialBeforeMinimumCertifies()
	{
		// Requirement: R-F7, R-F8
		Campaign campaign = controlAttempt().cell("safety", 97, 0).campaign().build();
		for (Admission admission : Scheduler.plan(campaign, NOW, 8))
		{
			if (admission instanceof Admission.Assign assign)
				requireThat(assign.purpose(), "purpose").isEqualTo(Purpose.FORMAL);
		}
	}

	/**
	 * Verifies that at the minimum pass rate 0.95 a cell whose minimum-pass-rate part certifies runs no control,
	 * incumbent or further candidate formal trial, because the comparisons follow from the minimum pass rate.
	 */
	@Test
	public void noPairedTrialWhenComparisonsAreImplied()
	{
		// Requirement: R-F9, R-E3
		Campaign campaign = working().development("formatting", 200, 0).development("plain_list", 200, 0).
			development("safety", 200, 0).slowStart(new SlowStart(8, 0)).certificationAttempt(1).
			cell("formatting", 100, 0).cell("plain_list", 100, 0).cell("safety", 99, 0).campaign().build();
		campaign = runNext(campaign, Verdict.PASS);
		for (Admission admission : Scheduler.plan(campaign, NOW, 8))
		{
			if (admission instanceof Admission.Assign assign)
			{
				requireThat(assign.purpose(), "purpose").isEqualTo(Purpose.FORMAL);
				requireThat(assign.allocation().rule(), "rule").isNotEqualTo(AllocationRule.PAIRED);
			}
		}
	}

	/**
	 * Verifies that control trials pair with a cell's waiting candidate formal trials in assignment order and run
	 * their test cases, and that further candidate formal trials follow until the slowest comparison could pass.
	 */
	@Test
	public void controlTrialsPairInAssignmentOrder()
	{
		// Requirement: R-F7, R-E3
		DrawnTestCase logs = Builder.testCase("safety", "logs");
		DrawnTestCase cache = Builder.testCase("safety", "cache");
		DrawnTestCase tmp = Builder.testCase("safety", "tmp");
		Campaign campaign = controlAttempt().cell("safety", 100, 0).
			controlPairing("safety", safetyPairing(PairCounts.EMPTY, List.of(logs, cache, tmp))).campaign().build();
		// With no pairs, 7 pairs that only the candidate passes are the fewest that pass a comparison at attempt 1
		requireThat(Scheduler.plan(campaign, NOW, 8), "plan").isEqualTo(List.of(control(logs), control(cache),
			control(tmp), pairedFormal("safety"), pairedFormal("safety"), pairedFormal("safety"),
			pairedFormal("safety")));
	}

	/**
	 * Verifies that a cell's in-flight control trials never exceed the pairs that its slowest undecided comparison
	 * still needs if every pair favours the candidate, and that an in-flight control trial counts toward the limit.
	 */
	@Test
	public void controlTrialsRespectCellLimit()
	{
		// Requirement: R-F7, R-E3
		DrawnTestCase logs = Builder.testCase("safety", "logs");
		DrawnTestCase cache = Builder.testCase("safety", "cache");
		DrawnTestCase tmp = Builder.testCase("safety", "tmp");
		Campaign campaign = controlAttempt().cell("safety", 100, 0).
			controlPairing("safety", safetyPairing(new PairCounts(0, 5, 0, 0), List.of(logs, cache, tmp))).campaign().
			build();
		requireThat(Scheduler.plan(campaign, NOW, 8), "plan").isEqualTo(List.of(control(logs), control(cache)));

		campaign = TransitionSteps.assignReference(campaign, Reference.CONTROL, "safety");
		requireThat(Scheduler.plan(campaign, NOW, 8), "inFlight").isEqualTo(List.of(
			new Admission.Rerun(TransitionSteps.last(campaign)), control(cache)));
	}

	/**
	 * Verifies that incumbent trials pair with a cell's waiting candidate formal trials in assignment order and run
	 * their test cases, alternating with the control trials, and that further candidate formal trials follow until
	 * the slowest comparison could pass.
	 */
	@Test
	public void incumbentTrialsPairInAssignmentOrder()
	{
		// Requirement: R-F8, R-E3
		DrawnTestCase logs = Builder.testCase("safety", "logs");
		DrawnTestCase cache = Builder.testCase("safety", "cache");
		DrawnTestCase tmp = Builder.testCase("safety", "tmp");
		Campaign campaign = controlAttempt().cell("safety", 100, 0).
			controlPairing("safety", safetyPairing(PairCounts.EMPTY, List.of(logs, cache, tmp))).
			incumbentPairing("safety", safetyPairing(PairCounts.EMPTY, List.of(logs, cache, tmp))).campaign().build();
		// The window of 8 trials ends the plan before the 4 further candidate formal trials that both kinds need
		requireThat(Scheduler.plan(campaign, NOW, 8), "plan").isEqualTo(List.of(control(logs), incumbent(logs),
			control(cache), incumbent(cache), control(tmp), incumbent(tmp), pairedFormal("safety"),
			pairedFormal("safety")));
	}

	/**
	 * Verifies that a cell's incumbent comparisons alone run the further candidate formal trials that they need once
	 * its control comparisons passed.
	 */
	@Test
	public void incumbentPairsNeedFurtherFormalTrials()
	{
		// Requirement: R-F8, R-E3
		DrawnTestCase logs = Builder.testCase("safety", "logs");
		Campaign campaign = controlAttempt().cell("safety", 100, 0).
			incumbentPairing("safety", safetyPairing(PairCounts.EMPTY, List.of(logs))).campaign().build();
		// The control comparisons passed; the incumbent comparisons need 7 pairs, and one candidate formal trial waits
		requireThat(Scheduler.plan(campaign, NOW, 8), "plan").isEqualTo(List.of(incumbent(logs),
			pairedFormal("safety"), pairedFormal("safety"), pairedFormal("safety"), pairedFormal("safety"),
			pairedFormal("safety"), pairedFormal("safety")));
	}

	/**
	 * Verifies that a cell's in-flight incumbent trials never exceed the pairs that its slowest undecided incumbent
	 * comparison still needs if every pair favours the candidate, and that an in-flight incumbent trial counts toward
	 * the limit.
	 */
	@Test
	public void incumbentTrialsRespectCellLimit()
	{
		// Requirement: R-F8, R-E3
		DrawnTestCase logs = Builder.testCase("safety", "logs");
		DrawnTestCase cache = Builder.testCase("safety", "cache");
		DrawnTestCase tmp = Builder.testCase("safety", "tmp");
		Campaign campaign = controlAttempt().cell("safety", 100, 0).
			incumbentPairing("safety", safetyPairing(new PairCounts(0, 5, 0, 0), List.of(logs, cache, tmp))).
			campaign().build();
		requireThat(Scheduler.plan(campaign, NOW, 8), "plan").isEqualTo(List.of(incumbent(logs), incumbent(cache)));

		campaign = TransitionSteps.assignReference(campaign, Reference.INCUMBENT, "safety");
		requireThat(Scheduler.plan(campaign, NOW, 8), "inFlight").isEqualTo(List.of(
			new Admission.Rerun(TransitionSteps.last(campaign)), incumbent(cache)));
	}

	/**
	 * Verifies that no incumbent trial runs when the candidate is the incumbent.
	 */
	@Test
	public void noIncumbentTrialForIncumbentCandidate()
	{
		// Requirement: R-F8
		DrawnTestCase logs = Builder.testCase("safety", "logs");
		Campaign campaign = new Builder().confirmed().readyBaseline().development("formatting", 200, 0).
			development("plain_list", 200, 0).development("safety", 200, 0).slowStart(new SlowStart(8, 0)).
			certificationAttempt(1).boundaries(Boundaries.standard(3, Boundaries.PAIRED_MINIMUM_PASS_RATE)).
			cell("formatting", 100, 0).cell("plain_list", 100, 0).cell("safety", 100, 0).
			controlPairing("safety", safetyPairing(new PairCounts(0, 6, 0, 0), List.of(logs))).campaign().build();
		requireThat(Scheduler.plan(campaign, NOW, 8), "plan").isEqualTo(List.of(
			new Admission.Assign(Purpose.CONTROL, 1, Builder.CONTROL, "safety", logs,
				allocation(AllocationRule.PAIRED))));
	}

	/**
	 * Returns a builder of a confirmed campaign whose working candidate 1 has replaced the incumbent, and whose
	 * baseline is met, so that no baseline trial runs.
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
		DrawnTestCase logs = Builder.targeted("safety", "logs");
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
	 * Verifies that the promotion runs of a prompt author attempt are admitted, each with its snapshot, and that its
	 * regression runs follow once a triggering test case has passed twice.
	 */
	@Test
	public void promotionRunsAreAdmitted()
	{
		// Requirement: R-E3
		DrawnTestCase logs = Builder.targeted("safety", "logs");
		DrawnTestCase formal = Builder.targeted("formatting", "formal");
		Sha256 attempt = Builder.snapshot("attempt");
		Campaign campaign = working().failure(logs).regression(formal, null).
			round(new Round(Map.of("safety", 0))).build();
		campaign = TransitionSteps.submit(campaign, "safety", attempt, List.of());
		Allocation targeted = allocation(AllocationRule.TARGETED);
		requireThat(Scheduler.plan(campaign, NOW, 8), "plan").isEqualTo(List.of(
			new Admission.Assign(Purpose.PROMOTION, 1, attempt, "safety", logs, targeted),
			new Admission.Assign(Purpose.PROMOTION, 1, attempt, "safety", logs, targeted)));

		campaign = TransitionSteps.assign(campaign, Purpose.PROMOTION, 1, attempt, logs);
		requireThat(Scheduler.plan(campaign, NOW, 8), "afterAssign").isEqualTo(List.of(
			new Admission.Rerun(TransitionSteps.last(campaign)),
			new Admission.Assign(Purpose.PROMOTION, 1, attempt, "safety", logs, targeted)));

		campaign = TransitionSteps.run(campaign, TransitionSteps.last(campaign), Verdict.PASS);
		campaign = TransitionSteps.adopt(campaign, Purpose.PROMOTION, 1, attempt, logs, Verdict.PASS);
		Allocation adopted = new Allocation(AllocationRule.TARGETED, 2, NOW);
		requireThat(Scheduler.plan(campaign, NOW, 8), "afterStreak").isEqualTo(List.of(
			new Admission.Assign(Purpose.PROMOTION, 1, attempt, "safety", logs, adopted),
			new Admission.Assign(Purpose.PROMOTION, 1, attempt, "formatting", formal, adopted),
			new Admission.Assign(Purpose.PROMOTION, 1, WORKING, "formatting", formal, adopted)));
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
		Campaign ahead = working().development("formatting", 40, 0).development("plain_list", 45, 0).
			development("safety", 85, 5).
			slowStart(new SlowStart(1, 0)).build();
		requireThat(Scheduler.plan(ahead, NOW, 8), "atLead").isEqualTo(List.of(development("formatting",
			AllocationRule.COVERAGE_FLOOR)));

		Campaign within = working().development("formatting", 41, 0).development("plain_list", 45, 0).
			development("safety", 85, 5).
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
			development("plain_list", 60, 0).development("safety", 60, 1).evidenceRevision(173).build();
		List<Admission> expected = List.of(
			new Admission.Assign(Purpose.DEVELOPMENT, 1, WORKING, "safety", null,
				new Allocation(AllocationRule.LOWEST_EXPLORATORY_BOUND, 173, later)),
			new Admission.Assign(Purpose.BASELINE, 1, Builder.INCUMBENT, "formatting", null,
				new Allocation(AllocationRule.BASELINE, 173, later)),
			new Admission.Assign(Purpose.BASELINE, 1, Builder.INCUMBENT, "plain_list", null,
				new Allocation(AllocationRule.BASELINE, 173, later)),
			new Admission.Assign(Purpose.BASELINE, 1, Builder.INCUMBENT, "safety", null,
				new Allocation(AllocationRule.BASELINE, 173, later)));
		requireThat(Scheduler.plan(campaign, later, 4), "plan").isEqualTo(expected);
	}

	/**
	 * Returns the fixture contract with both tests at the same risk rank, in a given order.
	 *
	 * @param order the test identifiers in contract order
	 * @return the contract
	 */
	private static Contract equalRisk(String... order)
	{
		Contract fixture = ContractFixtures.contract();
		List<PromptTest> tests = new ArrayList<>();
		for (String id : order)
		{
			PromptTest test = fixture.findTest(id).orElseThrow();
			tests.add(new PromptTest(test.id(), new RiskRank(1), test.requirementIds(), test.absentTriggers(),
				test.applicability(), test.turns(), test.timeLimit(), test.expectedBehaviour(),
				test.uncoveredInteractions(), test.variables(), test.committedTestCases(), test.testCases(),
				test.assertions(), test.regressionScenarios(), List.of()));
		}
		return new Contract(fixture.requirements(), tests,
			ContractFixtures.profiles(fixture.requirements(), tests), fixture.judge(),
			fixture.limitations(), fixture.minimumPassRate(), fixture.salt());
	}

	/**
	 * Verifies that tests tied on bound, outcomes and risk rank are admitted in contract order.
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
		Campaign campaign = working().development("formatting", 60, 0).development("plain_list", 60, 0).
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
		Campaign campaign = working().development("formatting", 60, 0).development("plain_list", 60, 0).
			development("safety", 60, 1).slowStart(new SlowStart(2, 0)).build();
		campaign = TransitionSteps.assign(campaign, Purpose.DEVELOPMENT, "formatting");
		campaign = Transitions.apply(campaign, new CampaignEvent.Start(TransitionSteps.last(campaign)));
		requireThat(cellIds(Scheduler.plan(campaign, NOW, 8)), "plan").isEqualTo(List.of("safety"));
	}

	/**
	 * Verifies that the formal phase first gives each test one trial.
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
			new Admission.Rerun(TransitionSteps.last(campaign)), formal("plain_list")));
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
			certificationAttempt(1).cell("formatting", 50, 0).cell("plain_list", 50, 0).cell("safety", 50, 1).build();
		requireThat(Scheduler.plan(lower, NOW, 8), "lowerBound").isEqualTo(List.of(formal("safety")));

		Campaign fewer = working().slowStart(new SlowStart(1, 0)).
			certificationAttempt(1).cell("formatting", 0, 2).cell("plain_list", 0, 2).cell("safety", 0, 1).build();
		requireThat(Scheduler.plan(fewer, NOW, 8), "fewerOutcomes").isEqualTo(List.of(formal("safety")));

		Campaign order = working().slowStart(new SlowStart(1, 0)).
			certificationAttempt(1).cell("formatting", 50, 0).cell("plain_list", 50, 0).cell("safety", 50, 0).build();
		requireThat(Scheduler.plan(order, NOW, 8), "testOrder").isEqualTo(List.of(formal("formatting")));
	}

	/**
	 * Verifies that a decided cell receives no new formal trials and an undecided cell's remaining passes to
	 * certification limit its unadopted trials.
	 */
	@Test
	public void formalRespectsDecisionsAndCellLimit()
	{
		// Requirement: R-E3
		Campaign campaign = working().development("formatting", 200, 0).development("plain_list", 200, 0).
			development("safety", 200, 0).slowStart(new SlowStart(8, 0)).certificationAttempt(1).
			cell("formatting", 100, 0).cell("plain_list", 100, 0).cell("safety", 97, 0).build();
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
	 * Verifies that a test under two profiles is scheduled as two cells with separate counts: development admits
	 * the weaker cell, and the formal phase gives each cell its own first trial.
	 */
	@Test
	public void twoCellTestIsScheduledPerCell()
	{
		// Requirement: R-C11
		Contract contract = ContractFixtures.twoCellContract();
		Campaign development = new Builder().confirmed(contract, 1).working(1).readyBaseline().
			development(ContractFixtures.BIG_CELL, 60, 0).development(ContractFixtures.SMALL_CELL, 60, 1).
			development(ContractFixtures.PLAIN_BIG_CELL, 100, 0).
			development(ContractFixtures.PLAIN_SMALL_CELL, 100, 0).development("safety", 100, 0).
			slowStart(new SlowStart(2, 0)).build();
		requireThat(cellIds(Scheduler.plan(development, NOW, 8)), "development").
			isEqualTo(List.of(ContractFixtures.SMALL_CELL, ContractFixtures.SMALL_CELL));

		Campaign formal = new Builder().confirmed(contract, 1).working(1).readyBaseline().
			slowStart(new SlowStart(1, 0)).certificationAttempt(1).cell(ContractFixtures.BIG_CELL, 50, 0).
			cell(ContractFixtures.PLAIN_BIG_CELL, 50, 0).cell(ContractFixtures.PLAIN_SMALL_CELL, 50, 0).
			cell("safety", 50, 0).build();
		requireThat(cellIds(Scheduler.plan(formal, NOW, 8)), "formal").
			isEqualTo(List.of(ContractFixtures.SMALL_CELL));
	}

	/**
	 * Returns a builder of a confirmed campaign of {@link TierFixtures#tieredContract()} whose working candidate 1
	 * has replaced the incumbent, and whose baseline is met, so that no baseline trial runs.
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
	 * Returns a development failure of the tiered contract's {@code formatting} test.
	 *
	 * @param cell the identifier of the failing cell
	 * @return the failure's test case
	 */
	private static DrawnTestCase tieredFailure(String cell)
	{
		return new Generator(TierFixtures.tieredContract()).draw(cell,
			DrawStream.create(DrawStream.Kind.DEVELOPMENT, 1));
	}

	/**
	 * Returns new test cases of the test of a failed test case, written after certification attempt 1.
	 *
	 * @param failure the failed test case
	 * @return 4 test cases, each of which gives every variable of the test a new value
	 */
	private static List<AddedTestCase> reproductionTestCases(DrawnTestCase failure)
	{
		PromptTest test = TierFixtures.tieredContract().findTest(failure.testId()).orElseThrow();
		return ContractFixtures.addedTestCases(test, 1, "added");
	}

	/**
	 * Verifies that after a certification attempt ends with formal failures, reproduction trials run in the failure
	 * group's cell before any development trial, so no prompt author works on the failures before they reproduce.
	 */
	@Test
	public void reproductionRunsBeforeDevelopment()
	{
		// Requirement: R-C19
		DrawnTestCase failure = tieredFailure(BIG_MEDIUM);
		Failure formal = new Failure(failure, "", List.of(new FailedAssertion("uses_requested_tone", "wrong_tone")),
			null, List.of(), List.of(), Builder.FILES);
		Campaign failed = tiered().slowStart(new SlowStart(4, 0)).development(SMALL_HIGH, 60, 0).
			development(SAFETY, 60, 0).certificationAttempt(1).failure(formal).outcome(Outcome.INCONCLUSIVE).build();
		requireThat(failed.reproducing().map(CertificationAttempt::index), "reproducing").isEqualTo(Optional.of(1));
		requireThat(Scheduler.plan(failed, NOW, 8).stream().anyMatch(admission ->
			admission instanceof Admission.Assign), "assigned").isFalse();

		Campaign campaign = Transitions.apply(failed, new CampaignEvent.ReproductionValues(1, 1,
			reproductionTestCases(failure)));

		List<Admission> plan = Scheduler.plan(campaign, NOW, 8);
		requireThat(plan.size(), "plan.size()").isEqualTo(4);
		for (Admission admission : plan)
		{
			requireThat(admission, "admission").isInstanceOf(Admission.Assign.class);
			Admission.Assign assign = (Admission.Assign) admission;
			requireThat(assign.purpose(), "purpose").isEqualTo(Purpose.REPRODUCTION);
			requireThat(assign.cell(), "cell").isEqualTo(BIG_MEDIUM);
			requireThat(assign.allocation().rule(), "rule").isEqualTo(AllocationRule.TARGETED);
		}
	}

	/**
	 * Verifies that development assigns representative trials only to development cells: each test's weakest
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
		DrawnTestCase failure = tieredFailure(BIG_MEDIUM);
		Campaign campaign = certified.development(BIG_MEDIUM, 60, 0).certificationAttempt(1).failure(failure).
			outcome(Outcome.INCONCLUSIVE).build();
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
	 * Verifies that the first working candidate samples only development cells, with no screen of the other cells, and
	 * that development assigns nothing once every development cell meets the commit gate, however empty the other cells
	 * are.
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
		// The cells below BIG_HIGH and the development cell are certified, so BIG_HIGH is open
		for (String cell : List.of(SMALL_HIGH, BIG_LOW, BIG_MEDIUM))
			attempt.cell(cell, 100, 0);
		Campaign campaign = attempt.cell(BIG_HIGH, 50, 1).build();
		requireThat(CellTier.developmentCellIds(campaign), "before").isEqualTo(List.of(SMALL_HIGH, SAFETY));
		requireThat(Scheduler.plan(campaign, NOW, 8), "formal").isEqualTo(List.of(formal(BIG_HIGH)));
		campaign = TransitionSteps.assign(campaign, Purpose.FORMAL, BIG_HIGH);
		campaign = TransitionSteps.run(campaign, TransitionSteps.last(campaign), Verdict.FAIL);
		requireThat(CellTier.developmentCellIds(campaign), "after").isEqualTo(List.of(BIG_HIGH, SMALL_HIGH, SAFETY));
	}

	/**
	 * Verifies that formal and baseline trials run in the certification scope's cells only: the weakest cell of each
	 * test under the weakest-cell scope, and every cell of the latest versions under the latest-versions scope.
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
		DrawnTestCase failing = new Generator(contract).markTargeted(tieredFailure(BIG_MEDIUM));
		Campaign campaign = tiered().development(SMALL_HIGH, 100, 0).development(SAFETY, 100, 0).
			development(BIG_MEDIUM, 0, 1).failure(failing).round(new Round(Map.of(BIG_MEDIUM, 0))).
			certificationAttempt(1).failure(tieredFailure(BIG_MEDIUM)).outcome(Outcome.INCONCLUSIVE).build();
		campaign = TransitionSteps.submit(campaign, BIG_MEDIUM, Builder.snapshot("attempt"), List.of());
		while (Promotion.decide(campaign.development().promptAuthor(), campaign.development().working().snapshot(),
			campaign.lineage().regressionSet(),
			campaign.development().testCaseOutcomes()) == Promotion.Decision.PENDING)
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

	/**
	 * Returns the identifier of a cell of the {@code formatting} test in {@link #codexContract()}.
	 *
	 * @param model the cell's model
	 * @param effort the cell's effort
	 * @return the identifier
	 */
	private static String codexCell(String model, String effort)
	{
		return "formatting@" + model + "/" + effort;
	}

	/**
	 * Returns the fixture contract under {@link TierFixtures#CODEX_PROFILES} and {@link TierFixtures#CODEX_RANKING}.
	 * Under the latest-versions scope, the {@code formatting} test has 15 cells: the efforts
	 * {@code low}, {@code medium}, {@code high}, {@code xhigh} and {@code max} of {@code gpt-6-luna},
	 * {@code gpt-6.1-sol} and {@code gpt-6-astra}, of which {@code gpt-6-luna/low} is the weakest. The {@code safety}
	 * test has one cell.
	 *
	 * @return the contract
	 */
	private static Contract codexContract()
	{
		return TierFixtures.contract(TierFixtures.CODEX_PROFILES, TierFixtures.CODEX_RANKING, List.of(),
			new TrialProfile("gpt-6-luna", "low"));
	}

	/**
	 * Returns the builder of a running certification attempt of {@link #codexContract()} under the latest-versions
	 * scope, whose concurrency window is 8 and whose listed cells are certified.
	 *
	 * @param certified the identifiers of the certified cells
	 * @return the attempt's builder
	 */
	private static Builder.AttemptBuilder codexAttempt(String... certified)
	{
		Builder.AttemptBuilder attempt = new Builder().confirmed(codexContract(), 1).working(1).readyBaseline().
			development(codexCell("gpt-6-luna", "low"), 200, 0).development(SAFETY, 200, 0).
			slowStart(new SlowStart(8, 0)).certificationAttempt(1);
		for (String cell : certified)
			attempt.cell(cell, 100, 0);
		return attempt;
	}

	/**
	 * Returns the cells of a plan's formal admissions.
	 *
	 * @param campaign the campaign
	 * @return the cells
	 */
	private static Set<String> formalCells(Campaign campaign)
	{
		Set<String> cells = new LinkedHashSet<>();
		for (Admission admission : Scheduler.plan(campaign, NOW, 8))
		{
			if (admission instanceof Admission.Assign assign && assign.purpose() == Purpose.FORMAL)
				cells.add(assign.cell());
		}
		return cells;
	}

	/**
	 * Verifies that an attempt's formal trials go to the development cells only while they are undecided, although
	 * every other scope cell is empty, and that the weakest-cell scope is unaffected.
	 */
	@Test
	public void formalStartsInDevelopmentCells()
	{
		// Requirement: R-E23
		requireThat(formalCells(codexAttempt().build()), "latestVersions").
			isEqualTo(Set.of(codexCell("gpt-6-luna", "low"), SAFETY));

		Campaign weakest = tiered(CertificationScope.WEAKEST_CELL).readyBaseline().development(SMALL_HIGH, 200, 0).
			development(SAFETY, 200, 0).slowStart(new SlowStart(8, 0)).certificationAttempt(1).build();
		requireThat(formalCells(weakest), "weakestCell").isEqualTo(Set.of(SMALL_HIGH, SAFETY));
	}

	/**
	 * Verifies that a stronger cell receives formal trials only once every cell below it is certified: certifying the
	 * weakest cell opens the next effort of the same model and the lowest effort of the next model, but not the
	 * cells above them.
	 */
	@Test
	public void strongerCellOpensAfterLowerCellsCertify()
	{
		// Requirement: R-E23
		Campaign campaign = codexAttempt(codexCell("gpt-6-luna", "low"), SAFETY).build();
		requireThat(formalCells(campaign), "afterWeakest").
			isEqualTo(Set.of(codexCell("gpt-6-luna", "medium"), codexCell("gpt-6.1-sol", "low")));

		campaign = codexAttempt(codexCell("gpt-6-luna", "low"), SAFETY, codexCell("gpt-6-luna", "medium")).build();
		requireThat(formalCells(campaign), "beforeLowerCellCertifies").
			isEqualTo(Set.of(codexCell("gpt-6-luna", "high"), codexCell("gpt-6.1-sol", "low")));
	}

	/**
	 * Verifies that a cell opens once the cells below it are certified, whatever the state of a cell that it is not
	 * below: {@code gpt-6.1-sol/medium} opens while {@code gpt-6-luna/high}, a weaker model at a higher effort, is
	 * undecided, and the two run together.
	 */
	@Test
	public void incomparableCellsOpenIndependently()
	{
		// Requirement: R-E23
		Campaign campaign = codexAttempt(codexCell("gpt-6-luna", "low"), SAFETY, codexCell("gpt-6-luna", "medium"),
			codexCell("gpt-6.1-sol", "low")).build();
		requireThat(formalCells(campaign), "open").isEqualTo(Set.of(codexCell("gpt-6-luna", "high"),
			codexCell("gpt-6.1-sol", "medium"), codexCell("gpt-6-astra", "low")));
	}

	/**
	 * Verifies that among a test's open cells, the one with the fewest formal outcomes goes first, even when
	 * another open cell has a lower confidence bound.
	 */
	@Test
	public void fewestFormalOutcomesAmongOpenCells()
	{
		// Requirement: R-E23
		String fewer = codexCell("gpt-6.1-sol", "low");
		String lowerBound = codexCell("gpt-6-luna", "medium");
		Campaign campaign = codexAttempt(codexCell("gpt-6-luna", "low"), SAFETY).cell(lowerBound, 70, 1).
			cell(fewer, 60, 0).campaign().slowStart(new SlowStart(1, 0)).build();
		SignificanceLevel level = campaign.runningAttempt().orElseThrow().procedure().boundaries().level();
		requireThat(ConfidenceBounds.lower(70, 1, level).value(), "lowerBound").
			isLessThan(ConfidenceBounds.lower(60, 0, level).value());
		requireThat(Scheduler.plan(campaign, NOW, 8), "plan").isEqualTo(List.of(formal(fewer)));
	}

	/**
	 * Verifies that the test limit counts the remaining passes of the open cells only: a development cell 3
	 * passes from certifying receives 3 formal trials, and the closed cells none, although the window allows 8.
	 */
	@Test
	public void testLimitOverOpenCells()
	{
		// Requirement: R-E23
		String weakest = codexCell("gpt-6-luna", "low");
		Campaign campaign = codexAttempt(SAFETY).cell(weakest, 97, 0).build();
		requireThat(Scheduler.plan(campaign, NOW, 8), "plan").isEqualTo(List.of(formal(weakest), formal(weakest),
			formal(weakest)));
	}

	/**
	 * Verifies that the incumbent's baseline trials are not ordered: its first round of trials covers every scope cell,
	 * the stronger ones included.
	 */
	@Test
	public void baselineIsNotOrdered()
	{
		// Requirement: R-E23
		Contract contract = codexContract().withScope(CertificationScope.LATEST_VERSIONS);
		Campaign campaign = new Builder().confirmed(contract, 1).working(1).build();
		List<String> scope = CellTier.scopeCellIds(contract);
		List<String> baseline = new ArrayList<>();
		for (Admission admission : Scheduler.plan(campaign, NOW, 64))
		{
			if (admission instanceof Admission.Assign assign && assign.purpose() == Purpose.BASELINE)
				baseline.add(assign.cell());
		}
		requireThat(baseline.subList(0, scope.size()), "firstRound").isEqualTo(scope);
	}
}
