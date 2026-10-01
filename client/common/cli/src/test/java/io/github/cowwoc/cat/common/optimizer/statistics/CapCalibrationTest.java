package io.github.cowwoc.cat.common.optimizer.statistics;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.math.BigInteger;

/**
 * Tests {@link CapCalibration}. This is the only test class that runs calibration.
 * <p>
 * The tests check these {@code design.md} numbers. The probabilities were also computed by an independent script.
 * <table>
 *   <caption>Design numbers checked by this class</caption>
 *   <tr><th>Source</th><th>Quantity</th><th>Design value</th><th>Checked value</th></tr>
 *   <tr><td>&sect;2.4</td><td>cap for attempts 1, 2, 3, 4, 5</td><td>262, 328, 384, 398, 409</td>
 *     <td>262, 328, 384, 398, 409</td></tr>
 *   <tr><td>&sect;2.4</td><td>cap for attempts 10, 50, 100</td><td>481, 638, 712</td><td>481, 638, 712</td></tr>
 *   <tr><td>&sect;2.4</td><td>P(certify within 600) at 98% for attempts 1, 2, 3, 4, 5</td>
 *     <td>0.854, 0.759, 0.677, 0.601, 0.568</td><td>0.854071, 0.758677, 0.676640, 0.600894, 0.568500</td></tr>
 *   <tr><td>&sect;2.4</td><td>P(certify within 600) at 98% for attempts 10, 50, 100</td><td>0.433, 0.183, 0.108</td>
 *     <td>0.432912, 0.182988, 0.108498</td></tr>
 *   <tr><td>&sect;2.7</td><td>P(certify within attempt 1's cap of 262) at 99%, 98%, 97%</td><td>0.90, 0.49, 0.17</td>
 *     <td>0.903856, 0.486035, 0.173554</td></tr>
 *   <tr><td>&sect;13 D1</td><td>attempt 1's cap with an unsplit 5% ({@code alpha_1 = 0.025})</td><td>255</td>
 *     <td>255</td></tr>
 *   <tr><td>&sect;13 D1</td><td>attempt 1's cap with a certification budget of 0.025</td><td>311</td><td>311</td></tr>
 *   <tr><td>&sect;13 D19</td><td>caps of attempts 1 and 100 under {@code 0.4 / ((i + 9)(i + 10))}</td>
 *     <td>382, 619</td><td>382, 619</td></tr>
 *   <tr><td>&sect;13 D19</td><td>cap under an equal split over 100 attempts</td><td>478</td><td>478</td></tr>
 *   <tr><td>&sect;13 D3</td><td>P(certify) for a true 97% candidate</td><td>about 15%</td><td>0.1736 within attempt
 *     1's cap of 262 (0.1172 within attempt 2's cap of 328); &sect;2.7 states 0.17 for attempt 1</td></tr>
 * </table>
 * The {@code design.md} &sect;13 D2 alternatives use different bets, which {@link BettingProcess} fixes, so this
 * class does not check them.
 */
public final class CapCalibrationTest
{
	/**
	 * Creates a test instance.
	 */
	public CapCalibrationTest()
	{
	}

	/**
	 * Returns the rows of the {@code design.md} &sect;2.4 table.
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
	 * Verifies one row of the {@code design.md} &sect;2.4 table: the cap, and the probability of certifying within 600
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
		CapCalibration calibration = CapCalibration.calibrate(level);
		requireThat(calibration.level(), "level").isEqualTo(level);
		requireThat(calibration.cap(), "cap").isEqualTo(cap);
		requireThat(CapCalibration.certificationProbability(level, 0.99, cap), "powerAtCap").
			isGreaterThanOrEqualTo(CapCalibration.POWER);
		requireThat(CapCalibration.certificationProbability(level, 0.99, cap - 1), "powerBeforeCap").
			isLessThan(CapCalibration.POWER);
		requireThat(CapCalibration.certificationProbability(level, 0.98, 600), "probabilityWithin600At98").
			isBetween(probabilityWithin600At98 - 1.0e-9, probabilityWithin600At98 + 1.0e-9);
	}

	/**
	 * Verifies the certification probabilities within attempt 1's cap that {@code design.md} &sect;2.7 states.
	 */
	@Test
	public void firstAttemptProbabilitiesMatchDesign()
	{
		// Requirement: R-S6
		SignificanceLevel level = ErrorBudget.certificationLevel(1);
		requireThat(CapCalibration.certificationProbability(level, 0.99, 262), "at99").
			isBetween(0.903_855_941_637 - 1.0e-9, 0.903_855_941_637 + 1.0e-9);
		requireThat(CapCalibration.certificationProbability(level, 0.98, 262), "at98").
			isBetween(0.486_035_249_752 - 1.0e-9, 0.486_035_249_752 + 1.0e-9);
		requireThat(CapCalibration.certificationProbability(level, 0.97, 262), "at97").
			isBetween(0.173_553_940_215 - 1.0e-9, 0.173_553_940_215 + 1.0e-9);
	}

	/**
	 * Verifies the caps of the alternative budget splits in {@code design.md} &sect;13 D1 and D19.
	 *
	 * @throws CapCalibrationException if calibration fails
	 */
	@Test
	public void alternativeBudgetCapsMatchDesign() throws CapCalibrationException
	{
		// Requirement: R-S6
		requireThat(CapCalibration.calibrate(level(40)).cap(), "unsplit").isEqualTo(255);
		requireThat(CapCalibration.calibrate(level(80)).cap(), "smallerShare").isEqualTo(311);
		// 0.4 / ((1 + 9)(1 + 10)) = 1 / 275 and 0.4 / ((100 + 9)(100 + 10)) = 1 / 29975
		requireThat(CapCalibration.calibrate(level(275)).cap(), "flatterSeriesFirst").isEqualTo(382);
		requireThat(CapCalibration.calibrate(level(29_975)).cap(), "flatterSeriesHundredth").isEqualTo(619);
		requireThat(CapCalibration.calibrate(level(2500)).cap(), "equalSplit").isEqualTo(478);
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
		CapCalibration.calibrate(new SignificanceLevel(BigInteger.TEN.pow(200)));
	}

	/**
	 * Verifies that a cap outside {@code [1, MAXIMUM_CAP]} is rejected.
	 */
	@Test(expectedExceptions = IllegalArgumentException.class)
	public void rejectsCapAboveMaximum()
	{
		new CapCalibration(ErrorBudget.certificationLevel(1), CapCalibration.MAXIMUM_CAP + 1);
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
}
