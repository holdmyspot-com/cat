package com.holdmyspot.cat.common.optimizer.report.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.optimizer.campaign.Campaign;
import com.holdmyspot.cat.common.optimizer.campaign.Pairing;
import com.holdmyspot.cat.common.optimizer.campaign.Pairing.UnpairedTrial;
import com.holdmyspot.cat.common.optimizer.campaign.Lifecycle;
import com.holdmyspot.cat.common.optimizer.campaign.Outcome;
import com.holdmyspot.cat.common.optimizer.campaign.PairCounts;
import com.holdmyspot.cat.common.optimizer.campaign.test.Boundaries;
import com.holdmyspot.cat.common.optimizer.campaign.test.Builder;
import com.holdmyspot.cat.common.optimizer.contract.CertificationScope;
import com.holdmyspot.cat.common.optimizer.contract.Contract;
import com.holdmyspot.cat.common.optimizer.contract.DrawStream;
import com.holdmyspot.cat.common.optimizer.contract.Generator;
import com.holdmyspot.cat.common.optimizer.contract.TestCase;
import com.holdmyspot.cat.common.optimizer.contract.DrawnTestCase;
import com.holdmyspot.cat.common.optimizer.contract.test.TierFixtures;
import com.holdmyspot.cat.common.optimizer.report.Phase;
import com.holdmyspot.cat.common.optimizer.report.RemainingWork.Entry;
import com.holdmyspot.cat.common.optimizer.report.RemainingWork.Kind;
import com.holdmyspot.cat.common.optimizer.report.RemainingWork;
import com.holdmyspot.cat.common.optimizer.statistics.ComparisonBoundaries;
import com.holdmyspot.cat.common.optimizer.statistics.test.FirstAttemptBoundaries;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.testng.annotations.Test;

/**
 * Tests {@link RemainingWork} with hand-computed values for both phases.
 * <p>
 * The fixture contract has the tests {@code formatting} and {@code safety}, each with one cell of the same
 * identifier, so {@code K = 2}. The all-pass minimum of the next attempt is 93
 * trials per cell for attempt 1, 117 for attempt 2 and 132 for attempt 3.
 */
public final class RemainingWorkTest
{
	/**
	 * Creates the test.
	 */
	public RemainingWorkTest()
	{
	}

	/**
	 * Returns the trials of one entry.
	 *
	 * @param work the count
	 * @param cell the cell's identifier
	 * @param kind what the trials are required for
	 * @return the trials
	 * @throws AssertionError if there is no such entry or its count is unavailable
	 */
	private static int trials(RemainingWork work, String cell, Kind kind)
	{
		for (Entry entry : work.entries())
		{
			if (entry.cell().equals(cell) && entry.kind() == kind)
			{
				requireThat(entry.trials(), "entry.trials()").withContext(entry, "entry").isNotNull();
				return entry.trials();
			}
		}
		throw new AssertionError("No " + kind + " entry for " + cell + " in " + work);
	}

	/**
	 * Verifies the design's all-pass minima that the hand computations below rely on.
	 */
	@Test
	public void allPassMinimumOfAttemptOne()
	{
		// Requirement: R-E12
		// Attempt 1 needs 93 all-pass trials per cell.
		requireThat(FirstAttemptBoundaries.get().minimumCertifyingPasses().get(0), "minimum").isEqualTo(93);
	}

