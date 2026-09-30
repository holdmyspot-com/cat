package io.github.cowwoc.cat.common.optimizer.statistics;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.math.BigDecimal;
import org.apache.commons.statistics.interval.Interval;
import org.testng.annotations.Test;

/**
 * Checks the exploratory interval used to prioritize representative development evidence.
 */
public final class DevelopmentWilsonIntervalTest
{
	/**
	 * Creates the test instance.
	 */
	public DevelopmentWilsonIntervalTest()
	{
	}

	/**
	 * Completed representative counts produce the two-sided 95% Wilson bounds, including extreme rates.
	 */
	@Test
	public void computesTwoSidedWilsonBounds()
	{
		Interval mixed = DevelopmentWilsonInterval.calculate(new CategoryCounts(5, 10));
		requireThat(Math.abs(mixed.getLowerBound() - 0.236_593_090_512_563_94), "mixedLowerError").
			isLessThan(1e-12);
		requireThat(Math.abs(mixed.getUpperBound() - 0.763_406_909_487_436_1), "mixedUpperError").
			isLessThan(1e-12);

		Interval allPass = DevelopmentWilsonInterval.calculate(new CategoryCounts(50, 50));
		requireThat(Math.abs(allPass.getLowerBound() - 0.928_652_400_866_641_2), "allPassLowerError").
			isLessThan(1e-12);
		requireThat(allPass.getUpperBound(), "allPassUpper").isEqualTo(1.0);

		Interval allFail = DevelopmentWilsonInterval.calculate(new CategoryCounts(0, 50));
		requireThat(allFail.getLowerBound(), "allFailLower").isEqualTo(0.0);
		requireThat(Math.abs(allFail.getUpperBound() - 0.071_347_599_133_358_74), "allFailUpperError").
			isLessThan(1e-12);
	}

	/**
	 * A revised confidence level changes the weak-category interval without changing the fixed allocation interval.
	 */
	@Test
	public void computesRevisedWeakEvidenceInterval()
	{
		CategoryCounts counts = new CategoryCounts(5, 10);
		Interval allocation = DevelopmentWilsonInterval.calculate(counts);
		Interval weakness = DevelopmentWilsonInterval.calculate(counts, new BigDecimal("0.90"));
		requireThat(weakness.getLowerBound(), "weaknessLower").isGreaterThan(allocation.getLowerBound());
		requireThat(weakness.getUpperBound(), "weaknessUpper").isLessThan(allocation.getUpperBound());
	}

	/**
	 * The caller cannot turn an invalid confidence level into a development decision.
	 */
	@Test(expectedExceptions = IllegalArgumentException.class)
	public void rejectsInvalidConfidenceLevel()
	{
		DevelopmentWilsonInterval.calculate(new CategoryCounts(5, 10), BigDecimal.ONE);
	}

	/**
	 * Empty evidence cannot be treated as an interval-based adaptive priority.
	 */
	@Test(expectedExceptions = IllegalArgumentException.class,
		expectedExceptionsMessageRegExp = "Wilson interval requires a completed outcome")
	public void rejectsEmptyRepresentativeEvidence()
	{
		DevelopmentWilsonInterval.calculate(new CategoryCounts(0, 0));
	}

	/**
	 * A missing counts value fails at the caller boundary.
	 */
	@Test(expectedExceptions = NullPointerException.class)
	public void rejectsNullCounts()
	{
		DevelopmentWilsonInterval.calculate(null);
	}
}
