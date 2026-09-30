package io.github.cowwoc.cat.common.optimizer.statistics;

import java.math.BigInteger;
import java.util.HashMap;
import java.util.Map;

/**
 * An anytime-valid Bernoulli test with a cap calibrated before formal observations.
 * <p>
 * Callers preserve the plan, counts, and category stop receipts across restarts.
 */
public final class SequentialPlan
{
	private static final RationalProbability ONE = new RationalProbability(BigInteger.ONE, BigInteger.ONE);
	private final RationalProbability alpha;
	private final RationalProbability threshold;
	private final RationalProbability planningRate;
	private final RationalProbability minimumPower;
	private final int cap;
	private final RationalProbability achievedPower;

	/**
	 * Calibrates the next campaign attempt with its exact assigned allowance.
	 *
	 * @param attemptIndex the one-based formal-attempt number across campaign candidates and lineages
	 * @param threshold the exact minimum category pass probability
	 * @param planningRate the pass probability used only for planning
	 * @param minimumPower the required probability of certification by the cap
	 * @return the frozen procedure, cap, and achieved planning power
	 * @throws IllegalArgumentException if the index or rates are invalid
	 * @throws IllegalStateException if the target power is unattainable or the cap exceeds the integer trial-count range
	 */
	public static SequentialPlan forAttempt(int attemptIndex, RationalProbability threshold,
		RationalProbability planningRate, RationalProbability minimumPower)
	{
		return forPower(LineageAllowance.forCandidate(attemptIndex), threshold, planningRate, minimumPower);
	}

	/**
	 * Calibrates the smallest cap meeting the required planning power.
	 *
	 * @param alpha the attempt's exact one-sided allowance
	 * @param threshold the exact minimum category pass probability
	 * @param planningRate the pass probability used only for planning
	 * @param minimumPower the minimum probability of certification by the cap
	 * @return the frozen procedure, cap, and achieved planning power
	 * @throws IllegalArgumentException if the inputs cannot define this Bernoulli procedure
	 * @throws IllegalStateException if the target power is unattainable or the cap exceeds the integer trial-count range
	 */
	static SequentialPlan forPower(RationalProbability alpha, RationalProbability threshold,
		RationalProbability planningRate, RationalProbability minimumPower)
	{
		validate(alpha, threshold, planningRate, minimumPower);
		SequentialEvidence evidence = new SequentialEvidence(alpha, threshold, planningRate);
		if (planningRate.equals(ONE))
			return new SequentialPlan(alpha, threshold, planningRate, minimumPower,
				allPassCap(evidence), ONE);

		BigInteger denominator = BigInteger.ONE;
		CalibrationMass mass = new CalibrationMass(alpha, planningRate, minimumPower);
		Map<Integer, BigInteger> live = Map.of(0, BigInteger.ONE);
		int lowerMaxFailures = -1;
		int upperMinFailures = 1;
		for (int trials = 1; ; ++trials)
		{
			// Each boundary moves by at most one failure after one additional Bernoulli outcome.
			if (evidence.lowerCrosses(trials - lowerMaxFailures - 1, lowerMaxFailures + 1))
				++lowerMaxFailures;
			if (!evidence.upperCrosses(trials - upperMinFailures, upperMinFailures))
				++upperMinFailures;

			denominator = denominator.multiply(planningRate.denominator());
			mass.advance(planningRate.denominator());
			Map<Integer, BigInteger> nextLive = new HashMap<>();
			for (Map.Entry<Integer, BigInteger> entry : live.entrySet())
			{
				int failures = entry.getKey();
				mass.propagate(lowerMaxFailures, upperMinFailures, failures,
					entry.getValue(), nextLive);
				mass.propagate(lowerMaxFailures, upperMinFailures, failures + 1,
					entry.getValue(), nextLive);
			}
			BigInteger targetMass = denominator.multiply(minimumPower.numerator());
			if (mass.certified.multiply(minimumPower.denominator()).compareTo(targetMass) >= 0)
			{
				RationalProbability achieved = new RationalProbability(mass.certified, denominator);
				return new SequentialPlan(alpha, threshold, planningRate, minimumPower, trials, achieved);
			}
			if (mass.trackStoppedBelow)
			{
				BigInteger possibleCertified = denominator.subtract(mass.stoppedBelow);
				if (possibleCertified.multiply(minimumPower.denominator()).compareTo(targetMass) < 0)
					throw new IllegalStateException("Planning power is unattainable after below-minimum stops; " +
						"revise the planning rate or power");
			}
			if (trials == Integer.MAX_VALUE)
				throw new IllegalStateException("The calibrated cap exceeds the integer trial-count range");
			live = nextLive;
		}
	}