	/**
	 * Verifies development counts that need commit gate trials only.
	 */
	@Test
	public void developmentCommitGateAndNextAttempt()
	{
		// Requirement: R-E12
		// formatting: n = 60, f = 0         -> max(0, max(100, 100*0) - 60)  =  40
		// plain_list: n = 100, f = 0        -> max(0, max(100, 0) - 100)     =   0
		// safety:     n = 100, f = 0        -> max(0, max(100, 0) - 100)     =   0
		// baseline met (50 in each cell)    ->                                  0 + 0 + 0
		// next attempt (i = 1): 93 per cell                                  -> 93 + 93 + 93 = 279
		// total = 40 + 0 + 0 + 0 + 279 = 319
		Campaign campaign = new Builder().confirmed().working(1).development("formatting", 60, 0).
			development("plain_list", 100, 0).development("safety", 100, 0).readyBaseline().build();
		RemainingWork work = RemainingWork.of(campaign);
		requireThat(work.phase(), "phase").isEqualTo(Phase.DEVELOPMENT);
		requireThat(trials(work, "formatting", Kind.COMMIT_GATE), "formattingCommitGate").isEqualTo(40);
		requireThat(trials(work, "safety", Kind.COMMIT_GATE), "safetyCommitGate").isEqualTo(0);
		requireThat(trials(work, "formatting", Kind.NEXT_ATTEMPT), "formattingNext").isEqualTo(93);
		requireThat(trials(work, "safety", Kind.NEXT_ATTEMPT), "safetyNext").isEqualTo(93);
		requireThat(work.total(), "total").isEqualTo(319);
		requireThat(work.unavailable(), "unavailable").isNull();
	}

	/**
	 * Verifies that a recent batch with 2 failures delays the commit gate beyond the simple count formula.
	 */
	@Test
	public void developmentRecentBatchDelaysCommitGate()
	{
		// Requirement: R-E12
		// formatting: 198 passes, 2 failures in batches [0, 0, 2, 0] at n = 200. The simple formula gives
		// max(0, max(100, 200) - 200) = 0, but the latest two complete batches hold 2 and 0 failures. After 50
		// more passes batch 5 completes with 0 failures, so the latest two (0, 0) are within the limit and 248/250
		// passes: 50 trials are needed. Smaller additions leave the latest two batches at (2, 0) or (0, partial).
		Campaign campaign = new Builder().confirmed().working(1).
			development("formatting", 198, List.of(0, 0, 2, 0)).development("safety", 100, 0).
			readyBaseline().build();
		RemainingWork work = RemainingWork.of(campaign);
		requireThat(trials(work, "formatting", Kind.COMMIT_GATE), "formattingCommitGate").isEqualTo(50);
		requireThat(trials(work, "safety", Kind.COMMIT_GATE), "safetyCommitGate").isEqualTo(0);
	}

	/**
	 * Verifies failures, an incomplete incumbent baseline and a cell with no development outcomes.
	 */
	@Test
	public void developmentFailuresAndBaseline()
	{
		// Requirement: R-E12
		// formatting: n = 153, f = 3        -> max(0, max(100, 300) - 153)   = 147
		// safety:     no outcomes (n = 0)   -> max(0, max(100, 0) - 0)       = 100
		// baseline formatting: 30 passes    -> 50 - 30                       =  20
		// baseline safety: 45 passes, 2 failures: after 3 more passes n = 50 with 48 passes,
		//   20*48 = 960 >= 19*50 = 950, so the target stays 50 and 3 trials are needed
		// plain_list: commit gate and baseline met (n = 100 and 50 passes) ->   0
		// next attempt (i = 1): 93 + 93 + 93 = 279
		// total = 147 + 100 + 20 + 3 + 0 + 279 = 549
		Campaign campaign = new Builder().confirmed().working(1).development("formatting", 150, 3).
			development("plain_list", 100, 0).baseline("formatting", 30, 0).baseline("plain_list", 50, 0).
			baseline("safety", 45, 2).build();
		RemainingWork work = RemainingWork.of(campaign);
		requireThat(trials(work, "formatting", Kind.COMMIT_GATE), "formattingCommitGate").isEqualTo(147);
		requireThat(trials(work, "safety", Kind.COMMIT_GATE), "safetyCommitGate").isEqualTo(100);
		requireThat(trials(work, "formatting", Kind.BASELINE), "formattingBaseline").isEqualTo(20);
		requireThat(trials(work, "safety", Kind.BASELINE), "safetyBaseline").isEqualTo(3);
		requireThat(work.total(), "total").isEqualTo(549);
	}

