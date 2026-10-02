package io.github.cowwoc.cat.common.optimizer.statistics;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import org.testng.annotations.Test;

/**
 * Tests {@link ExploratoryBounds}.
 * <p>
 * The tests check these {@code design.md} numbers, where the risk ceiling is {@code floor(1 / (2u))} for the
 * Clopper&ndash;Pearson upper bound {@code u} on the failure rate. The reference bounds come from an independent
 * script.
 * <table>
 *   <caption>Design numbers checked by this class</caption>
 *   <tr><th>Source</th><th>Quantity</th><th>Design value</th><th>Checked value</th></tr>
 *   <tr><td>&sect;3.3</td><td>risk ceiling at 12, 24 and 48 outcomes without failures</td><td>2, 4, 8</td>
 *     <td>2, 4, 8 ({@code u} = 0.220922, 0.117346, 0.060503)</td></tr>
 *   <tr><td>&sect;3.3</td><td>risk ceiling with 1 failure in 100</td><td>10</td><td>10 ({@code u} = 0.046560)</td>
 *     </tr>
 *   <tr><td>&sect;13 D8</td><td>adopted outcomes without failures before the risk ceiling first permits 8</td>
 *     <td>48</td><td>47 ({@code u} = 0.061750 gives 8; at 46, {@code u} = 0.063049 gives 7). The ceiling first
 *     permits 2 at 11 and 4 at 23; &sect;3.3's "2 at 12, 4 at 24, 8 at 48" holds, but D8's "takes 48" is one
 *     too many.</td></tr>
 * </table>
 */
public final class ExploratoryBoundsTest
{
	/**
	 * Creates a test instance.
	 */
	public ExploratoryBoundsTest()
	{
	}

	/**
	 * Verifies 95% Wilson intervals against independently computed values.
	 */
	@Test
	public void wilsonMatchesReference()
	{
		// Requirement: R-S8
		requireInterval(ExploratoryBounds.wilson(99, 100), 0.945_513_803_821, 0.998_232_567_936, "99of100");
		requireInterval(ExploratoryBounds.wilson(50, 50), 0.928_652_400_867, 1, "50of50");
		requireInterval(ExploratoryBounds.wilson(0, 10), 0, 0.277_532_799_863, "0of10");
		requireInterval(ExploratoryBounds.wilson(45, 50), 0.786_397_685_625, 0.956_524_235_068, "45of50");
	}

	/**
	 * Verifies that the Wilson interval without trials is {@code [0, 1]}.
	 */
	@Test
	public void wilsonWithoutTrialsIsTrivial()
	{
		// Requirement: R-S8
		Interval interval = ExploratoryBounds.wilson(0, 0);
		requireThat(interval.lower(), "lower").isEqualTo(0.0);
		requireThat(interval.upper(), "upper").isEqualTo(1.0);
	}

	/**
	 * Requires an interval's endpoints within {@code 1e-9}, clamped to {@code [0, 1]}.
	 *
	 * @param interval the interval
	 * @param lower    the expected lower endpoint
	 * @param upper    the expected upper endpoint
	 * @param name     the name of the interval
	 */
	private static void requireInterval(Interval interval, double lower, double upper, String name)
	{
		requireThat(interval.lower(), name + "Lower").isBetween(Math.max(0, lower - 1.0e-9), true,
			lower + 1.0e-9, true);
		requireThat(interval.upper(), name + "Upper").isBetween(upper - 1.0e-9, true,
			Math.min(1, upper + 1.0e-9), true);
	}

	/**
	 * Verifies one-sided 95% Clopper&ndash;Pearson upper bounds and the risk ceilings of {@code design.md} &sect;3.3.
	 */
	@Test
	public void clopperPearsonMatchesReference()
	{
		// Requirement: R-S8
		requireUpperBound(0, 11, 0.238_404_190_381, 2);
		requireUpperBound(0, 12, 0.220_922_191_946, 2);
		requireUpperBound(0, 23, 0.122_123_388_907, 4);
		requireUpperBound(0, 24, 0.117_346_156_155, 4);
		requireUpperBound(0, 46, 0.063_049_301_674, 7);
		requireUpperBound(0, 47, 0.061_750_134_711, 8);
		requireUpperBound(0, 48, 0.060_503_409_349, 8);
		requireUpperBound(1, 100, 0.046_559_811_454, 10);
		requireUpperBound(5, 50, 0.198_833_002_516, 2);
	}

	/**
	 * Requires a Clopper&ndash;Pearson upper bound and the risk ceiling it implies.
	 *
	 * @param events  the number of failures
	 * @param trials  the number of outcomes
	 * @param bound   the expected upper bound
	 * @param ceiling the expected {@code floor(1 / (2u))}
	 */
	private static void requireUpperBound(int events, int trials, double bound, int ceiling)
	{
		String name = events + "of" + trials;
		double upper = ExploratoryBounds.clopperPearsonUpper(events, trials);
		requireThat(upper, name).isBetween(bound - 1.0e-9, bound + 1.0e-9);
		requireThat((int) Math.floor(1 / (2 * upper)), name + "Ceiling").isEqualTo(ceiling);
	}

	/**
	 * Verifies that the upper bound is 1 without outcomes or when every outcome is an event.
	 */
	@Test
	public void clopperPearsonWithoutEvidenceIsOne()
	{
		// Requirement: R-S8
		requireThat(ExploratoryBounds.clopperPearsonUpper(0, 0), "noTrials").isEqualTo(1.0);
		requireThat(ExploratoryBounds.clopperPearsonUpper(5, 5), "allEvents").isEqualTo(1.0);
	}

	/**
	 * Verifies that more events than trials are rejected.
	 */
	@Test(expectedExceptions = IllegalArgumentException.class)
	public void rejectsEventsAboveTrials()
	{
		ExploratoryBounds.clopperPearsonUpper(6, 5);
	}

	/**
	 * Verifies that an interval whose lower endpoint exceeds its upper endpoint is rejected.
	 */
	@Test(expectedExceptions = IllegalArgumentException.class)
	public void rejectsInvertedInterval()
	{
		new Interval(0.6, 0.5);
	}
}