	/**
	 * Finds the first exact certification crossing when every planned outcome passes.
	 *
	 * @param evidence the fixed sequential evidence factors
	 * @return the smallest certifying trial count
	 * @throws IllegalStateException if the cap exceeds the integer trial-count range
	 */
	private static int allPassCap(SequentialEvidence evidence)
	{
		int lower = 0;
		int upper = 1;
		while (!evidence.lowerCrosses(upper, 0))
		{
			if (upper == Integer.MAX_VALUE)
				throw new IllegalStateException("The calibrated cap exceeds the integer trial-count range");
			lower = upper;
			if (upper > Integer.MAX_VALUE / 2)
				upper = Integer.MAX_VALUE;
			else
				upper *= 2;
		}
		while (upper - lower > 1)
		{
			int middle = lower + (upper - lower) / 2;
			if (evidence.lowerCrosses(middle, 0))
				upper = middle;
			else
				lower = middle;
		}
		return upper;
	}

	/**
	 * Accumulates probability mass at stopping boundaries while unresolved states count paths.
	 */
	private static final class CalibrationMass
	{
		private final BoundaryPathWeight lowerWeight;
		private final BoundaryPathWeight upperWeight;
		private final boolean trackStoppedBelow;
		private BigInteger certified = BigInteger.ZERO;
		private BigInteger stoppedBelow = BigInteger.ZERO;
		private int trials;

		/**
		 * Fixes boundary weights and whether upper-stop mass is needed to detect unattainable power.
		 *
		 * @param alpha the one-sided allowance
		 * @param planningRate the exact probability of a compliant outcome
		 * @param minimumPower the requested certification probability
		 */
		private CalibrationMass(RationalProbability alpha, RationalProbability planningRate,
			RationalProbability minimumPower)
		{
			lowerWeight = new BoundaryPathWeight(planningRate);
			upperWeight = new BoundaryPathWeight(planningRate);
			// Ville's inequality bounds an upper stop at the planning rate by alpha. The unstopped lower
			// process crosses almost surely because that rate exceeds the threshold. Targets strictly below
			// 1-alpha therefore remain attainable without calculating the exact absorbed upper-stop mass.
			RationalProbability eventualPowerFloor = new RationalProbability(
				alpha.denominator().subtract(alpha.numerator()), alpha.denominator());
			trackStoppedBelow = minimumPower.compareTo(eventualPowerFloor) >= 0;
		}

		/**
		 * Carries absorbed mass to the next trial's exact denominator.
		 *
		 * @param factor the planning-rate denominator for one trial
		 */
		private void advance(BigInteger factor)
		{
			certified = certified.multiply(factor);
			if (trackStoppedBelow)
				stoppedBelow = stoppedBelow.multiply(factor);
			++trials;
		}

		/**
		 * Counts unresolved paths or applies their shared probability at a stopping boundary.
		 *
		 * @param lowerMaxFailures the highest failure count crossing the lower boundary
		 * @param upperMinFailures the lowest failure count crossing the upper boundary
		 * @param failures the new failure count
		 * @param paths the number of surviving paths with this trial and failure count
		 * @param nextLive unresolved path counts for the next look
		 */
		private void propagate(int lowerMaxFailures, int upperMinFailures, int failures,
			BigInteger paths, Map<Integer, BigInteger> nextLive)
		{
			if (paths.signum() == 0)
				return;
			if (failures <= lowerMaxFailures)
			{
				certified = certified.add(paths.multiply(lowerWeight.at(trials, failures)));
				return;
			}
			if (failures < upperMinFailures)
			{
				nextLive.merge(failures, paths, BigInteger::add);
				return;
			}
			if (trackStoppedBelow)
				stoppedBelow = stoppedBelow.add(paths.multiply(upperWeight.at(trials, failures)));
		}
	}

	/**
	 * Updates the common probability numerator for paths reaching one moving boundary.
	 */
	private static final class BoundaryPathWeight
	{
		private final BigInteger successFactor;
		private final BigInteger failureFactor;
		private BigInteger numerator = BigInteger.ONE;
		private int successes;
		private int failures;