	/**
	 * Verifies that the commit gate counts only the development cells while the incumbent baseline and the next attempt
	 * count every cell of the certification scope.
	 */
	@Test
	public void developmentWorkCoversScopeCells()
	{
		// Requirement: R-E19
		// Requirement: R-C15
		// latest versions: commit gate 100 in each development cell (small/high, safety)  = 200
		//   baseline 50 in each of the 6 scope cells                                    = 300
		//   next attempt 93 in each of the 6 scope cells                                = 558
		//   total = 1058
		Contract latest = TierFixtures.tieredContract();
		RemainingWork work = RemainingWork.of(new Builder().confirmed(latest, 1).working(1).build());
		requireThat(trials(work, TierFixtures.SMALL_HIGH, Kind.COMMIT_GATE), "weakestCommitGate").isEqualTo(100);
		requireThat(entries(work, TierFixtures.BIG_LOW), "bigLowKinds").
			isEqualTo(List.of(Kind.BASELINE, Kind.NEXT_ATTEMPT));
		requireThat(trials(work, TierFixtures.BIG_LOW, Kind.BASELINE), "bigLowBaseline").isEqualTo(50);
		requireThat(work.total(), "latestTotal").isEqualTo(1058);

		// weakest cell: commit gate 200, baseline 2 × 50 = 100, next attempt 2 × 93 = 186; total = 486
		Contract weakest = latest.withScope(CertificationScope.WEAKEST_CELL);
		RemainingWork weakestWork = RemainingWork.of(new Builder().confirmed(weakest, 1).working(1).build());
		requireThat(entries(weakestWork, TierFixtures.BIG_LOW), "outsideScope").isEmpty();
		requireThat(weakestWork.total(), "weakestTotal").isEqualTo(486);
	}

	/**
	 * Verifies that a development cell outside the certification scope adds its commit gate minimum and nothing else,
	 * and follows its base cell in the entries.
	 */
	@Test
	public void outsideScopeCellAddsCommitGate()
	{
		// Requirement: R-E12
		// weakest cell: the scope is small/high and safety. A failure in big/medium makes it a development cell.
		//   commit gate 100 in each of 3 development cells                                = 300
		//   baseline 50 in each of the 2 scope cells                                    = 100
		//   next attempt (i = 2) 117 in each of the 2 scope cells                       = 234
		//   total = 634
		Contract weakest = TierFixtures.tieredContract().withScope(CertificationScope.WEAKEST_CELL);
		Campaign campaign = new Builder().confirmed(weakest, 1).working(1).certificationAttempt(1).
			failure(draw(weakest, TierFixtures.BIG_MEDIUM)).outcome(Outcome.INCONCLUSIVE).build();
		RemainingWork work = RemainingWork.of(campaign);

		requireThat(entries(work, TierFixtures.BIG_MEDIUM), "outsideScope").isEqualTo(List.of(Kind.COMMIT_GATE));
		requireThat(trials(work, TierFixtures.BIG_MEDIUM, Kind.COMMIT_GATE), "outsideScopeCommitGate").isEqualTo(100);
		requireThat(work.entries().stream().map(Entry::cell).distinct().toList(), "cells").isEqualTo(
			List.of(TierFixtures.BIG_MEDIUM, TierFixtures.SMALL_HIGH, TierFixtures.SAFETY));
		requireThat(work.total(), "total").isEqualTo(634);
	}

