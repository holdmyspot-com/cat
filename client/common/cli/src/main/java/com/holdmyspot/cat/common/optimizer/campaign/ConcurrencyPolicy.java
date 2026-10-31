package com.holdmyspot.cat.common.optimizer.campaign;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.optimizer.contract.Cell;
import com.holdmyspot.cat.common.optimizer.statistics.DecisionBoundaries;
import com.holdmyspot.cat.common.optimizer.statistics.PairTally;
import com.holdmyspot.cat.common.optimizer.statistics.PairedComparison;
import com.holdmyspot.cat.common.optimizer.statistics.SignificanceLevel;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Limits how many trials run at once.
 * <p>
 * Trials belong to units of work: a working candidate's development series, one cell's part of a certification
 * attempt, one round of a formal failure group's reproduction, or one prompt author attempt's promotion runs. A unit's
 * spare failures are the further failures that consume one, which it can absorb before the next one decides it and
 * wastes the trials still running. A unit starts at its spare failures plus one, at most the campaign's ceiling, and
 * its window grows and drops as {@link Window} describes. A run without an outcome halves every window, as
 * {@link ConcurrencyWindows} describes.
 * <p>
 * The campaign's window is the sum of the limits of the units that admit trials now, at most the ceiling and the
 * throttle's limit. Each unit admits at most its own limit; a formal cell also admits at most its cell limit.
 */
public final class ConcurrencyPolicy
{
	/**
	 * Prevents instantiation.
	 */
	private ConcurrencyPolicy()
	{
	}

	/**
	 * Returns the most trials that may run at once for the work that the campaign does now: the sum of the limits of
	 * the units of work that admit trials, at most the campaign's ceiling and the throttle's limit. When no unit
	 * admits trials, the working candidate's development series stands in for them.
	 *
	 * @param campaign the campaign
	 * @return the maximum simultaneous trials
	 * @throws NullPointerException if {@code campaign} is null
	 */
	public static int window(Campaign campaign)
	{
		requireThat(campaign, "campaign").isNotNull();
		long total = 0;
		for (WorkUnit unit : admittedUnits(campaign))
			total += limit(campaign, unit);
		if (total == 0)
			total = limit(campaign, WorkUnit.development(campaign.development().series()));
		return campaign.throttle().limit((int) Math.min(total, campaign.ceiling()));
	}

	/**
	 * Returns the most trials of a unit of work that may run at once.
	 *
	 * @param campaign the campaign
	 * @param unit the unit of work
	 * @return the unit's window, at most the ceiling, after the halvings in effect
	 * @throws NullPointerException if any argument is null
	 */
	public static int limit(Campaign campaign, WorkUnit unit)
	{
		requireThat(campaign, "campaign").isNotNull();
		requireThat(unit, "unit").isNotNull();
		return campaign.windows().limit(unit, spareFailures(campaign, unit), campaign.ceiling());
	}

	/**
	 * Returns the units of work that admit trials now: the running attempt's undecided open cells; otherwise the
	 * reproducing failure groups' current rounds that have test cases; otherwise the working candidate's development
	 * series, unless development is paused; otherwise the prompt author attempts under validation.
	 *
	 * @param campaign the campaign
	 * @return the units, empty if none admits trials
	 */
	static List<WorkUnit> admittedUnits(Campaign campaign)
	{
		List<WorkUnit> units = new ArrayList<>();
		if (!campaign.isConfirmed())
			return units;
		Optional<CertificationAttempt> running = campaign.runningAttempt();
		if (running.isPresent())
		{
			CertificationAttempt attempt = running.get();
			for (Cell cell : CellTier.openCells(campaign, attempt))
			{
				if (DecisionTable.cellDecision(attempt, cell.id()) == DecisionTable.CellDecision.UNDECIDED)
					units.add(formalUnit(attempt, cell.id()));
			}
			return units;
		}
		Optional<CertificationAttempt> reproducing = campaign.reproducing();
		if (reproducing.isPresent())
		{
			List<FailureGroup> groups = reproducing.get().groups();
			for (int i = 0; i < groups.size(); ++i)
			{
				FailureGroup group = groups.get(i);
				if (group.status() == FailureGroup.Status.REPRODUCING && !group.round().awaitsValues())
					units.add(reproductionUnit(i + 1, group));
			}
			return units;
		}
		Development development = campaign.development();
		if (!development.isPaused())
		{
			units.add(WorkUnit.development(development.series()));
			return units;
		}
		for (PromptAuthorAttempt attempt : pendingPromptAuthors(campaign))
			units.add(WorkUnit.promotion(attempt));
		return units;
	}

	/**
	 * Returns the prompt author attempts whose promotion decision is pending.
	 *
	 * @param campaign the campaign
	 * @return the attempts, in validation order
	 */
	static List<PromptAuthorAttempt> pendingPromptAuthors(Campaign campaign)
	{
		Development development = campaign.development();
		List<PromptAuthorAttempt> pending = new ArrayList<>();
		Round round = development.round();
		PromptAuthorAttempt promptAuthor = development.promptAuthor();
		if (round != null && round.weak() && promptAuthor == null)
		{
			for (PromptAuthorAttempt attempt : round.validationAttempts())
			{
				if (Promotion.decide(attempt, campaign) == Promotion.Decision.PENDING)
					pending.add(attempt);
			}
		}
		else if (promptAuthor != null && Promotion.decide(promptAuthor, campaign) == Promotion.Decision.PENDING)
			pending.add(promptAuthor);
		return pending;
	}

