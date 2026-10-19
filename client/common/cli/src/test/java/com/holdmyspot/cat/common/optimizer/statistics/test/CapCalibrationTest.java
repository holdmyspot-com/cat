package com.holdmyspot.cat.common.optimizer.statistics.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.optimizer.statistics.BettingProcess;
import com.holdmyspot.cat.common.optimizer.statistics.CapCalibration;
import com.holdmyspot.cat.common.optimizer.statistics.CapCalibrationException;
import com.holdmyspot.cat.common.optimizer.statistics.DecisionBoundaries;
import com.holdmyspot.cat.common.optimizer.statistics.ErrorBudget;
import com.holdmyspot.cat.common.optimizer.statistics.SignificanceLevel;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;

/**
 * Tests {@link CapCalibration}. This is the only test class that runs calibration.
 * <p>
 * The tests check these reference values. The probabilities were also computed by an independent script.
 * <table>
 *   <caption>Reference values checked by this class</caption>
 *   <tr><th>Quantity</th><th>Reference value</th><th>Checked value</th></tr>
 *   <tr><td>cap for attempts 1, 2, 3, 4, 5</td><td>262, 328, 384, 398, 409</td>
 *     <td>262, 328, 384, 398, 409</td></tr>
 *   <tr><td>cap for attempts 10, 50, 100</td><td>481, 638, 712</td><td>481, 638, 712</td></tr>
 *   <tr><td>P(certify within 600) at 98% for attempts 1, 2, 3, 4, 5</td>
 *     <td>0.854, 0.759, 0.677, 0.601, 0.568</td><td>0.854071, 0.758677, 0.676640, 0.600894, 0.568500</td></tr>
 *   <tr><td>P(certify within 600) at 98% for attempts 10, 50, 100</td><td>0.433, 0.183, 0.108</td>
 *     <td>0.432912, 0.182988, 0.108498</td></tr>
 *   <tr><td>P(certify within attempt 1's cap of 262) at 99%, 98%, 97%</td><td>0.90, 0.49, 0.17</td>
 *     <td>0.903856, 0.486035, 0.173554</td></tr>
 *   <tr><td>attempt 1's cap with an unsplit 5% ({@code alpha_1 = 0.025})</td><td>255</td>
 *     <td>255</td></tr>
 *   <tr><td>attempt 1's cap with a certification budget of 0.025</td><td>311</td><td>311</td></tr>
 *   <tr><td>caps of attempts 1 and 100 under {@code 0.4 / ((i + 9)(i + 10))}</td>
 *     <td>382, 619</td><td>382, 619</td></tr>
 *   <tr><td>cap under an equal split over 100 attempts</td><td>478</td><td>478</td></tr>
 *   <tr><td>P(certify) for a true 97% candidate</td><td>about 15%</td><td>0.1736 within attempt
 *     1's cap of 262 (0.1172 within attempt 2's cap of 328); rounded to 0.17 for attempt 1</td></tr>
 * </table>
 * The single 99% bet and equal-weight mixture use different bets, which {@link BettingProcess} fixes, so this
 * class does not check them.
 */
public final class CapCalibrationTest
{
	/**
	 * The campaign's minimum pass rate {@code p_min}.
	 */
	private static final BigDecimal MINIMUM_PASS_RATE = new BigDecimal("0.95");

	/**
	 * Creates a test instance.
	 */
	public CapCalibrationTest()
	{
	}

	/**
	 * Returns the certification caps and probabilities per attempt.
	 *
	 * @return the certification index, the cap and the probability of certifying within 600 outcomes at a 98% pass
	 *   rate, as computed by the independent script
	 */
	@DataProvider
	public Object[][] capTable()
	{
		return new Object[][]
		{
			{1, 262, 0.854_071_138_730},
			{2, 328, 0.758_676_554_874},
			{3, 384, 0.676_639_761_663},
			{4, 398, 0.600_894_276_558},
			{5, 409, 0.568_499_664_633},
			{10, 481, 0.432_912_425_964},
			{50, 638, 0.182_988_253_021},
			{100, 712, 0.108_497_811_890}
		};
	}