	/**
	 * Verifies that a tool-output stress cell, which the contract does not list, adds its commit gate minimum right
	 * after its base cell.
	 */
	@Test
	public void toolOutputStressCellAddsCommitGate()
	{
		// Requirement: R-E12
		// latest versions: the scope is the 6 contract cells. A failure in big/low under the 5000-token tool-output
		// limit makes that limited cell a development cell, though no scope cell is the limited cell.
		//   commit gate 100 in each of 3 development cells (limited big/low, small/high, safety)  = 300
		//   baseline 50 in each of the 6 scope cells                                             = 300
		//   next attempt (i = 2) 117 in each of the 6 scope cells                                = 702
		//   total = 1302
		String limited = TierFixtures.BIG_LOW + "/tool-output-5000";
		Contract contract = TierFixtures.tieredContract();
		Campaign campaign = new Builder().confirmed(contract, 1).working(1).certificationAttempt(1).
			failure(draw(contract, limited)).outcome(Outcome.INCONCLUSIVE).build();
		RemainingWork work = RemainingWork.of(campaign);

		requireThat(entries(work, limited), "limitedKinds").isEqualTo(List.of(Kind.COMMIT_GATE));
		requireThat(trials(work, limited, Kind.COMMIT_GATE), "limitedCommitGate").isEqualTo(100);
		List<String> cells = work.entries().stream().map(Entry::cell).distinct().toList();
		requireThat(cells.get(cells.indexOf(TierFixtures.BIG_LOW) + 1), "afterBase").isEqualTo(limited);
		requireThat(work.total(), "total").isEqualTo(1302);
	}

	/**
	 * Verifies that a scope cell that is also a development cell is counted once.
	 */
	@Test
	public void developmentCellInScopeIsCountedOnce()
	{
		// Requirement: R-E12
		// latest versions: big/medium is in the scope and, after a failure there, a development cell.
		//   commit gate 100 in each of 3 development cells (big/medium, small/high, safety)  = 300
		//   baseline 50 in each of the 6 scope cells                                       = 300
		//   next attempt (i = 2) 117 in each of the 6 scope cells                          = 702
		//   total = 1302
		Contract contract = TierFixtures.tieredContract();
		Campaign campaign = new Builder().confirmed(contract, 1).working(1).certificationAttempt(1).
			failure(draw(contract, TierFixtures.BIG_MEDIUM)).outcome(Outcome.INCONCLUSIVE).build();
		RemainingWork work = RemainingWork.of(campaign);

		requireThat(entries(work, TierFixtures.BIG_MEDIUM), "bigMediumKinds").
			isEqualTo(List.of(Kind.COMMIT_GATE, Kind.BASELINE, Kind.NEXT_ATTEMPT));
		requireThat(work.entries().stream().map(Entry::cell).distinct().count(), "cells").isEqualTo(6L);
		requireThat(work.total(), "total").isEqualTo(1302);
	}

	/**
	 * Draws a test case of a cell.
	 *
	 * @param contract the contract
	 * @param cell the cell's identifier, which may name a cell under a tool-output token limit
	 * @return the test case
	 */
	private static DrawnTestCase draw(Contract contract, String cell)
	{
		return new Generator(contract).draw(cell, DrawStream.create(DrawStream.Kind.DEVELOPMENT, 1));
	}

	/**
	 * Returns the kinds of a cell's entries.
	 *
	 * @param work the count
	 * @param cell the cell's identifier
	 * @return the kinds, in entry order
	 */
	private static List<Kind> entries(RemainingWork work, String cell)
	{
		return work.entries().stream().filter(entry -> entry.cell().equals(cell)).map(Entry::kind).toList();
	}

	/**
	 * Verifies that the next attempt's minimum follows the certification index.
	 */
	@Test
	public void developmentNextAttemptUsesNextIndex()
	{
		// Requirement: R-E12
		// attempt 1 ended; the next attempt is 2, whose all-pass minimum is 117 per cell.
		// commit gate and baseline are met, so total = 117 + 117 + 117 = 351
		Campaign campaign = new Builder().confirmed().working(1).readyDevelopment().readyBaseline().
			certificationAttempt(1).outcome(Outcome.INCONCLUSIVE).build();
		RemainingWork work = RemainingWork.of(campaign);
		requireThat(work.phase(), "phase").isEqualTo(Phase.DEVELOPMENT);
		requireThat(trials(work, "formatting", Kind.NEXT_ATTEMPT), "formattingNext").isEqualTo(117);
		requireThat(trials(work, "safety", Kind.NEXT_ATTEMPT), "safetyNext").isEqualTo(117);
		requireThat(work.total(), "total").isEqualTo(351);
	}

