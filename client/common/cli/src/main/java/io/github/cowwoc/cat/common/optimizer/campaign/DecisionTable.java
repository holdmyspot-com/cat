package io.github.cowwoc.cat.common.optimizer.campaign;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.optimizer.statistics.ComparisonBoundaries;
import io.github.cowwoc.cat.common.optimizer.statistics.ControlComparison;
import io.github.cowwoc.cat.common.optimizer.statistics.DecisionBoundaries;
import io.github.cowwoc.cat.common.optimizer.statistics.PairTally;
import io.github.cowwoc.cat.common.optimizer.statistics.SignificanceLevel;

/**
 * Maps adopted formal counts to cell and certification-attempt decisions, then selects the campaign action.
 * <p>
 * A cell tests one prompt test with one model and reasoning effort. Decisions use each cell's adopted counts and the
 * attempt's fixed boundaries; this class does not change the attempt or campaign.
 */
public final class DecisionTable
{
	/**
	 * The decision for one cell of a certification attempt.
	 */
	public enum CellDecision
	{
		/**
		 * No decision yet.
		 */
		UNDECIDED,
		/**
		 * The cell certifies.
		 */
		CERTIFIED,
		/**
		 * The cell's pass rate is clearly below the minimum pass rate.
		 */
		CLEARLY_BELOW,
		/**
		 * The cell reached its outcome cap without certification or a clearly-below decision.
		 */
		CAP_REACHED,
		/**
		 * The cell's minimum-pass-rate part certified, but the candidate passes one of its assertions less often than
		 * the control.
		 */
		BELOW_CONTROL
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
		 * Every cell certified and none is proven worse than the incumbent.
		 */
		CERTIFIED,
		/**
		 * Every cell certified and at least one is proven worse than the incumbent.
		 */
		CERTIFIED_PROVEN_WORSE,
		/**
		 * At least one cell's pass rate is clearly below the minimum pass rate.
		 */
		BELOW_MINIMUM,
		/**
		 * At least one cell passes one of its assertions less often than the control.
		 */
		BELOW_CONTROL,
		/**
		 * At least one cell reached its outcome cap undecided, and none is clearly below the minimum pass rate.
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
		 * Return to development automatically. Each formal failure makes its cell a development cell, and prompt
		 * authors receive its cell, test and failed requirements, never its held-out test case, reply or rationale.
		 * A cell below control becomes a development cell, and prompt authors receive its assertion with the
		 * candidate's and the control's pass rates.
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
	 * Decides the minimum-pass-rate part of one cell from its adopted formal counts. The result never is
	 * {@link CellDecision#BELOW_CONTROL}.
	 *
	 * @param boundaries the attempt's boundaries
	 * @param counts the cell's adopted formal counts
	 * @return the decision
	 * @throws NullPointerException if {@code boundaries} or {@code counts} are null
	 * @throws IllegalArgumentException if {@code counts} exceed the boundaries' outcome cap
	 */
	public static CellDecision minimumDecision(DecisionBoundaries boundaries, CellCounts counts)
	{
		requireThat(boundaries, "boundaries").isNotNull();
		requireThat(counts, "counts").isNotNull();
		if (boundaries.certifies(counts.passes(), counts.failures()))
			return CellDecision.CERTIFIED;
		if (boundaries.isClearlyBelow(counts.passes(), counts.failures()))
			return CellDecision.CLEARLY_BELOW;
		if (counts.outcomes() >= boundaries.cap())
			return CellDecision.CAP_REACHED;
		return CellDecision.UNDECIDED;
	}

