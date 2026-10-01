package io.github.cowwoc.cat.common.optimizer.statistics;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import org.testng.annotations.Test;

import java.math.BigInteger;

/**
 * Tests {@link ErrorBudget}.
 * <p>
 * The tests check these {@code design.md} numbers:
 * <table>
 *   <caption>Design numbers checked by this class</caption>
 *   <tr><th>Source</th><th>Quantity</th><th>Design value</th><th>Checked threshold</th></tr>
 *   <tr><td>&sect;2.2</td><td>alpha_1, alpha_2, alpha_3</td><td>2%, 0.667%, 0.333%</td><td>50, 150, 300</td></tr>
 *   <tr><td>&sect;2.4</td><td>alpha_4, alpha_5</td><td>0.200%, 0.133%</td><td>500, 750</td></tr>
 *   <tr><td>&sect;2.4</td><td>alpha_10, alpha_50, alpha_100</td><td>0.0364%, 0.00157%, 0.000396%</td>
 *     <td>2750, 63750, 252500</td></tr>
 *   <tr><td>&sect;2.2</td><td>beta_1 / 3</td><td>0.005 / 2 / 3</td><td>1200</td></tr>
 *   <tr><td>&sect;2.2</td><td>gamma_1 / 1, gamma_1 / 8, gamma_2 / 2</td><td>0.005 / 2, 0.005 / 16,
 *     0.005 / 6 / 2</td><td>400, 3200, 2400</td></tr>
 *   <tr><td>&sect;2.2</td><td>total over any number of attempts and baselines</td><td>at most 0.05</td>
 *     <td>0.05 &middot; N / (N + 1) after N</td></tr>
 * </table>
 */
public final class ErrorBudgetTest
{
	/**
	 * Creates a test instance.
	 */
	public ErrorBudgetTest()
	{
	}

	/**
	 * Verifies that certification attempt {@code i} receives {@code alpha_i = 0.04 / (i(i + 1))}, matching the
	 * {@code design.md} &sect;2.4 table.
	 */
	@Test
	public void certificationLevelsFollowSeries()
	{
		// Requirement: R-S1
		requireThat(ErrorBudget.certificationLevel(1).threshold(), "alpha1").isEqualTo(BigInteger.valueOf(50));
		requireThat(ErrorBudget.certificationLevel(2).threshold(), "alpha2").isEqualTo(BigInteger.valueOf(150));
		requireThat(ErrorBudget.certificationLevel(3).threshold(), "alpha3").isEqualTo(BigInteger.valueOf(300));
		requireThat(ErrorBudget.certificationLevel(4).threshold(), "alpha4").isEqualTo(BigInteger.valueOf(500));
		requireThat(ErrorBudget.certificationLevel(5).threshold(), "alpha5").isEqualTo(BigInteger.valueOf(750));
		requireThat(ErrorBudget.certificationLevel(10).threshold(), "alpha10").isEqualTo(BigInteger.valueOf(2750));
		requireThat(ErrorBudget.certificationLevel(50).threshold(), "alpha50").
			isEqualTo(BigInteger.valueOf(63_750));
		requireThat(ErrorBudget.certificationLevel(100).threshold(), "alpha100").
			isEqualTo(BigInteger.valueOf(252_500));
	}

	/**
	 * Verifies that the displayed probabilities round to the percentages in the {@code design.md} &sect;2.4 table.
	 */
	@Test
	public void certificationProbabilitiesMatchTable()
	{
		// Requirement: R-S1
		requireThat(ErrorBudget.certificationLevel(1).probability(), "alpha1").isEqualTo(0.02);
		requireThat(ErrorBudget.certificationLevel(2).probability(), "alpha2").isBetween(0.006_665, 0.006_675);
		requireThat(ErrorBudget.certificationLevel(3).probability(), "alpha3").isBetween(0.003_325, 0.003_335);
		requireThat(ErrorBudget.certificationLevel(4).probability(), "alpha4").isBetween(0.001_995, 0.002_005);
		requireThat(ErrorBudget.certificationLevel(5).probability(), "alpha5").isBetween(0.001_325, 0.001_335);
		requireThat(ErrorBudget.certificationLevel(10).probability(), "alpha10").
			isBetween(0.000_363_5, 0.000_364_5);
		requireThat(ErrorBudget.certificationLevel(50).probability(), "alpha50").
			isBetween(0.000_015_65, 0.000_015_75);
		requireThat(ErrorBudget.certificationLevel(100).probability(), "alpha100").
			isBetween(0.000_003_955, 0.000_003_965);
	}