	/**
	 * Verifies that only unresolved cells of a running attempt need trials, each to its frozen boundary, and that a
	 * cell whose control and incumbent comparisons have not started needs the control and incumbent trials that pass
	 * them from no pairs.
	 */
	@Test
	public void formalUnresolvedCells()
	{
		// Requirement: R-E12
		// Requirement: R-F7
		// Boundaries: a cell certifies at 100 + 50*F passes. At alpha_1 = 1/50, a control comparison with no pairs
		// passes after 105 pairs in which both trials pass.
		// The same holds for an incumbent comparison.
		// formatting: S = 60,  F = 0 -> undecided; certifies at S = 100 -> 100 - 60 = 40; its 100 formal outcomes
		//             pair with 100 of the 105 control trials, so 5 more formal trials -> 105 + 5 = 110. The 105
		//             incumbent trials pair with the same 105 formal trials -> 105
		// plain_list: S = 100, F = 0 -> certified, with passed comparisons; needs nothing
		// safety:     S = 140, F = 1 -> undecided; certifies at S = 150 -> 150 - 140 = 10; its 151 formal outcomes
		//             cover the 105 control trials -> 105, and the 105 incumbent trials -> 105
		// total = 40 + 110 + 105 + 10 + 105 + 105 = 475
		Campaign campaign = new Builder().confirmed().working(1).readyDevelopment().readyBaseline().
			certificationAttempt(1).boundaries(ReportBoundaries.paired()).cell("formatting", 60, 0).
			cell("plain_list", 100, 0).cell("safety", 140, 1).build();
		RemainingWork work = RemainingWork.of(campaign);
		requireThat(work.phase(), "phase").isEqualTo(Phase.FORMAL);
		requireThat(trials(work, "formatting", Kind.CERTIFICATION), "formatting").isEqualTo(40);
		requireThat(trials(work, "formatting", Kind.CONTROL), "formattingControl").isEqualTo(110);
		requireThat(trials(work, "safety", Kind.CERTIFICATION), "safety").isEqualTo(10);
		requireThat(trials(work, "formatting", Kind.INCUMBENT), "formattingIncumbent").isEqualTo(105);
		requireThat(trials(work, "safety", Kind.CONTROL), "safetyControl").isEqualTo(105);
		requireThat(trials(work, "safety", Kind.INCUMBENT), "safetyIncumbent").isEqualTo(105);
		requireThat(work.entries().size(), "entries").isEqualTo(6);
		requireThat(work.total(), "total").isEqualTo(475);
	}

	/**
	 * Verifies that a cell whose control comparisons are undecided needs the fewest further pairs, in which both
	 * trials pass, that pass its weakest undecided comparison, and the formal trials that those pairs lack. The
	 * candidate is the incumbent, so the cell needs no incumbent trials.
	 */
	@Test
	public void formalControlPairsWhenEveryTrialPasses()
	{
		// Requirement: R-E12
		// Requirement: R-F7
		// formatting: S = 100, F = 0 -> certified minimum-pass-rate part. 40 pairs: uses_requested_tone has 40 pairs
		//             in which both passed, and passes after 65 more at alpha_1 = 1/50; expected_outcome has 40
		//             pairs that only the candidate passed, so it passed. 60 formal outcomes are unpaired, so the
		//             65 control trials need 5 more formal trials -> 65 + 5 = 70
		// plain_list, safety: S = 100, F = 0 -> certified, with passed control comparisons; need nothing
		Map<String, PairCounts> comparisons = new LinkedHashMap<>();
		comparisons.put("uses_requested_tone", new PairCounts(40, 0, 0, 0));
		comparisons.put(TestCase.EXPECTED_OUTCOME, new PairCounts(0, 40, 0, 0));
		Campaign campaign = new Builder().confirmed().readyDevelopment().readyBaseline().
			certificationAttempt(1).boundaries(ReportBoundaries.paired()).cell("formatting", 100, 0).
			controlPairing("formatting", new Pairing(unpaired(60), comparisons)).
			cell("plain_list", 100, 0).cell("safety", 100, 0).build();
		RemainingWork work = RemainingWork.of(campaign);
		requireThat(trials(work, "formatting", Kind.CONTROL), "formatting").isEqualTo(70);
		requireThat(work.entries().size(), "entries").isEqualTo(1);
		requireThat(work.total(), "total").isEqualTo(70);
	}