	/**
	 * Verifies each attempt's cap and probability of certifying within 600
	 * outcomes at a 98% pass rate.
	 *
	 * @param certificationIndex          the certification index
	 * @param cap                         the expected cap
	 * @param probabilityWithin600At98    the expected probability
	 * @throws CapCalibrationException if calibration fails
	 */
	@Test(dataProvider = "capTable")
	public void capMatchesTableRow(int certificationIndex, int cap, double probabilityWithin600At98)
		throws CapCalibrationException
	{
		// Requirement: R-S6
		SignificanceLevel level = ErrorBudget.certificationLevel(certificationIndex);
		CapCalibration calibration = CapCalibration.calibrate(level, MINIMUM_PASS_RATE);
		requireThat(calibration.level(), "level").isEqualTo(level);
		requireThat(calibration.cap(), "cap").isEqualTo(cap);
		requireThat(certificationProbability(level, 0.99, cap), "powerAtCap").
			isGreaterThanOrEqualTo(CapCalibration.POWER);
		requireThat(certificationProbability(level, 0.99, cap - 1), "powerBeforeCap").
			isLessThan(CapCalibration.POWER);
		requireThat(certificationProbability(level, 0.98, 600), "probabilityWithin600At98").
			isBetween(probabilityWithin600At98 - 1.0e-9, probabilityWithin600At98 + 1.0e-9);
	}

	/**
	 * Verifies the certification probabilities within attempt 1's cap at 99%, 98% and 97% pass rates.
	 */
	@Test
	public void firstAttemptProbabilitiesMatchReference()
	{
		// Requirement: R-S6
		SignificanceLevel level = ErrorBudget.certificationLevel(1);
		requireThat(certificationProbability(level, 0.99, 262), "at99").
			isBetween(0.903_855_941_637 - 1.0e-9, 0.903_855_941_637 + 1.0e-9);
		requireThat(certificationProbability(level, 0.98, 262), "at98").
			isBetween(0.486_035_249_752 - 1.0e-9, 0.486_035_249_752 + 1.0e-9);
		requireThat(certificationProbability(level, 0.97, 262), "at97").
			isBetween(0.173_553_940_215 - 1.0e-9, 0.173_553_940_215 + 1.0e-9);
	}

	/**
	 * Verifies the caps under alternative budget shares, a flatter spending series and an equal split.
	 *
	 * @throws CapCalibrationException if calibration fails
	 */
	@Test
	public void alternativeBudgetCapsMatchReference() throws CapCalibrationException
	{
		// Requirement: R-S6
		requireThat(CapCalibration.calibrate(level(40), MINIMUM_PASS_RATE).cap(), "unsplit").isEqualTo(255);
		requireThat(CapCalibration.calibrate(level(80), MINIMUM_PASS_RATE).cap(), "smallerShare").isEqualTo(311);
		// 0.4 / ((1 + 9)(1 + 10)) = 1 / 275 and 0.4 / ((100 + 9)(100 + 10)) = 1 / 29975
		requireThat(CapCalibration.calibrate(level(275), MINIMUM_PASS_RATE).cap(), "flatterSeriesFirst").isEqualTo(382);
		requireThat(CapCalibration.calibrate(level(29_975), MINIMUM_PASS_RATE).cap(),
			"flatterSeriesHundredth").isEqualTo(619);
		requireThat(CapCalibration.calibrate(level(2500), MINIMUM_PASS_RATE).cap(), "equalSplit").isEqualTo(478);
	}

	/**
	 * Verifies that a level that no cap up to the maximum can reach with 90% power fails calibration, so that
	 * admission can be blocked.
	 *
	 * @throws CapCalibrationException always
	 */
	@Test(expectedExceptions = CapCalibrationException.class)
	public void unreachableLevelFailsCalibration() throws CapCalibrationException
	{
		// Requirement: R-S6
		CapCalibration.calibrate(new SignificanceLevel(BigInteger.TEN.pow(200)), MINIMUM_PASS_RATE);
	}

