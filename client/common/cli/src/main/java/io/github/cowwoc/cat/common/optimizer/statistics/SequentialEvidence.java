package io.github.cowwoc.cat.common.optimizer.statistics;

import java.math.BigInteger;

/**
 * Exact directional martingale evidence at one frozen Bernoulli threshold.
 */
final class SequentialEvidence
{
	private final RationalProbability alpha;
	private final RationalProbability threshold;
	private final RationalProbability rate;
	private final Ratio lowerSuccess;
	private final Ratio lowerFailure;
	private final Ratio upperSuccess;
	private final Ratio upperFailure;
	private final AccumulatedEvidence lowerEvidence;
	private final AccumulatedEvidence upperEvidence;

	/**
	 * Fixes the factors before any formal outcome is observed.
	 *
	 * @param alpha the one-sided allowance
	 * @param threshold the minimum category pass rate
	 * @param rate the predeclared planning rate
	 */
	SequentialEvidence(RationalProbability alpha, RationalProbability threshold, RationalProbability rate)
	{
		this.alpha = alpha;
		this.threshold = threshold;
		this.rate = rate;
		BigInteger tN = threshold.numerator();
		BigInteger tD = threshold.denominator();
		BigInteger rN = rate.numerator();
		BigInteger rD = rate.denominator();
		lowerSuccess = new Ratio(rN.multiply(tD), rD.multiply(tN));
		lowerFailure = new Ratio(rD.subtract(rN).multiply(tD), rD.multiply(tD.subtract(tN)));
		upperSuccess = new Ratio(tN.multiply(rD).multiply(BigInteger.TWO).subtract(rN.multiply(tD)),
			tN.multiply(rD));
		upperFailure = new Ratio(rD.add(rN).multiply(tD).
			subtract(tN.multiply(rD).multiply(BigInteger.TWO)), rD.multiply(tD.subtract(tN)));
		lowerEvidence = new AccumulatedEvidence(alpha, lowerSuccess, lowerFailure);
		upperEvidence = new AccumulatedEvidence(alpha, upperSuccess, upperFailure);
	}

	/**
	 * Indicates whether the exact lower-bound evidence strictly crosses reciprocal alpha.
	 *
	 * @param successes compliant outcomes
	 * @param failures noncompliant outcomes
	 * @return whether the lower confidence bound exceeds the minimum
	 */
	boolean lowerCrosses(int successes, int failures)
	{
		return lowerEvidence.crosses(successes, failures);
	}

	/**
	 * Indicates whether the exact upper-bound evidence strictly crosses reciprocal alpha.
	 *
	 * @param successes compliant outcomes
	 * @param failures noncompliant outcomes
	 * @return whether the upper confidence bound is below the minimum
	 */
	boolean upperCrosses(int successes, int failures)
	{
		return upperEvidence.crosses(successes, failures);
	}

	/**
	 * Indicates whether lower-process evidence crosses at a proposed mean.
	 *
	 * @param mean the proposed Bernoulli mean strictly between zero and one
	 * @param successes compliant outcomes
	 * @param failures noncompliant outcomes
	 * @return whether the exact lower evidence strictly crosses reciprocal alpha
	 */
	boolean lowerCrossesAt(RationalProbability mean, int successes, int failures)
	{
		BigInteger tN = threshold.numerator();
		BigInteger tD = threshold.denominator();
		BigInteger rN = rate.numerator();
		BigInteger rD = rate.denominator();
		BigInteger mN = mean.numerator();
		BigInteger mD = mean.denominator();
		BigInteger betNumerator = rN.multiply(tD).subtract(tN.multiply(rD));
		BigInteger numerator = lowerFailure.numerator().multiply(mN).add(betNumerator.multiply(mD));
		Ratio successFactor = new Ratio(numerator, lowerFailure.denominator().multiply(mN));
		return crosses(successFactor, lowerFailure, successes, failures);
	}