	/**
	 * Returns the unit of work of a cell of a certification attempt: its minimum-pass-rate part until its paired
	 * comparisons start, then its paired comparisons.
	 *
	 * @param attempt the certification attempt
	 * @param cell the cell's identifier
	 * @return the unit
	 */
	static WorkUnit formalUnit(CertificationAttempt attempt, String cell)
	{
		return WorkUnit.formal(attempt.index(), cell, attempt.pairing(Reference.CONTROL, cell).isComparing());
	}

	/**
	 * Returns the unit of work of a failure group's current round.
	 *
	 * @param number the group's 1-based position in its attempt
	 * @param group the failure group
	 * @return the unit
	 */
	static WorkUnit reproductionUnit(int number, FailureGroup group)
	{
		return WorkUnit.reproduction(number, group.rounds().size());
	}

	/**
	 * Indicates whether a unit of work can still admit trials or adopt outcomes, so that its window is kept.
	 *
	 * @param campaign the campaign
	 * @param unit the unit of work
	 * @return {@code true} if the unit is the current development series, an undecided cell's current part of the
	 *   running attempt, a reproducing group's current round or a prompt author attempt under validation
	 */
	static boolean isCurrent(Campaign campaign, WorkUnit unit)
	{
		return switch (unit.purpose())
		{
			case DEVELOPMENT -> unit.series() == campaign.development().series();
			case FORMAL ->
			{
				Optional<CertificationAttempt> running = campaign.runningAttempt();
				if (running.isEmpty() || running.get().index() != unit.series())
					yield false;
				CertificationAttempt attempt = running.get();
				yield attempt.procedure().cells().contains(unit.part()) &&
					DecisionTable.cellDecision(attempt, unit.part()) == DecisionTable.CellDecision.UNDECIDED &&
					formalUnit(attempt, unit.part()).equals(unit);
			}
			case REPRODUCTION ->
			{
				Optional<CertificationAttempt> reproducing = campaign.reproducing();
				if (reproducing.isEmpty())
					yield false;
				List<FailureGroup> groups = reproducing.get().groups();
				if (unit.series() < 1 || unit.series() > groups.size())
					yield false;
				FailureGroup group = groups.get(unit.series() - 1);
				yield group.status() == FailureGroup.Status.REPRODUCING &&
					reproductionUnit(unit.series(), group).equals(unit);
			}
			case PROMOTION -> findPromptAuthor(campaign, unit).isPresent();
			default -> false;
		};
	}

	/**
	 * Finds the prompt author attempt of a promotion unit among the attempts under validation.
	 *
	 * @param campaign the campaign
	 * @param unit a promotion unit
	 * @return the attempt, or empty if no attempt under validation has the unit's number and snapshot
	 */
	private static Optional<PromptAuthorAttempt> findPromptAuthor(Campaign campaign, WorkUnit unit)
	{
		Development development = campaign.development();
		List<PromptAuthorAttempt> attempts = new ArrayList<>();
		if (development.promptAuthor() != null)
			attempts.add(development.promptAuthor());
		if (development.round() != null)
			attempts.addAll(development.round().validationAttempts());
		for (PromptAuthorAttempt attempt : attempts)
		{
			if (WorkUnit.promotion(attempt).equals(unit))
				return Optional.of(attempt);
		}
		return Optional.empty();
	}

	/**
	 * Returns a unit of work's spare failures: the further failures that consume one, which it can absorb before the
	 * next one decides it.
	 * <ul>
	 *   <li>Development: 0, because a blocking failure fails fast.</li>
	 *   <li>Formal: the further failures before the cell's minimum-pass-rate part is clearly below, out of reach or at
	 *   its cap; during the paired comparisons, the further pairs that only the reference trial passes before a
	 *   comparison is below its reference, out of reach or at its cap.</li>
	 *   <li>Reproduction: the further matched failures before the round reproduces its group.</li>
	 *   <li>Promotion: the triggering test cases that the attempt can still fix, less one; none for a minimality
	 *   reduction; and no more than the failing regression runs that keep each prompt test's failures likely for the
	 *   working candidate.</li>
	 * </ul>
	 *
	 * @param campaign the campaign
	 * @param unit the unit of work
	 * @return the spare failures, 0 if the unit's work no longer exists
	 */
	static int spareFailures(Campaign campaign, WorkUnit unit)
	{
		return switch (unit.purpose())
		{
			case FORMAL ->
			{
				if (unit.series() < 1 || unit.series() > campaign.attempts().size())
					yield 0;
				CertificationAttempt attempt = campaign.attempt(unit.series());
				if (!attempt.procedure().cells().contains(unit.part()))
					yield 0;
				yield formalSpareFailures(attempt, unit.part(), unit.stage() == 1);
			}
			case REPRODUCTION ->
			{
				Optional<CertificationAttempt> reproducing = campaign.reproducing();
				if (reproducing.isEmpty() || unit.series() < 1 || unit.series() > reproducing.get().groups().size())
					yield 0;
				yield reproductionSpareFailures(reproducing.get().group(unit.series()).round());
			}
			case PROMOTION ->
			{
				Optional<PromptAuthorAttempt> attempt = findPromptAuthor(campaign, unit);
				if (attempt.isEmpty())
					yield 0;
				yield Promotion.spareFailures(attempt.get(), campaign.lineage().regressionSet(),
					campaign.development().testCaseOutcomes());
			}
			default -> 0;
		};
	}