	/**
	 * Decides one cell of a certification attempt from its adopted formal counts and control comparisons. Once the
	 * cell's minimum-pass-rate part certified, later formal failures do not undo it. The cell then certifies once
	 * every assertion's control comparison passed, is below control once any assertion is, and reaches its cap once
	 * its formal outcomes reach the cap and each has its control trial.
	 *
	 * @param attempt the attempt
	 * @param cell the identifier of the cell
	 * @return the decision
	 * @throws NullPointerException if {@code attempt} or {@code cell} are null
	 * @throws IllegalArgumentException if the attempt's procedure has no cell identified by {@code cell}
	 */
	public static CellDecision cellDecision(CertificationAttempt attempt, String cell)
	{
		requireThat(attempt, "attempt").isNotNull();
		requireThat(cell, "cell").isNotNull();
		DecisionBoundaries boundaries = attempt.procedure().boundaries();
		CellCounts counts = attempt.count(cell);
		ControlPairing pairing = attempt.pairing(cell);
		if (!pairing.isComparing())
		{
			CellDecision minimum = minimumDecision(boundaries, counts);
			if (minimum == CellDecision.CERTIFIED)
			{
				// The control comparisons start when the adopted outcome that certified the minimum-pass-rate part
				// is adopted
				return CellDecision.UNDECIDED;
			}
			return minimum;
		}
		SignificanceLevel level = boundaries.level();
		boolean allPassed = true;
		for (PairCounts comparison : pairing.comparisons().values())
		{
			PairTally tally = comparison.tally();
			if (ControlComparison.isBelowControl(tally, level))
				return CellDecision.BELOW_CONTROL;
			if (!ControlComparison.passes(tally, level))
				allPassed = false;
		}
		if (allPassed)
			return CellDecision.CERTIFIED;
		if (counts.outcomes() >= boundaries.cap() && pairing.unpaired().isEmpty())
			return CellDecision.CAP_REACHED;
		return CellDecision.UNDECIDED;
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
		boolean capReached = false;
		boolean allCertified = true;
		boolean provenWorse = false;
		for (String cell : attempt.procedure().cells())
		{
			CellDecision decision = cellDecision(attempt, cell);
			switch (decision)
			{
				case CLEARLY_BELOW ->
				{
					return AttemptDecision.BELOW_MINIMUM;
				}
				case BELOW_CONTROL ->
				{
					return AttemptDecision.BELOW_CONTROL;
				}
				case CAP_REACHED ->
				{
					capReached = true;
					allCertified = false;
				}
				case UNDECIDED -> allCertified = false;
				case CERTIFIED ->
				{
					if (isProvenWorse(attempt, cell))
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
			case BELOW_MINIMUM, BELOW_CONTROL, INCONCLUSIVE ->
			{
				if (qualifiesForKeep)
					yield Action.OFFER_KEEP_IMPROVEMENT;
				yield Action.RETURN_TO_DEVELOPMENT;
			}
		};
	}

	/**
	 * Indicates whether a cell of an attempt is proven better than the incumbent at the attempt's improvement
	 * level.
	 *
	 * @param attempt the attempt
	 * @param cell the identifier of the cell whose formal counts are compared
	 * @return {@code true} if the measurement conditions are comparable and the cell is proven better
	 * @throws NullPointerException if {@code attempt} or {@code cell} are null
	 * @throws IllegalArgumentException if the attempt's procedure has no cell identified by {@code cell}
	 */
	public static boolean isProvenBetter(CertificationAttempt attempt, String cell)
	{
		requireThat(attempt, "attempt").isNotNull();
		requireThat(cell, "cell").isNotNull();
		CellCounts counts = attempt.count(cell);
		ComparisonBoundaries comparison = comparison(attempt, cell);
		return Comparability.of(attempt.identity(), attempt.procedure().baselineIdentity()).isComparable() &&
			comparison.isProvenBetter(counts.passes(), counts.failures());
	}

	/**
	 * Indicates whether a cell of an attempt is proven worse than the incumbent at the attempt's improvement
	 * level.
	 *
	 * @param attempt the attempt
	 * @param cell the identifier of the cell whose formal counts are compared
	 * @return {@code true} if the measurement conditions are comparable and the cell is proven worse
	 * @throws NullPointerException if {@code attempt} or {@code cell} are null
	 * @throws IllegalArgumentException if the attempt's procedure has no cell identified by {@code cell}
	 */
	public static boolean isProvenWorse(CertificationAttempt attempt, String cell)
	{
		requireThat(attempt, "attempt").isNotNull();
		requireThat(cell, "cell").isNotNull();
		CellCounts counts = attempt.count(cell);
		ComparisonBoundaries comparison = comparison(attempt, cell);
		return Comparability.of(attempt.identity(), attempt.procedure().baselineIdentity()).isComparable() &&
			comparison.isProvenWorse(counts.passes(), counts.failures());
	}

	/**
	 * Returns a cell's comparison boundaries.
	 *
	 * @param attempt the attempt
	 * @param cell the cell identifier selecting the comparison boundaries
	 * @return the boundaries
	 * @throws IllegalArgumentException if the attempt's procedure has no cell identified by {@code cell}
	 */
	private static ComparisonBoundaries comparison(CertificationAttempt attempt, String cell)
	{
		CertificationProcedure procedure = attempt.procedure();
		return procedure.boundaries().comparisons().get(procedure.position(cell));
	}
}
