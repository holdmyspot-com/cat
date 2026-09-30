package io.github.cowwoc.cat.common.optimizer.statistics;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.math.BigInteger;
import org.testng.annotations.Test;

/**
 * Tests exact one-sided binomial lower bounds.
 */
public final class BinomialLowerBoundTest
{
	/**
	 * Creates a test instance.
	 */
	public BinomialLowerBoundTest()
	{
	}

	/**
	 * Verifies the boundary cases of the Clopper-Pearson lower bound.
	 */
	@Test
	public void computesClopperPearsonBoundaryCases()
	{
		requireThat(BinomialLowerBound.oneSided(0, 3, probability(1, 20)), "zeroSuccessBound").isEqualTo(0.0);
		requireThat(BinomialLowerBound.oneSided(1, 1, probability(19, 20)), "singleSuccessBound").
			isLessThanOrEqualTo(0.95).isGreaterThan(0.949_999_999_999_999_8);
		requireThat(BinomialLowerBound.oneSided(72, 72, probability(1, 40)), "allPassBound").isGreaterThan(0.95);
		requireThat(BinomialLowerBound.oneSided(71, 71, probability(1, 40)), "shortAllPassBound").
			isLessThanOrEqualTo(0.95);
	}

	/**
	 * Verifies independent interior Clopper-Pearson reference values.
	 */
	@Test
	public void computesInteriorClopperPearsonBounds()
	{
		double halfSuccessBound = BinomialLowerBound.oneSided(36, 72, probability(1, 40));
		requireThat(Math.abs(halfSuccessBound - 0.379_757_116_1), "halfSuccessBoundError").isLessThan(1e-9);

		double oneOfTwoBound = BinomialLowerBound.oneSided(1, 2, probability(1, 40));
		requireThat(Math.abs(oneOfTwoBound - (1.0 - Math.sqrt(0.975))), "oneOfTwoBoundError").isLessThan(1e-12);
	}

	/**
	 * Verifies an exact interior root is reported no higher than the true bound.
	 */
	@Test
	public void roundsExactInteriorRootDownward()
	{
		double bound = BinomialLowerBound.oneSided(1, 3, probability(7_999, 8_000));
		requireThat(bound, "bound").isEqualTo(0.95);
		requireThat(probability(19, 20).compareDouble(bound), "boundComparison").isLessThan(0);
		requireThat(probability(19, 20).compareDouble(Math.nextUp(bound)), "nextBoundComparison").
			isGreaterThan(0);
	}

	/**
	 * Verifies an exact interior equality does not strictly exceed its threshold.
	 */
	@Test
	public void rejectsExactInteriorThresholdEquality()
	{
		RationalProbability alpha = probability(7_999, 8_000);
		requireThat(BinomialLowerBound.strictlyExceeds(1, 3, alpha, probability(19, 20)), "atThreshold").
			isFalse();
		requireThat(BinomialLowerBound.strictlyExceeds(1, 3, alpha, probability(949, 1_000)),
			"belowThreshold").isTrue();
	}

	/**
	 * Verifies an allowance below the smallest positive double remains valid exact input.
	 */
	@Test
	public void handlesSubnormalAllowance()
	{
		RationalProbability alpha = new RationalProbability(BigInteger.ONE, BigInteger.ONE.shiftLeft(1_075));
		requireThat(BinomialLowerBound.oneSided(1, 1, alpha), "bound").isEqualTo(0.0);
	}

	/**
	 * Verifies lower bounds remain probabilities and grow as successes increase.
	 */
	@Test
	public void keepsBoundsInRangeAndMonotonic()
	{
		double previous = 0.0;
		for (int successes = 0; successes <= 72; ++successes)
		{
			double bound = BinomialLowerBound.oneSided(successes, 72, probability(1, 40));
			requireThat(bound, "bound").isGreaterThanOrEqualTo(0.0).isLessThanOrEqualTo(1.0);
			requireThat(bound, "bound").isGreaterThanOrEqualTo(previous);
			previous = bound;
		}
	}

	/**
	 * Verifies invalid binomial observations are rejected.
	 */
	@Test(expectedExceptions = IllegalArgumentException.class)
	public void rejectsSuccessesAboveTrials()
	{
		BinomialLowerBound.oneSided(2, 1, probability(1, 20));
	}

	/**
	 * Verifies invalid alpha values are rejected.
	 */
	@Test(expectedExceptions = IllegalArgumentException.class)
	public void rejectsInvalidAlpha()
	{
		BinomialLowerBound.oneSided(1, 1, probability(1, 1));
	}

	/** Verifies exact alpha conversion never exceeds the assigned allowance. */
	@Test
	public void convertsAlphaConservatively()
	{
		RationalProbability alpha = probability(1, 40);
		requireThat(alpha.compareDouble(alpha.toDoubleFloor()), "floorComparison").isLessThanOrEqualTo(0);
		requireThat(alpha.compareDouble(alpha.toDoubleCeiling()), "ceilingComparison").isGreaterThanOrEqualTo(0);
	}

	/**
	 * Creates an exact probability fixture.
	 *
	 * @param numerator numerator
	 * @param denominator denominator
	 * @return exact probability
	 */
	private static RationalProbability probability(long numerator, long denominator)
	{
		return new RationalProbability(BigInteger.valueOf(numerator), BigInteger.valueOf(denominator));
	}
}