	/**
	 * Returns a reproduction round's spare failures: the further matched failures before it reproduces its group.
	 *
	 * @param round the round
	 * @return the spare failures
	 */
	static int reproductionSpareFailures(ReproductionRound round)
	{
		return Math.max(0, FailureGroup.FAILURES - 1 - round.failures());
	}

	/**
	 * Returns a formal cell's spare failures.
	 *
	 * @param attempt the certification attempt
	 * @param cell the cell's identifier
	 * @param comparing {@code true} for the paired comparisons, {@code false} for the minimum-pass-rate part
	 * @return the spare failures, at most {@value Campaign#MAXIMUM_CEILING}
	 */
	static int formalSpareFailures(CertificationAttempt attempt, String cell, boolean comparing)
	{
		DecisionBoundaries boundaries = attempt.procedure().boundaries();
		if (!comparing)
			return minimumSpareFailures(boundaries, attempt.count(cell));
		int spare = Campaign.MAXIMUM_CEILING;
		for (Reference reference : Reference.values())
		{
			Pairing pairing = attempt.pairing(reference, cell);
			int remaining = DecisionTable.remainingPairs(pairing, boundaries);
			for (PairCounts comparison : pairing.comparisons().values())
			{
				spare = Math.min(spare, pairedSpareFailures(comparison.tally(), remaining,
					boundaries.level()));
			}
		}
		return spare;
	}

	/**
	 * Returns the further failures that a cell's minimum-pass-rate part absorbs before it is clearly below, out of
	 * reach or at its cap.
	 *
	 * @param boundaries the attempt's boundaries
	 * @param counts the cell's adopted counts
	 * @return the spare failures, at most {@value Campaign#MAXIMUM_CEILING}
	 */
	private static int minimumSpareFailures(DecisionBoundaries boundaries, CellCounts counts)
	{
		int passes = counts.passes();
		int failures = counts.failures();
		for (int further = 1; further <= Campaign.MAXIMUM_CEILING; ++further)
		{
			if (passes + failures + further >= boundaries.cap() ||
				boundaries.isClearlyBelow(passes, failures + further) || !boundaries.canCertifyWith(failures + further))
			{
				return further - 1;
			}
		}
		return Campaign.MAXIMUM_CEILING;
	}

	/**
	 * Returns the further pairs that only the reference trial passes, which a comparison absorbs before it is below
	 * its reference, can no longer pass within the cap, or reaches the cap.
	 *
	 * @param tally the comparison's adopted pairs
	 * @param remaining the pairs that the cap still permits
	 * @param level the attempt's certification level
	 * @return the spare unfavourable pairs, at most {@value Campaign#MAXIMUM_CEILING}
	 */
	private static int pairedSpareFailures(PairTally tally, int remaining, SignificanceLevel level)
	{
		for (int further = 1; further <= Campaign.MAXIMUM_CEILING; ++further)
		{
			if (further > remaining)
				return further - 1;
			int referenceAhead = tally.referenceAhead() + further;
			PairTally after = new PairTally(referenceAhead, tally.even(), tally.candidateAhead());
			PairTally best = new PairTally(referenceAhead, tally.even(),
				tally.candidateAhead() + remaining - further);
			if (PairedComparison.isBelow(after, level) || !PairedComparison.passes(best, level))
				return further - 1;
		}
		return Campaign.MAXIMUM_CEILING;
	}

	/**
	 * Returns the most certification trials of a cell (prompt test and trial profile) that may await inclusion in
	 * evidence at once: the passes still required to certify it, at least 1, and never more than its remaining cap.
	 *
	 * @param boundaries the attempt's boundaries
	 * @param counts the cell's certification outcomes included in evidence
	 * @return the limit, 0 if the cell reached its cap
	 * @throws NullPointerException if any argument is null
	 * @throws IllegalArgumentException if the counts exceed the cap
	 */
	public static int cellLimit(DecisionBoundaries boundaries, CellCounts counts)
	{
		requireThat(boundaries, "boundaries").isNotNull();
		requireThat(counts, "counts").isNotNull();
		requireThat(counts.outcomes(), "counts.outcomes()").isLessThanOrEqualTo(boundaries.cap(), "boundaries.cap()");

		int remaining = boundaries.cap() - counts.outcomes();
		int required = boundaries.minimumCertifyingPasses().get(counts.failures()) - counts.passes();
		return Math.min(Math.max(1, required), remaining);
	}
}