		/**
		 * Fixes the probability factors shared by every path with the same counts.
		 *
		 * @param planningRate the exact probability of a compliant outcome
		 */
		private BoundaryPathWeight(RationalProbability planningRate)
		{
			successFactor = planningRate.numerator();
			failureFactor = planningRate.denominator().subtract(successFactor);
		}

		/**
		 * Returns the exact probability numerator of one path at the boundary.
		 *
		 * @param trials the new trial count
		 * @param nextFailures the new failure count
		 * @return shared numerator over the planning-rate denominator raised to {@code trials}
		 */
		private BigInteger at(int trials, int nextFailures)
		{
			int nextSuccesses = trials - nextFailures;
			// A boundary moves by at most one failure per trial, so both exponents only increase.
			numerator = numerator.multiply(successFactor.pow(nextSuccesses - successes)).
				multiply(failureFactor.pow(nextFailures - failures));
			successes = nextSuccesses;
			failures = nextFailures;
			return numerator;
		}
	}

	/**
	 * Validates the allowed rates and nonnegative directional betting factors.
	 *
	 * @param alpha the one-sided attempt allowance
	 * @param threshold the claimed minimum rate
	 * @param planningRate the cap-planning rate
	 * @param minimumPower the planning-power target
	 */
	private static void validate(RationalProbability alpha, RationalProbability threshold,
		RationalProbability planningRate, RationalProbability minimumPower)
	{
		if (alpha == null || threshold == null || planningRate == null || minimumPower == null)
			throw new IllegalArgumentException("Sequential procedure probabilities must be present");
		if (alpha.numerator().signum() <= 0 || alpha.compareTo(ONE) >= 0)
			throw new IllegalArgumentException("alpha must be between zero and one");
		if (threshold.numerator().signum() <= 0 || threshold.compareTo(ONE) >= 0)
			throw new IllegalArgumentException("threshold must be between zero and one");
		if (planningRate.compareTo(threshold) <= 0)
			throw new IllegalArgumentException("planningRate must exceed threshold");
		if (minimumPower.numerator().signum() <= 0 || minimumPower.compareTo(ONE) >= 0)
			throw new IllegalArgumentException("minimumPower must be between zero and one");
		BigInteger twiceThreshold = threshold.numerator().multiply(planningRate.denominator()).
			multiply(BigInteger.TWO);
		BigInteger planningNumerator = planningRate.numerator().multiply(threshold.denominator());
		if (planningNumerator.compareTo(twiceThreshold) > 0)
			throw new IllegalArgumentException("planningRate must be at most twice threshold");
	}

	/**
	 * Creates a calibrated immutable plan.
	 *
	 * @param alpha the one-sided allowance
	 * @param threshold the category minimum
	 * @param planningRate the cap-planning rate
	 * @param minimumPower the planning-power target
	 * @param cap the calibrated maximum valid trial count
	 * @param achievedPower the exact power at the cap
	 */
	private SequentialPlan(RationalProbability alpha, RationalProbability threshold,
		RationalProbability planningRate, RationalProbability minimumPower, int cap,
		RationalProbability achievedPower)
	{
		this.alpha = alpha;
		this.threshold = threshold;
		this.planningRate = planningRate;
		this.minimumPower = minimumPower;
		this.cap = cap;
		this.achievedPower = achievedPower;
	}

	/**
	 * Returns the one-sided allowance applied to each category.
	 *
	 * @return the exact allowance
	 */
	public RationalProbability alpha()
	{
		return alpha;
	}

	/**
	 * Returns the minimum pass probability that each category must exceed.
	 *
	 * @return the exact minimum
	 */
	public RationalProbability threshold()
	{
		return threshold;
	}

	/**
	 * Returns the rate used only for cap planning.
	 *
	 * @return the exact planning rate
	 */
	public RationalProbability planningRate()
	{
		return planningRate;
	}

	/**
	 * Returns the required probability of certification at the planning rate.
	 *
	 * @return the exact planning-power target
	 */
	public RationalProbability minimumPower()
	{
		return minimumPower;
	}

	/**
	 * Returns the frozen maximum number of valid logical trials for a category.
	 *
	 * @return the calibrated per-category cap
	 */
	public int cap()
	{
		return cap;
	}

