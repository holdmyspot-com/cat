package io.github.cowwoc.cat.common.optimizer.campaign;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.optimizer.statistics.DecisionBoundaries;
import io.github.cowwoc.cat.common.optimizer.statistics.ExploratoryBounds;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The keep-improvement conditions and offers of {@code design.md} section 2.10.
 */
public final class KeepImprovement
{
	/**
	 * The adopted outcomes that a category neither certified nor proven better needs as a regression check.
	 */
	public static final int REGRESSION_CHECK_OUTCOMES = 100;

	/**
	 * Prevents instantiation.
	 */
	private KeepImprovement()
	{
	}

	/**
	 * Indicates whether an attempt meets the keep-improvement conditions: it ended without certification under the
	 * current lineage, some category is proven better, none is proven worse, and every other category certified or
	 * has at least {@value #REGRESSION_CHECK_OUTCOMES} adopted outcomes.
	 *
	 * @param attempt the attempt
	 * @param currentLineage the current lineage's number
	 * @return {@code true} if the attempt qualifies
	 * @throws NullPointerException if {@code attempt} is null
	 */
	public static boolean qualifies(CertificationAttempt attempt, int currentLineage)
	{
		requireThat(attempt, "attempt").isNotNull();
		Outcome outcome = attempt.outcome();
		if (attempt.lineage() != currentLineage || outcome == null || outcome == Outcome.CERTIFIED ||
			outcome == Outcome.INVALID_STATISTICAL_ASSUMPTIONS)
		{
			return false;
		}
		DecisionBoundaries boundaries = attempt.procedure().boundaries();
		boolean provenBetter = false;
		for (String category : attempt.procedure().categories())
		{
			if (DecisionTable.isProvenWorse(attempt, category))
				return false;
			if (DecisionTable.isProvenBetter(attempt, category))
			{
				provenBetter = true;
				continue;
			}
			CategoryCounts counts = attempt.count(category);
			if (!boundaries.certifies(counts.passes(), counts.failures()) &&
				counts.outcomes() < REGRESSION_CHECK_OUTCOMES)
			{
				return false;
			}
		}
		return provenBetter;
	}

	/**
	 * Returns the best qualifying attempt of each measurement identity.
	 * <p>
	 * Attempts are ranked only against attempts with an equal identity: the highest 95% Wilson lower bound on the
	 * weakest category wins, then more categories proven better, then the more recent attempt.
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
	 * Returns the better of two qualifying attempts with an equal identity.
	 *
	 * @param first an attempt
	 * @param second a later attempt
	 * @return the attempt with the higher lower bound on its weakest category, then more categories proven better,
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
		if (provenBetterCategories(first) > provenBetterCategories(second))
			return first;
		return second;
	}

	/**
	 * Returns the 95% Wilson lower bound on an attempt's weakest category.
	 *
	 * @param attempt the attempt
	 * @return the smallest lower bound across the attempt's categories
	 */
	private static double weakestLowerBound(CertificationAttempt attempt)
	{
		double weakest = 1;
		for (String category : attempt.procedure().categories())
		{
			CategoryCounts counts = attempt.count(category);
			weakest = Math.min(weakest, ExploratoryBounds.wilson(counts.passes(), counts.outcomes()).lower());
		}
		return weakest;
	}

	/**
	 * Returns the number of an attempt's categories that are proven better than the incumbent.
	 *
	 * @param attempt the attempt
	 * @return the number of categories
	 */
	private static int provenBetterCategories(CertificationAttempt attempt)
	{
		int count = 0;
		for (String category : attempt.procedure().categories())
		{
			if (DecisionTable.isProvenBetter(attempt, category))
				++count;
		}
		return count;
	}
}