	/**
	 * Indicates whether upper-process evidence crosses at a proposed mean.
	 *
	 * @param mean the proposed Bernoulli mean strictly between zero and one
	 * @param successes compliant outcomes
	 * @param failures noncompliant outcomes
	 * @return whether the exact upper evidence strictly crosses reciprocal alpha
	 */
	boolean upperCrossesAt(RationalProbability mean, int successes, int failures)
	{
		BigInteger tN = threshold.numerator();
		BigInteger tD = threshold.denominator();
		BigInteger rN = rate.numerator();
		BigInteger rD = rate.denominator();
		BigInteger mN = mean.numerator();
		BigInteger mD = mean.denominator();
		BigInteger betNumerator = rN.multiply(tD).subtract(tN.multiply(rD));
		BigInteger denominator = upperSuccess.denominator().multiply(mD.subtract(mN));
		BigInteger numerator = denominator.add(betNumerator.multiply(mN));
		Ratio failureFactor = new Ratio(numerator, denominator);
		return crosses(upperSuccess, failureFactor, successes, failures);
	}

	/**
	 * Indicates whether exact accumulated evidence crosses a directional boundary.
	 *
	 * @param successFactor evidence factor for each success
	 * @param failureFactor evidence factor for each failure
	 * @param successes compliant outcomes
	 * @param failures noncompliant outcomes
	 * @return whether the strict boundary is crossed
	 */
	private boolean crosses(Ratio successFactor, Ratio failureFactor, int successes, int failures)
	{
		BigInteger left = alpha.numerator().multiply(successFactor.numerator().pow(successes)).
			multiply(failureFactor.numerator().pow(failures));
		BigInteger right = alpha.denominator().multiply(successFactor.denominator().pow(successes)).
			multiply(failureFactor.denominator().pow(failures));
		return left.compareTo(right) > 0;
	}

	/**
	 * Reuses exact directional evidence along increasing counts and reconstructs earlier looks.
	 */
	private static final class AccumulatedEvidence
	{
		private final RationalProbability alpha;
		private final Ratio successFactor;
		private final Ratio failureFactor;
		private BigInteger left;
		private BigInteger right;
		private int previousSuccesses;
		private int previousFailures;

		/**
		 * Fixes the directional factors and strict crossing allowance.
		 *
		 * @param alpha the exact one-sided allowance
		 * @param successFactor the evidence multiplier for a compliant outcome
		 * @param failureFactor the evidence multiplier for a noncompliant outcome
		 */
		private AccumulatedEvidence(RationalProbability alpha, Ratio successFactor, Ratio failureFactor)
		{
			this.alpha = alpha;
			this.successFactor = reduceFactor(successFactor);
			this.failureFactor = reduceFactor(failureFactor);
			left = alpha.numerator();
			right = alpha.denominator();
		}

		/**
		 * Evaluates the same exact crossing regardless of the order in which counts are supplied.
		 *
		 * @param successes the compliant outcome count
		 * @param failures the noncompliant outcome count
		 * @return whether the strict directional boundary is crossed
		 */
		private boolean crosses(int successes, int failures)
		{
			if (successes < previousSuccesses || failures < previousFailures)
			{
				left = alpha.numerator();
				right = alpha.denominator();
				previousSuccesses = 0;
				previousFailures = 0;
			}
			int addedSuccesses = successes - previousSuccesses;
			int addedFailures = failures - previousFailures;
			left = left.multiply(successFactor.numerator().pow(addedSuccesses)).
				multiply(failureFactor.numerator().pow(addedFailures));
			right = right.multiply(successFactor.denominator().pow(addedSuccesses)).
				multiply(failureFactor.denominator().pow(addedFailures));
			previousSuccesses = successes;
			previousFailures = failures;
			return left.compareTo(right) > 0;
		}

		/**
		 * Removes common factors from the accumulator's private copy.
		 *
		 * @param factor the exact directional multiplier
		 * @return the same multiplier with coprime numerator and denominator
		 */
		private static Ratio reduceFactor(Ratio factor)
		{
			BigInteger divisor = factor.numerator().gcd(factor.denominator());
			return new Ratio(factor.numerator().divide(divisor), factor.denominator().divide(divisor));
		}
	}

	/**
	 * An exact nonnegative evidence factor, which may exceed one.
	 *
	 * @param numerator the factor numerator
	 * @param denominator the factor denominator
	 */
	private record Ratio(BigInteger numerator, BigInteger denominator)
	{
	}
}