	/**
	 * Returns the exact probability of certification by the cap at the planning rate.
	 *
	 * @return the achieved planning power
	 */
	public RationalProbability achievedPower()
	{
		return achievedPower;
	}

	/**
	 * Classifies retained category counts at the current repeated look.
	 *
	 * @param counts valid logical outcomes from one unchanged category distribution
	 * @return certification, below-minimum, continuation, or cap exhaustion
	 * @throws NullPointerException if {@code counts} is null
	 * @throws IllegalArgumentException if the counts exceed the frozen cap
	 */
	public SequentialStatus decide(CategoryCounts counts)
	{
		if (counts == null)
			throw new NullPointerException("counts");
		if (counts.trials() > cap)
			throw new IllegalArgumentException("Category trials exceed the frozen cap of " + cap);
		SequentialEvidence evidence = new SequentialEvidence(alpha, threshold, planningRate);
		int failures = counts.trials() - counts.successes();
		if (evidence.lowerCrosses(counts.successes(), failures))
			return SequentialStatus.CERTIFIED;
		if (evidence.upperCrosses(counts.successes(), failures))
			return SequentialStatus.BELOW_MINIMUM;
		if (counts.trials() == cap)
			return SequentialStatus.CAP_EXHAUSTED;
		return SequentialStatus.CONTINUE;
	}

	/**
	 * Returns the two directional anytime-valid bounds at one repeated look.
	 *
	 * @param counts valid logical outcomes from the unchanged category distribution
	 * @return the current one-sided lower and upper limits
	 * @throws NullPointerException if {@code counts} is null
	 * @throws IllegalArgumentException if the counts exceed the frozen cap
	 */
	public SequentialBounds bounds(CategoryCounts counts)
	{
		if (counts == null)
			throw new NullPointerException("counts");
		if (counts.trials() > cap)
			throw new IllegalArgumentException("Category trials exceed the frozen cap of " + cap);
		if (counts.trials() == 0)
			return new SequentialBounds(0.0, 1.0);

		int successes = counts.successes();
		int failures = counts.trials() - successes;
		SequentialEvidence evidence = new SequentialEvidence(alpha, threshold, planningRate);
		return new SequentialBounds(lowerBound(evidence, successes, failures),
			upperBound(evidence, successes, failures));
	}

	/**
	 * Inverts the lower test over representable doubles without rounding above its exact root.
	 *
	 * @param evidence the frozen exact martingale factors
	 * @param successes compliant outcomes
	 * @param failures noncompliant outcomes
	 * @return the displayed lower endpoint
	 */
	private static double lowerBound(SequentialEvidence evidence, int successes, int failures)
	{
		if (successes == 0)
			return 0.0;
		long lowerBits = Double.doubleToRawLongBits(0.0);
		long upperBits = Double.doubleToRawLongBits(1.0);
		while (upperBits - lowerBits > 1)
		{
			long midpointBits = lowerBits + (upperBits - lowerBits) / 2;
			RationalProbability midpoint = RationalProbability.fromDoubleExact(
				Double.longBitsToDouble(midpointBits));
			if (evidence.lowerCrossesAt(midpoint, successes, failures))
				lowerBits = midpointBits;
			else
				upperBits = midpointBits;
		}
		return Double.longBitsToDouble(lowerBits);
	}

	/**
	 * Inverts the upper test over representable doubles without rounding below its exact root.
	 *
	 * @param evidence the frozen exact martingale factors
	 * @param successes compliant outcomes
	 * @param failures noncompliant outcomes
	 * @return the displayed upper endpoint
	 */
	private static double upperBound(SequentialEvidence evidence, int successes, int failures)
	{
		if (failures == 0)
			return 1.0;
		long lowerBits = Double.doubleToRawLongBits(0.0);
		long upperBits = Double.doubleToRawLongBits(1.0);
		while (upperBits - lowerBits > 1)
		{
			long midpointBits = lowerBits + (upperBits - lowerBits) / 2;
			RationalProbability midpoint = RationalProbability.fromDoubleExact(
				Double.longBitsToDouble(midpointBits));
			if (evidence.upperCrossesAt(midpoint, successes, failures))
				upperBits = midpointBits;
			else
				lowerBits = midpointBits;
		}
		return Double.longBitsToDouble(upperBits);
	}
}