	/**
	 * Verifies that at a minimum pass rate of 0.95, which implies the control and incumbent comparisons, an unresolved
	 * cell needs only the formal trials of its minimum-pass-rate part, and a certified cell needs nothing.
	 */
	@Test
	public void formalImpliedComparisonsNeedNoPairs()
	{
		// Requirement: R-E12
		// Requirement: R-F9
		// formatting: S = 60,  F = 0 -> certifies at S = 100 -> 40
		// plain_list: S = 100, F = 0 -> certified; needs nothing
		// safety:     S = 140, F = 1 -> certifies at S = 150 -> 10
		// total = 40 + 10 = 50
		Campaign campaign = new Builder().confirmed().working(1).readyDevelopment().readyBaseline().
			certificationAttempt(1).boundaries(ReportBoundaries.boundaries()).cell("formatting", 60, 0).
			cell("plain_list", 100, 0).cell("safety", 140, 1).build();
		RemainingWork work = RemainingWork.of(campaign);
		requireThat(work.entries(), "entries").isEqualTo(List.of(new Entry("formatting", Kind.CERTIFICATION, 40, null),
			new Entry("safety", Kind.CERTIFICATION, 10, null)));
		requireThat(work.total(), "total").isEqualTo(50);
	}

	/**
	 * Verifies that a cell whose undecided control comparison cannot pass within its cap has no minimum, with the
	 * reason. The candidate is the incumbent, so the cell needs no incumbent trials.
	 */
	@Test
	public void formalControlBeyondCapIsUnavailable()
	{
		// Requirement: R-E12
		// Requirement: R-F7
		// Cap 100. formatting: S = 100, F = 0 -> certified minimum-pass-rate part. Both comparisons have 10 pairs in
		// which both passed, and pass after 95 more at alpha_1 = 1/50, but only 90 pairs remain within the cap.
		List<ComparisonBoundaries> neutral = Collections.nCopies(3,
			Boundaries.comparison(100, 60, 40, 4, Boundaries.PAIRED_MINIMUM_PASS_RATE));
		Map<String, PairCounts> comparisons = new LinkedHashMap<>();
		comparisons.put("uses_requested_tone", new PairCounts(10, 0, 0, 0));
		comparisons.put(TestCase.EXPECTED_OUTCOME, new PairCounts(10, 0, 0, 0));
		Campaign campaign = new Builder().confirmed().readyDevelopment().readyBaseline().
			certificationAttempt(1).boundaries(Boundaries.decision(100, 100, 50, 5, neutral,
				Boundaries.PAIRED_MINIMUM_PASS_RATE)).
			cell("formatting", 100, 0).
			controlPairing("formatting", new Pairing(unpaired(90), comparisons)).
			cell("plain_list", 100, 0).cell("safety", 100, 0).build();
		RemainingWork work = RemainingWork.of(campaign);
		requireThat(work.total(), "total").isNull();
		requireThat(work.unavailable(), "unavailable").isNotNull();
		requireThat(work.entries(), "entries").isEqualTo(List.of(new Entry("formatting", Kind.CONTROL, null,
			"The control comparison of uses_requested_tone cannot pass within the cap of 100 pairs.")));
	}

