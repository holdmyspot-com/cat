package io.github.cowwoc.cat.common.optimizer.campaign;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.optimizer.statistics.ComparisonBoundaries;
import io.github.cowwoc.cat.common.optimizer.statistics.DecisionBoundaries;

/**
 * The formal decisions of {@code design.md} section 2.8.
 * <p>
 * Decisions use only the adopted counts. A frozen cap is never raised, and an ended attempt never reopens.
 */
public final class DecisionTable
{
	/**
	 * The state of one category of a certification attempt.
	 */
	public enum CategoryDecision
	{
		/**
		 * No decision yet.
		 */
		UNDECIDED,
		/**
		 * The category certifies.
		 */
		CERTIFIED,
		/**
		 * The category is clearly below 95%.
		 */
		CLEARLY_BELOW,
		/**
		 * The category reached its cap undecided.
		 */
		CAP_REACHED
	}

	/**
	 * The state of a certification attempt.
	 */
	public enum AttemptDecision
	{
		/**
		 * The attempt continues.
		 */
		UNDECIDED,
		/**
		 * Every category certified and none is proven worse than the incumbent.
		 */
		CERTIFIED,
		/**
		 * Every category certified and some category is proven worse than the incumbent.
		 */
		CERTIFIED_PROVEN_WORSE,
		/**
		 * Some category is clearly below 95%.
		 */
		BELOW_MINIMUM,
		/**
		 * Some category reached its cap undecided, and none is clearly below.
		 */
		INCONCLUSIVE
	}

	/**
	 * What the campaign does with an attempt's decision.
	 */
	public enum Action
	{
		/**
		 * Keep testing.
		 */
		CONTINUE,
		/**
		 * Deliver the certified candidate automatically.
		 */
		DELIVER,
		/**
		 * Retain the incumbent until the user explicitly accepts the regression trade-off.
		 */
		AWAIT_TRADE_OFF,
		/**
		 * Return to development automatically, with the formal failures as diagnostic input.
		 */
		RETURN_TO_DEVELOPMENT,
		/**
		 * Pause and offer {@code keep improvement}, {@code optimize further} and {@code stop}.
		 */
		OFFER_KEEP_IMPROVEMENT
	}

	/**
	 * Prevents instantiation.
	 */
	private DecisionTable()
	{
	}

	/**
	 * Decides one category.
	 *
	 * @param boundaries the attempt's boundaries
	 * @param counts the category's adopted formal counts
	 * @return the decision
	 * @throws NullPointerException if any argument is null
	 * @throws IllegalArgumentException if the counts exceed the cap
	 */
	public static CategoryDecision categoryDecision(DecisionBoundaries boundaries, CategoryCounts counts)
	{
		requireThat(boundaries, "boundaries").isNotNull();
		requireThat(counts, "counts").isNotNull();
		if (boundaries.certifies(counts.passes(), counts.failures()))
			return CategoryDecision.CERTIFIED;
		if (boundaries.isClearlyBelow(counts.passes(), counts.failures()))
			return CategoryDecision.CLEARLY_BELOW;
		if (counts.outcomes() >= boundaries.cap())
			return CategoryDecision.CAP_REACHED;
		return CategoryDecision.UNDECIDED;
	}

	/**
	 * Decides a certification attempt from its adopted counts.
	 *
	 * @param attempt the attempt
	 * @return the decision
	 * @throws NullPointerException if {@code attempt} is null
	 */
	public static AttemptDecision attemptDecision(CertificationAttempt attempt)
	{
		requireThat(attempt, "attempt").isNotNull();
		DecisionBoundaries boundaries = attempt.procedure().boundaries();
		boolean capReached = false;
		boolean allCertified = true;
		boolean provenWorse = false;
		for (String category : attempt.procedure().categories())
		{
			CategoryDecision decision = categoryDecision(boundaries, attempt.count(category));
			switch (decision)
			{
				case CLEARLY_BELOW ->
				{
					return AttemptDecision.BELOW_MINIMUM;
				}
				case CAP_REACHED ->
				{
					capReached = true;
					allCertified = false;
				}
				case UNDECIDED -> allCertified = false;
				case CERTIFIED ->
				{
					if (isProvenWorse(attempt, category))
						provenWorse = true;
				}
			}
		}
		if (capReached)
			return AttemptDecision.INCONCLUSIVE;
		if (!allCertified)
			return AttemptDecision.UNDECIDED;
		if (provenWorse)
			return AttemptDecision.CERTIFIED_PROVEN_WORSE;
		return AttemptDecision.CERTIFIED;
	}

	/**
	 * Returns the action that follows an attempt's decision.
	 *
	 * @param decision the attempt's decision
	 * @param qualifiesForKeep {@code true} if the attempt meets the keep-improvement conditions
	 * @return the action
	 * @throws NullPointerException if {@code decision} is null
	 */
	public static Action action(AttemptDecision decision, boolean qualifiesForKeep)
	{
		requireThat(decision, "decision").isNotNull();
		return switch (decision)
		{
			case UNDECIDED -> Action.CONTINUE;
			case CERTIFIED -> Action.DELIVER;
			case CERTIFIED_PROVEN_WORSE -> Action.AWAIT_TRADE_OFF;
			case BELOW_MINIMUM, INCONCLUSIVE ->
			{
				if (qualifiesForKeep)
					yield Action.OFFER_KEEP_IMPROVEMENT;
				yield Action.RETURN_TO_DEVELOPMENT;
			}
		};
	}

	/**
	 * Indicates whether a category of an attempt is proven better than the incumbent at the attempt's improvement
	 * level.
	 *
	 * @param attempt the attempt
	 * @param category the category
	 * @return {@code true} if the category is proven better
	 * @throws NullPointerException if any argument is null
	 * @throws IllegalArgumentException if the attempt's procedure has no such category
	 */
	public static boolean isProvenBetter(CertificationAttempt attempt, String category)
	{
		requireThat(attempt, "attempt").isNotNull();
		requireThat(category, "category").isNotNull();
		CategoryCounts counts = attempt.count(category);
		return comparison(attempt, category).isProvenBetter(counts.passes(), counts.failures());
	}

	/**
	 * Indicates whether a category of an attempt is proven worse than the incumbent at the attempt's improvement
	 * level.
	 *
	 * @param attempt the attempt
	 * @param category the category
	 * @return {@code true} if the category is proven worse
	 * @throws NullPointerException if any argument is null
	 * @throws IllegalArgumentException if the attempt's procedure has no such category
	 */
	public static boolean isProvenWorse(CertificationAttempt attempt, String category)
	{
		requireThat(attempt, "attempt").isNotNull();
		requireThat(category, "category").isNotNull();
		CategoryCounts counts = attempt.count(category);
		return comparison(attempt, category).isProvenWorse(counts.passes(), counts.failures());
	}

	/**
	 * Returns a category's comparison boundaries.
	 *
	 * @param attempt the attempt
	 * @param category the category
	 * @return the boundaries
	 * @throws IllegalArgumentException if the attempt's procedure has no such category
	 */
	private static ComparisonBoundaries comparison(CertificationAttempt attempt, String category)
	{
		CertificationProcedure procedure = attempt.procedure();
		return procedure.boundaries().comparisons().get(procedure.position(category));
	}
}
