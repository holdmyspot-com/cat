package com.holdmyspot.cat.common.optimizer.campaign;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.optimizer.statistics.ComparisonBoundaries;
import com.holdmyspot.cat.common.optimizer.statistics.PairedComparison;
import com.holdmyspot.cat.common.optimizer.statistics.DecisionBoundaries;
import com.holdmyspot.cat.common.optimizer.statistics.SignificanceLevel;

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
		 * No continuation within the cell's outcome cap can certify it: its minimum-pass-rate part is undecided and no
		 * state with its failures certifies within the cap, or an undecided control or incumbent comparison cannot pass
		 * within the cap's remaining pairs even if only the candidate trial of every further pair passes.
		 */
		OUT_OF_REACH,
		/**
		 * The cell's minimum-pass-rate part certified, but the candidate passes one of its assertions less often than
		 * the control.
		 */
		BELOW_CONTROL,
		/**
		 * The cell's minimum-pass-rate part certified, but the candidate passes one of its assertions less often than
		 * the incumbent, and none less often than the control.
		 */
		BELOW_INCUMBENT
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
		 * At least one cell passes one of its assertions less often than the incumbent, no cell passes one less often
		 * than the control, and none is clearly below the minimum pass rate.
		 */
		BELOW_INCUMBENT,
		/**
		 * At least one cell reached its outcome cap undecided or is out of reach, and none is clearly below the minimum
		 * pass rate, below control or below incumbent.
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
		 * A cell below control or below incumbent becomes a development cell, and prompt authors receive its assertion
		 * with the candidate's pass rate and the control's or the incumbent's.
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
	 * Decides the minimum-pass-rate part of one cell from its adopted formal counts. A cell that neither certifies nor
	 * is clearly below reaches its cap once its outcomes reach the cap, and is out of reach before that once no state
	 * with its failures certifies within the cap. The result never is {@link CellDecision#BELOW_CONTROL} or
	 * {@link CellDecision#BELOW_INCUMBENT}.
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
		if (!boundaries.canCertifyWith(counts.failures()))
			return CellDecision.OUT_OF_REACH;
		return CellDecision.UNDECIDED;
	}

	/**
	 * Decides one cell of a certification attempt from its adopted formal counts, control comparisons and incumbent
	 * comparisons. Once the cell's minimum-pass-rate part certified, later formal failures do not undo it. The cell
	 * then is below control once any assertion's control comparison is, and otherwise below incumbent once any
	 * assertion's incumbent comparison is. It certifies once every assertion's control and incumbent comparisons
	 * passed, and reaches its cap once its formal outcomes reach the cap and each has its trial of every kind whose
	 * comparisons have not all passed. Before that, it is out of reach once some undecided comparison cannot pass
	 * within the cap's remaining pairs (see {@link #pairsToPass}). A cell has no incumbent comparisons when the
	 * candidate is the incumbent. When the attempt's minimum pass rate implies the comparisons, the cell's decision is
	 * its minimum-pass-rate part's.
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
		if (PairedComparison.isImpliedBy(boundaries.minimumPassRate()))
			return minimumDecision(boundaries, counts);
		Pairing control = attempt.pairing(Reference.CONTROL, cell);
		if (!control.isComparing())
		{
			CellDecision minimum = minimumDecision(boundaries, counts);
			if (minimum == CellDecision.CERTIFIED)
			{
				// The paired comparisons start when the adopted outcome that certified the minimum-pass-rate part
				// is adopted
				return CellDecision.UNDECIDED;
			}
			return minimum;
		}
		SignificanceLevel level = boundaries.level();
		Pairing incumbent = attempt.pairing(Reference.INCUMBENT, cell);
		if (isAnyBelow(control, level))
			return CellDecision.BELOW_CONTROL;
		if (isAnyBelow(incumbent, level))
			return CellDecision.BELOW_INCUMBENT;
		boolean controlPassed = isEveryPassed(control, level);
		boolean incumbentPassed = isEveryPassed(incumbent, level);
		if (controlPassed && incumbentPassed)
			return CellDecision.CERTIFIED;
		if (counts.outcomes() >= boundaries.cap() && (controlPassed || control.unpaired().isEmpty()) &&
			(incumbentPassed || incumbent.unpaired().isEmpty()))
		{
			return CellDecision.CAP_REACHED;
		}
		if (!canPass(control, boundaries) || !canPass(incumbent, boundaries))
			return CellDecision.OUT_OF_REACH;
		return CellDecision.UNDECIDED;
	}

	/**
	 * Returns the fewest further pairs after which every comparison of a pairing passes, if only the candidate trial
	 * of each further pair passes. No other continuation passes a comparison sooner, so a pairing whose result exceeds
	 * the pairs that the cap still allows cannot pass within the cap.
	 *
	 * @param pairing the cell's pairing with one kind of reference trial
	 * @param boundaries the attempt's boundaries
	 * @return the pairs, 0 if every comparison passed or the pairing compares nothing, or one more than the pairs that
	 *   the cap still allows if some comparison cannot pass within them
	 * @throws NullPointerException if any of the arguments are null
	 */
	public static int pairsToPass(Pairing pairing, DecisionBoundaries boundaries)
	{
		requireThat(pairing, "pairing").isNotNull();
		requireThat(boundaries, "boundaries").isNotNull();
		int remaining = remainingPairs(pairing, boundaries);
		int needed = 0;
		for (PairCounts comparison : pairing.comparisons().values())
			needed = Math.max(needed, pairsToPass(comparison, remaining, boundaries));
		return needed;
	}

	/**
	 * Returns the fewest further pairs after which one comparison passes, if only the candidate trial of each further
	 * pair passes. No other continuation passes the comparison sooner, so a comparison whose result exceeds the pairs
	 * that the cap still allows cannot pass within the cap.
	 *
	 * @param comparison the adopted pairs of one assertion's comparison
	 * @param remaining the further pairs that the cell's cap still allows
	 * @param boundaries the attempt's boundaries
	 * @return the pairs, 0 if the comparison passed, or {@code remaining + 1} if it cannot pass within them
	 * @throws NullPointerException if {@code comparison} or {@code boundaries} are null
	 * @throws IllegalArgumentException if {@code remaining} is negative
	 */
	public static int pairsToPass(PairCounts comparison, int remaining, DecisionBoundaries boundaries)
	{
		requireThat(comparison, "comparison").isNotNull();
		requireThat(boundaries, "boundaries").isNotNull();
		return PairedComparison.fewestCandidateAheadPairsToPass(comparison.tally(), boundaries.level(), remaining);
	}

	/**
	 * Returns the further pairs that a cell's cap still allows a pairing.
	 *
	 * @param pairing the cell's pairing with one kind of reference trial
	 * @param boundaries the attempt's boundaries
	 * @return the cap minus the adopted pairs, or 0 if none remain
	 */
	public static int remainingPairs(Pairing pairing, DecisionBoundaries boundaries)
	{
		requireThat(pairing, "pairing").isNotNull();
		requireThat(boundaries, "boundaries").isNotNull();
		return Math.max(0, boundaries.cap() - pairing.pairs());
	}

	/**
	 * Indicates whether every comparison of a pairing can still pass within the cap's remaining pairs.
	 *
	 * @param pairing the cell's pairing with one kind of reference trial
	 * @param boundaries the attempt's boundaries
	 * @return {@code true} if some continuation within the cap passes every comparison
	 */
	private static boolean canPass(Pairing pairing, DecisionBoundaries boundaries)
	{
		return pairsToPass(pairing, boundaries) <= remainingPairs(pairing, boundaries);
	}

	/**
	 * Indicates whether the candidate is below the reference trials in any of a pairing's comparisons.
	 *
	 * @param pairing the pairing
	 * @param level the attempt's certification level
	 * @return {@code true} if some assertion's comparison is below
	 */
	private static boolean isAnyBelow(Pairing pairing, SignificanceLevel level)
	{
		for (PairCounts comparison : pairing.comparisons().values())
		{
			if (PairedComparison.isBelow(comparison.tally(), level))
				return true;
		}
		return false;
	}

	/**
	 * Indicates whether every comparison of a pairing passed.
	 *
	 * @param pairing the pairing
	 * @param level the attempt's certification level
	 * @return {@code true} if every assertion's comparison passed, including when the pairing compares nothing
	 */
	private static boolean isEveryPassed(Pairing pairing, SignificanceLevel level)
	{
		for (PairCounts comparison : pairing.comparisons().values())
		{
			if (!PairedComparison.passes(comparison.tally(), level))
				return false;
		}
		return true;
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
		boolean inconclusive = false;
		boolean belowIncumbent = false;
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
				case BELOW_INCUMBENT ->
				{
					// Below control in a later cell takes precedence
					belowIncumbent = true;
					allCertified = false;
				}
				case CAP_REACHED, OUT_OF_REACH ->
				{
					inconclusive = true;
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
		if (belowIncumbent)
			return AttemptDecision.BELOW_INCUMBENT;
		if (inconclusive)
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
			case BELOW_MINIMUM, BELOW_CONTROL, BELOW_INCUMBENT, INCONCLUSIVE ->
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