	/**
	 * Verifies that a cell whose control and incumbent comparisons are undecided needs the reference trials of each
	 * kind and counts the formal trials that both kinds lack only once.
	 */
	@Test
	public void formalIncumbentSharesFormalTrials()
	{
		// Requirement: R-E12
		// Requirement: R-F7
		// formatting: S = 100, F = 0 -> certified minimum-pass-rate part. 40 pairs of each kind: uses_requested_tone
		//             has 40 pairs in which both passed, and passes after 65 more at alpha_1 = 1/50;
		//             expected_outcome has 40 pairs that only the candidate passed, so it passed. 60 formal outcomes
		//             are unpaired with either kind, so the 65 control trials need 5 more formal trials -> 65 + 5 = 70,
		//             and the 65 incumbent trials pair with the same formal trials -> 65
		// plain_list, safety: S = 100, F = 0 -> certified, with passed comparisons; need nothing
		Map<String, PairCounts> comparisons = new LinkedHashMap<>();
		comparisons.put("uses_requested_tone", new PairCounts(40, 0, 0, 0));
		comparisons.put(TestCase.EXPECTED_OUTCOME, new PairCounts(0, 40, 0, 0));
		Campaign campaign = new Builder().confirmed().working(1).readyDevelopment().readyBaseline().
			certificationAttempt(1).boundaries(ReportBoundaries.paired()).cell("formatting", 100, 0).
			controlPairing("formatting", new Pairing(unpaired(60), comparisons)).
			incumbentPairing("formatting", new Pairing(unpaired(60), comparisons)).
			cell("plain_list", 100, 0).cell("safety", 100, 0).build();
		RemainingWork work = RemainingWork.of(campaign);
		requireThat(trials(work, "formatting", Kind.CONTROL), "formattingControl").isEqualTo(70);
		requireThat(trials(work, "formatting", Kind.INCUMBENT), "formattingIncumbent").isEqualTo(65);
		requireThat(work.entries().size(), "entries").isEqualTo(2);
		requireThat(work.total(), "total").isEqualTo(135);
	}

	/**
	 * Verifies that a cell whose undecided incumbent comparison cannot pass within its cap has no minimum, with the
	 * reason.
	 */
	@Test
	public void formalIncumbentBeyondCapIsUnavailable()
	{
		// Requirement: R-E12
		// Requirement: R-F7
		// Cap 100. formatting: S = 100, F = 0 -> certified minimum-pass-rate part. Its control comparisons have 10
		// pairs that only the candidate passed and passed. Both incumbent comparisons have 10 pairs in which both
		// passed, and pass after 95 more at alpha_1 = 1/50, but only 90 pairs remain within the cap.
		List<ComparisonBoundaries> neutral = Collections.nCopies(3,
			Boundaries.comparison(100, 60, 40, 4, Boundaries.PAIRED_MINIMUM_PASS_RATE));
		Map<String, PairCounts> control = new LinkedHashMap<>();
		control.put("uses_requested_tone", new PairCounts(0, 10, 0, 0));
		control.put(TestCase.EXPECTED_OUTCOME, new PairCounts(0, 10, 0, 0));
		Map<String, PairCounts> incumbent = new LinkedHashMap<>();
		incumbent.put("uses_requested_tone", new PairCounts(10, 0, 0, 0));
		incumbent.put(TestCase.EXPECTED_OUTCOME, new PairCounts(10, 0, 0, 0));
		Campaign campaign = new Builder().confirmed().working(1).readyDevelopment().readyBaseline().
			certificationAttempt(1).boundaries(Boundaries.decision(100, 100, 50, 5, neutral,
				Boundaries.PAIRED_MINIMUM_PASS_RATE)).
			cell("formatting", 100, 0).
			controlPairing("formatting", new Pairing(unpaired(90), control)).
			incumbentPairing("formatting", new Pairing(unpaired(90), incumbent)).
			cell("plain_list", 100, 0).cell("safety", 100, 0).build();
		RemainingWork work = RemainingWork.of(campaign);
		requireThat(work.total(), "total").isNull();
		requireThat(work.unavailable(), "unavailable").isNotNull();
		requireThat(work.entries(), "entries").isEqualTo(List.of(new Entry("formatting", Kind.CONTROL, 0, null),
			new Entry("formatting", Kind.INCUMBENT, null,
				"The incumbent comparison of uses_requested_tone cannot pass within the cap of 100 pairs.")));
	}