	/**
	 * Verifies that each category's improvement decision uses {@code beta_i / K}.
	 */
	@Test
	public void improvementLevelDividesByCategories()
	{
		// Requirement: R-S1
		requireThat(ErrorBudget.improvementLevel(1, 1).threshold(), "beta1K1").isEqualTo(BigInteger.valueOf(400));
		requireThat(ErrorBudget.improvementLevel(1, 3).threshold(), "beta1K3").isEqualTo(BigInteger.valueOf(1200));
		requireThat(ErrorBudget.improvementLevel(2, 3).threshold(), "beta2K3").isEqualTo(BigInteger.valueOf(3600));
	}

	/**
	 * Verifies that each category's incumbent bounds use {@code gamma_b / K}, where {@code b} numbers the baseline
	 * independently of the certification index.
	 */
	@Test
	public void incumbentLevelDividesByCategories()
	{
		// Requirement: R-S1
		requireThat(ErrorBudget.incumbentLevel(1, 1).threshold(), "gamma1K1").isEqualTo(BigInteger.valueOf(400));
		requireThat(ErrorBudget.incumbentLevel(1, 8).threshold(), "gamma1K8").isEqualTo(BigInteger.valueOf(3200));
		requireThat(ErrorBudget.incumbentLevel(2, 2).threshold(), "gamma2K2").isEqualTo(BigInteger.valueOf(2400));
	}

	/**
	 * Verifies that the union bound over the first 1000 attempts and baselines, each with 7 categories, stays below
	 * 0.05: the exact sum is {@code 0.05 * 1000 / 1001}.
	 */
	@Test
	public void jointBudgetStaysBelowFivePercent()
	{
		// Requirement: R-S1
		int indexes = 1000;
		int categories = 7;
		Sum sum = new Sum(BigInteger.ZERO, BigInteger.ONE);
		for (int index = 1; index <= indexes; ++index)
		{
			sum = sum.plus(1, ErrorBudget.certificationLevel(index));
			// The improvement and incumbent levels are spent once per category.
			sum = sum.plus(categories, ErrorBudget.improvementLevel(index, categories));
			sum = sum.plus(categories, ErrorBudget.incumbentLevel(index, categories));
		}
		// 0.05 * 1000 / 1001 = 50 / 1001
		requireThat(sum.numerator(), "numerator").isEqualTo(BigInteger.valueOf(50));
		requireThat(sum.denominator(), "denominator").isEqualTo(BigInteger.valueOf(1001));
	}

	/**
	 * Verifies that indexes larger than {@code Integer.MAX_VALUE / 2} do not overflow.
	 */
	@Test
	public void largeIndexDoesNotOverflow()
	{
		// Requirement: R-S1
		BigInteger index = BigInteger.valueOf(Integer.MAX_VALUE);
		BigInteger expected = BigInteger.valueOf(25).multiply(index).multiply(index.add(BigInteger.ONE));
		requireThat(ErrorBudget.certificationLevel(Integer.MAX_VALUE).threshold(), "threshold").isEqualTo(expected);
	}

	/**
	 * Verifies that certification indexes start at 1.
	 */
	@Test(expectedExceptions = IllegalArgumentException.class)
	public void rejectsCertificationIndexZero()
	{
		ErrorBudget.certificationLevel(0);
	}

	/**
	 * Verifies that a contract has at least one category.
	 */
	@Test(expectedExceptions = IllegalArgumentException.class)
	public void rejectsZeroCategories()
	{
		ErrorBudget.improvementLevel(1, 0);
	}

	/**
	 * Verifies that baseline indexes start at 1.
	 */
	@Test(expectedExceptions = IllegalArgumentException.class)
	public void rejectsBaselineIndexZero()
	{
		ErrorBudget.incumbentLevel(0, 1);
	}

	/**
	 * An exact sum of error probabilities in lowest terms.
	 *
	 * @param numerator the numerator
	 * @param denominator the positive denominator
	 */
	private record Sum(BigInteger numerator, BigInteger denominator)
	{
		/**
		 * Adds {@code count} copies of the error probability {@code 1 / level.threshold()}.
		 *
		 * @param count the number of decisions that spend the level
		 * @param level the level
		 * @return the new sum
		 */
		Sum plus(int count, SignificanceLevel level)
		{
			BigInteger threshold = level.threshold();
			BigInteger sumNumerator = numerator.multiply(threshold).add(BigInteger.valueOf(count).multiply(denominator));
			BigInteger sumDenominator = denominator.multiply(threshold);
			BigInteger divisor = sumNumerator.gcd(sumDenominator);
			return new Sum(sumNumerator.divide(divisor), sumDenominator.divide(divisor));
		}
	}
}
