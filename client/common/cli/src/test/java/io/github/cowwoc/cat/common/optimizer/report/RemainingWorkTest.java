package io.github.cowwoc.cat.common.optimizer.report;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.optimizer.campaign.Campaign;
import io.github.cowwoc.cat.common.optimizer.campaign.Builder;
import io.github.cowwoc.cat.common.optimizer.campaign.Lifecycle;
import io.github.cowwoc.cat.common.optimizer.campaign.Outcome;
import io.github.cowwoc.cat.common.optimizer.report.RemainingWork.Entry;
import io.github.cowwoc.cat.common.optimizer.report.RemainingWork.Kind;
import io.github.cowwoc.cat.common.optimizer.statistics.FirstAttemptBoundaries;
import org.testng.annotations.Test;

/**
 * Tests {@link RemainingWork} with hand-computed values for both phases.
 * <p>
 * The fixture contract has the categories {@code formatting} and {@code safety}, each with one cell of the same
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
	 * Verifies development counts that need readiness trials only.
	 */
	@Test
	public void developmentReadinessAndNextAttempt()
	{
		// Requirement: R-E12
		// formatting: n = 60, f = 0         -> max(0, max(100, 100*0) - 60)  =  40
		// safety:     n = 100, f = 0        -> max(0, max(100, 0) - 100)     =   0
		// baseline met (50 and 50)          ->                                  0 + 0
		// next attempt (i = 1): 93 per cell                                  -> 93 + 93 = 186
		// total = 40 + 0 + 0 + 0 + 186 = 226
		Campaign campaign = new Builder().confirmed().working(1).development("formatting", 60, 0).
			development("safety", 100, 0).readyBaseline().build();
		RemainingWork work = RemainingWork.of(campaign);
		requireThat(work.phase(), "phase").isEqualTo(Phase.DEVELOPMENT);
		requireThat(trials(work, "formatting", Kind.READINESS), "formattingReadiness").isEqualTo(40);
		requireThat(trials(work, "safety", Kind.READINESS), "safetyReadiness").isEqualTo(0);
		requireThat(trials(work, "formatting", Kind.NEXT_ATTEMPT), "formattingNext").isEqualTo(93);
		requireThat(trials(work, "safety", Kind.NEXT_ATTEMPT), "safetyNext").isEqualTo(93);
		requireThat(work.total(), "total").isEqualTo(226);
		requireThat(work.unavailable(), "unavailable").isNull();
	}

	/**
	 * Verifies that a recent batch with 2 failures delays readiness beyond the simple count formula.
	 */
	@Test
	public void developmentRecentBatchDelaysReadiness()
	{
		// Requirement: R-E12
		// formatting: 198 passes, 2 failures in batches [0, 0, 2, 0] at n = 200. The simple formula gives
		// max(0, max(100, 200) - 200) = 0, but the latest two complete batches hold 2 and 0 failures. After 50
		// more passes batch 5 completes with 0 failures, so the latest two (0, 0) are within the limit and 248/250
		// passes: 50 trials are needed. Smaller additions leave the latest two batches at (2, 0) or (0, partial).
		Campaign campaign = new Builder().confirmed().working(1).
			development("formatting", 198, java.util.List.of(0, 0, 2, 0)).development("safety", 100, 0).
			readyBaseline().build();
		RemainingWork work = RemainingWork.of(campaign);
		requireThat(trials(work, "formatting", Kind.READINESS), "formattingReadiness").isEqualTo(50);
		requireThat(trials(work, "safety", Kind.READINESS), "safetyReadiness").isEqualTo(0);
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
		// next attempt (i = 1): 93 + 93 = 186
		// total = 147 + 100 + 20 + 3 + 186 = 456
		Campaign campaign = new Builder().confirmed().working(1).development("formatting", 150, 3).
			baseline("formatting", 30, 0).baseline("safety", 45, 2).build();
		RemainingWork work = RemainingWork.of(campaign);
		requireThat(trials(work, "formatting", Kind.READINESS), "formattingReadiness").isEqualTo(147);
		requireThat(trials(work, "safety", Kind.READINESS), "safetyReadiness").isEqualTo(100);
		requireThat(trials(work, "formatting", Kind.BASELINE), "formattingBaseline").isEqualTo(20);
		requireThat(trials(work, "safety", Kind.BASELINE), "safetyBaseline").isEqualTo(3);
		requireThat(work.total(), "total").isEqualTo(456);
	}

	/**
	 * Verifies that the next attempt's minimum follows the certification index.
	 */
	@Test
	public void developmentNextAttemptUsesNextIndex()
	{
		// Requirement: R-E12
		// attempt 1 ended; the next attempt is 2, whose all-pass minimum is 117 per cell.
		// readiness and baseline are met, so total = 117 + 117 = 234
		Campaign campaign = new Builder().confirmed().working(1).readyDevelopment().readyBaseline().
			certificationAttempt(1).outcome(Outcome.INCONCLUSIVE).build();
		RemainingWork work = RemainingWork.of(campaign);
		requireThat(work.phase(), "phase").isEqualTo(Phase.DEVELOPMENT);
		requireThat(trials(work, "formatting", Kind.NEXT_ATTEMPT), "formattingNext").isEqualTo(117);
		requireThat(trials(work, "safety", Kind.NEXT_ATTEMPT), "safetyNext").isEqualTo(117);
		requireThat(work.total(), "total").isEqualTo(234);
	}

	/**
	 * Verifies that only unresolved cells of a running attempt need trials, each to its frozen boundary.
	 */
	@Test
	public void formalUnresolvedCells()
	{
		// Requirement: R-E12
		// Boundaries: a cell certifies at 100 + 50*F passes.
		// formatting: S = 60,  F = 0 -> undecided; certifies at S = 100 -> 100 - 60 = 40
		// safety:     S = 140, F = 1 -> undecided; certifies at S = 150 -> 150 - 140 = 10
		// total = 50
		Campaign campaign = new Builder().confirmed().working(1).readyDevelopment().readyBaseline().
			certificationAttempt(1).boundaries(ReportBoundaries.boundaries()).cell("formatting", 60, 0).
			cell("safety", 140, 1).build();
		RemainingWork work = RemainingWork.of(campaign);
		requireThat(work.phase(), "phase").isEqualTo(Phase.FORMAL);
		requireThat(trials(work, "formatting", Kind.CERTIFICATION), "formatting").isEqualTo(40);
		requireThat(trials(work, "safety", Kind.CERTIFICATION), "safety").isEqualTo(10);
		requireThat(work.entries().size(), "entries").isEqualTo(2);
		requireThat(work.total(), "total").isEqualTo(50);
	}

	/**
	 * Verifies that a certified cell and a clearly-below cell need no further trials.
	 */
	@Test
	public void formalResolvedCellsNeedNothing()
	{
		// Requirement: R-E12
		// formatting: S = 100, F = 0 certifies (100 >= 100 + 50*0)
		// safety:     S = 0,   F = 6 is clearly below (any S <= 294 at F = 6)
		// no cell is unresolved, so the minimum is 0
		Campaign campaign = new Builder().confirmed().working(1).readyDevelopment().readyBaseline().
			certificationAttempt(1).boundaries(ReportBoundaries.boundaries()).cell("formatting", 100, 0).
			cell("safety", 0, 6).build();
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