	/**
	 * Returns formal trials of the formatting cell that have no control trial yet.
	 *
	 * @param count the number of trials
	 * @return the trials, each passing only the expected outcome
	 */
	private static List<UnpairedTrial> unpaired(int count)
	{
		return Collections.nCopies(count, new UnpairedTrial(Builder.testCase("formatting", "formal"),
			Set.of(TestCase.EXPECTED_OUTCOME)));
	}

	/**
	 * Verifies that a certified cell and a clearly-below cell need no further trials.
	 */
	@Test
	public void formalResolvedCellsNeedNothing()
	{
		// Requirement: R-E12
		// formatting and plain_list: S = 100, F = 0 certifies (100 >= 100 + 50*0)
		// safety:                   S = 0,   F = 6 is clearly below (any S <= 294 at F = 6)
		// no cell is unresolved, so the minimum is 0
		Campaign campaign = new Builder().confirmed().working(1).readyDevelopment().readyBaseline().
			certificationAttempt(1).boundaries(ReportBoundaries.boundaries()).cell("formatting", 100, 0).
			cell("plain_list", 100, 0).cell("safety", 0, 6).build();
		RemainingWork work = RemainingWork.of(campaign);
		requireThat(work.entries(), "entries").isEmpty();
		requireThat(work.total(), "total").isEqualTo(0);
	}

	/**
	 * Verifies that a cell that cannot certify within its cap has no minimum, with the reason.
	 */
	@Test
	public void formalUnreachableCellHasNoMinimum()
	{
		// Requirement: R-E12
		// safety: S = 200, F = 5 (n = 205 < cap 300). Certifying needs 100 + 50*5 = 350 passes, which no state with
		// 5 failures reaches within cap 300 (at most 295 passes), so it is not clearly below and not reachable.
		Campaign campaign = new Builder().confirmed().working(1).readyDevelopment().readyBaseline().
			certificationAttempt(1).boundaries(ReportBoundaries.boundaries()).cell("formatting", 60, 0).
			cell("safety", 200, 5).build();
		RemainingWork work = RemainingWork.of(campaign);
		Entry safety = null;
		for (Entry entry : work.entries())
		{
			if (entry.cell().equals("safety"))
				safety = entry;
		}
		requireThat(safety, "safety").isNotNull();
		requireThat(safety.trials(), "safety.trials()").isNull();
		requireThat(safety.unavailable(), "safety.unavailable()").isNotNull();
		requireThat(work.total(), "total").isNull();
		requireThat(work.unavailable(), "unavailable").isNotNull();
	}

	/**
	 * Verifies that no minimum is shown before a contract is confirmed or after the campaign ended.
	 */
	@Test
	public void unavailableOutsideTheTrialPhases()
	{
		// Requirement: R-E12
		RemainingWork unconfirmed = RemainingWork.of(new Builder().build());
		requireThat(unconfirmed.phase(), "unconfirmedPhase").isEqualTo(Phase.UNCONFIRMED);
		requireThat(unconfirmed.total(), "unconfirmedTotal").isNull();
		requireThat(unconfirmed.unavailable(), "unconfirmedReason").isNotNull();

		RemainingWork finished = RemainingWork.of(new Builder().confirmed().working(1).readyDevelopment().
			readyBaseline().lifecycle(Lifecycle.FINISHED).build());
		requireThat(finished.total(), "finishedTotal").isNull();
		requireThat(finished.unavailable(), "finishedReason").isNotNull();
	}
}
