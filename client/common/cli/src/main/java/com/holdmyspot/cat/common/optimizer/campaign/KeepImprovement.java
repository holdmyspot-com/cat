package com.holdmyspot.cat.common.optimizer.campaign;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.optimizer.statistics.ExploratoryBounds;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Selects certification attempts that the user may keep as improvements without certification.
 * Comparisons use each cell's counts; a cell is a prompt test under one runner profile.
 */
public final class KeepImprovement
{
	/**
	 * Require this many counted outcomes in each cell that is neither certified nor proven better.
	 */
	public static final int REGRESSION_CHECK_OUTCOMES = 100;

	/**
	 * Prevents instantiation.
	 */
	private KeepImprovement()
	{
	}

	/**
	 * Indicates whether an attempt qualifies to be kept without certification: it ended under the current contract
	 * revision with neither certification nor invalid statistical assumptions, some cell is proven better, none is proven
	 * worse, and each cell not proven better is certified or has at least {@value #REGRESSION_CHECK_OUTCOMES} counted
	 * outcomes. The attempt and the frozen incumbent baseline must record every required condition with equal values.
	 *
	 * @param attempt the attempt
	 * @param currentLineage the current contract revision's number, which an eligible attempt must match
	 * @return {@code true} if the attempt qualifies
	 * @throws NullPointerException if {@code attempt} is null
	 */
	public static boolean qualifies(CertificationAttempt attempt, int currentLineage)
	{
		requireThat(attempt, "attempt").isNotNull();
		Outcome outcome = attempt.outcome();
		if (attempt.lineage() != currentLineage || outcome == null)
		{
			return false;
		}
		switch (outcome)
		{
			case CERTIFIED, INVALID_STATISTICAL_ASSUMPTIONS ->
			{
				return false;
			}
			default ->
			{
			}
		}
		if (!Comparability.of(attempt.identity(), attempt.procedure().baselineIdentity()).isComparable())
			return false;
		boolean provenBetter = false;
		for (String cell : attempt.procedure().cells())
		{
			if (DecisionTable.isProvenWorse(attempt, cell))
				return false;
			if (DecisionTable.isProvenBetter(attempt, cell))
			{
				provenBetter = true;
				continue;
			}
			if (DecisionTable.cellDecision(attempt, cell) != DecisionTable.CellDecision.CERTIFIED &&
				attempt.count(cell).outcomes() < REGRESSION_CHECK_OUTCOMES)
			{
				return false;
			}
		}
		return provenBetter;
	}

	/**
	 * Returns the best qualifying attempt under each set of recorded measurement conditions.
	 * <p>
	 * Attempts are ranked only against attempts with equal recorded conditions: the highest 95% Wilson lower bound
	 * on the weakest cell wins, then more cells proven better, then the more recent attempt.
	 *
	 * @param campaign the campaign
	 * @return the offers: the current identity's first, then each earlier identity's, most recent first
	 * @throws NullPointerException if {@code campaign} is null
	 */
	public static List<ImprovementOffer> offers(Campaign campaign)
	{
		requireThat(campaign, "campaign").isNotNull();
		if (!campaign.isConfirmed())
			return List.of();
		int currentLineage = campaign.lineage().number();
		Map<MeasurementIdentity, CertificationAttempt> best = new LinkedHashMap<>();
		for (CertificationAttempt attempt : campaign.attempts())
		{
			if (!qualifies(attempt, currentLineage))
				continue;
			best.merge(attempt.identity(), attempt, KeepImprovement::better);
		}
		MeasurementIdentity current = campaign.identity();
		List<ImprovementOffer> offers = new ArrayList<>();
		CertificationAttempt currentBest = best.remove(current);
		if (currentBest != null)
			offers.add(new ImprovementOffer(currentBest.index(), current, false, List.of()));
		List<CertificationAttempt> earlier = new ArrayList<>(best.values());
		earlier.sort(Comparator.comparingInt(CertificationAttempt::index).reversed());
		for (CertificationAttempt attempt : earlier)
		{
			offers.add(new ImprovementOffer(attempt.index(), attempt.identity(), true,
				attempt.identity().changes(current)));
		}
		return offers;
	}

	/**
	 * Returns the better of two qualifying attempts with equal recorded measurement conditions.
	 *
	 * @param first an attempt
	 * @param second a later attempt
	 * @return the attempt with the higher lower bound on its weakest cell, then more cells proven better,
	 *   then {@code second}
	 */
	private static CertificationAttempt better(CertificationAttempt first, CertificationAttempt second)
	{
		int byWeakest = Double.compare(weakestLowerBound(first), weakestLowerBound(second));
		if (byWeakest != 0)
		{
			if (byWeakest > 0)
				return first;
			return second;
		}
		if (provenBetterCells(first) > provenBetterCells(second))
			return first;
		return second;
	}

	/**
	 * Returns the 95% Wilson lower bound on an attempt's weakest cell.
	 *
	 * @param attempt the attempt
	 * @return the smallest lower bound across the attempt's cells
	 */
	private static double weakestLowerBound(CertificationAttempt attempt)
	{
		double weakest = 1;
		for (String cell : attempt.procedure().cells())
		{
			CellCounts counts = attempt.count(cell);
			weakest = Math.min(weakest, ExploratoryBounds.wilson(counts.passes(), counts.outcomes()).lower());
		}
		return weakest;
	}

	/**
	 * Returns the number of an attempt's cells that are proven better than the incumbent.
	 *
	 * @param attempt the attempt
	 * @return the number of cells
	 */
	private static int provenBetterCells(CertificationAttempt attempt)
	{
		int count = 0;
		for (String cell : attempt.procedure().cells())
		{
			if (DecisionTable.isProvenBetter(attempt, cell))
				++count;
		}
		return count;
	}
}