	/**
	 * Verifies that a cap outside {@code [1, MAXIMUM_CAP]} is rejected.
	 */
	@Test(expectedExceptions = IllegalArgumentException.class)
	public void rejectsCapAboveMaximum()
	{
		new CapCalibration(ErrorBudget.certificationLevel(1), MINIMUM_PASS_RATE, CapCalibration.MAXIMUM_CAP + 1);
	}

	/**
	 * Returns the probability that a cell certifies within a number of adopted outcomes, given both early stops.
	 *
	 * @param level    the attempt's certification level {@code alpha_i}
	 * @param passRate the cell's true pass rate
	 * @param outcomes the number of adopted outcomes
	 * @return the probability
	 */
	private static double certificationProbability(SignificanceLevel level, double passRate, int outcomes)
	{
		CapCalibration.Walk walk = new CapCalibration.Walk(DecisionBoundaries.compute(level, MINIMUM_PASS_RATE, outcomes,
			List.of()),
			passRate);
		double certified = 0;
		for (int outcome = 1; outcome <= outcomes; ++outcome)
			certified = walk.advance();
		return certified;
	}

	/**
	 * Returns a level with the given threshold.
	 *
	 * @param threshold the threshold
	 * @return the level
	 */
	private static SignificanceLevel level(long threshold)
	{
		return new SignificanceLevel(BigInteger.valueOf(threshold));
	}

	/**
	 * Verifies that, for the minimum pass rate {@code p_min}, the planning pass rate is {@code 1 - (1 - p_min) / 5}.
	 */
	@Test
	public void planningRateFollowsMinimumPassRate()
	{
		// Requirement: R-S6
		requireThat(CapCalibration.planningPassRate(new BigDecimal("0.95")), "ninetyFive").isEqualTo(0.99);
		requireThat(CapCalibration.planningPassRate(new BigDecimal("0.90")), "ninety").isEqualTo(0.98);
		requireThat(CapCalibration.planningPassRate(new BigDecimal("0.80")), "eighty").isEqualTo(0.96);
	}

	/**
	 * Verifies the first attempt's cap at other minimum pass rates, against an independent calculation of the
	 * absorbing-state walk at the planning pass rate: 129 at the minimum pass rate {@code p_min = 0.90} and 62 at
	 * {@code p_min = 0.80}.
	 *
	 * @throws CapCalibrationException if no cap exists
	 */
	@Test
	public void capFollowsMinimumPassRate() throws CapCalibrationException
	{
		// Requirement: R-S6
		SignificanceLevel level = ErrorBudget.certificationLevel(1);
		requireThat(CapCalibration.calibrate(level, new BigDecimal("0.90")).cap(), "ninety").isEqualTo(129);
		requireThat(CapCalibration.calibrate(level, new BigDecimal("0.80")).cap(), "eighty").isEqualTo(62);
	}

	/**
	 * Verifies the all-pass minimums of the first attempt at the minimum pass rate {@code p_min} = 0.80, 0.90, 0.95, 0.97
	 * and 0.99.
	 */
	@Test
	public void allPassMinimumsFollowMinimumPassRate()
	{
		// Requirement: R-S6
		SignificanceLevel level = ErrorBudget.certificationLevel(1);
		requireThat(DecisionBoundaries.allPassMinimum(level, new BigDecimal("0.80")), "eighty").isEqualTo(22);
		requireThat(DecisionBoundaries.allPassMinimum(level, new BigDecimal("0.90")), "ninety").isEqualTo(45);
		requireThat(DecisionBoundaries.allPassMinimum(level, new BigDecimal("0.95")), "ninetyFive").isEqualTo(93);
		requireThat(DecisionBoundaries.allPassMinimum(level, new BigDecimal("0.97")), "ninetySeven").isEqualTo(157);
		requireThat(DecisionBoundaries.allPassMinimum(level, new BigDecimal("0.99")), "ninetyNine").isEqualTo(474);
	}
}
