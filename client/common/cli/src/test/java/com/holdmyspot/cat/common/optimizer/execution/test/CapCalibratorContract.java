package com.holdmyspot.cat.common.optimizer.execution.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.optimizer.contract.Contract;
import com.holdmyspot.cat.common.optimizer.execution.CapCalibrator;
import com.holdmyspot.cat.common.optimizer.statistics.CapCalibration;
import com.holdmyspot.cat.common.optimizer.statistics.CapCalibrationException;
import com.holdmyspot.cat.common.optimizer.statistics.ErrorBudget;
import com.holdmyspot.cat.common.optimizer.statistics.SignificanceLevel;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.math.BigDecimal;

/**
 * The behavior that every {@link CapCalibrator} must provide: a calibration for the requested level and minimum pass
 * rate, whose cap is a usable trial count, and a refusal of a missing argument. Each implementation's test extends this
 * class.
 */
public abstract class CapCalibratorContract
{
	/**
	 * Creates the contract tests.
	 */
	protected CapCalibratorContract()
	{
	}

	/**
	 * Creates the calibrator under test.
	 *
	 * @return the calibrator
	 */
	protected abstract CapCalibrator create();

	/**
	 * Returns the attempts and minimum pass rates for which the calibrator is asked.
	 *
	 * @return the certification index and the minimum pass rate
	 */
	@DataProvider
	public Object[][] requests()
	{
		return new Object[][]
		{
			{1, Contract.DEFAULT_MINIMUM_PASS_RATE},
			{3, new BigDecimal("0.90")}
		};
	}

	/**
	 * Verifies that the calibration names the requested level and minimum pass rate and has a cap within the largest
	 * that calibration considers.
	 *
	 * @param certificationIndex the certification index
	 * @param minimumPassRate    the minimum pass rate
	 * @throws CapCalibrationException if the calibrator finds no cap
	 */
	@Test(dataProvider = "requests")
	public void calibrationIsForTheRequest(int certificationIndex, BigDecimal minimumPassRate)
		throws CapCalibrationException
	{
		SignificanceLevel level = ErrorBudget.certificationLevel(certificationIndex);
		CapCalibration calibration = create().calibrate(level, minimumPassRate);
		requireThat(calibration.level(), "level").isEqualTo(level);
		requireThat(calibration.minimumPassRate(), "minimumPassRate").isEqualTo(minimumPassRate);
		requireThat(calibration.cap(), "cap").isBetween(1, true, CapCalibration.MAXIMUM_CAP, true);
	}

	/**
	 * Verifies that a missing level is refused.
	 *
	 * @throws CapCalibrationException never
	 */
	@Test(expectedExceptions = NullPointerException.class)
	public void rejectsNullLevel() throws CapCalibrationException
	{
		create().calibrate(null, Contract.DEFAULT_MINIMUM_PASS_RATE);
	}

	/**
	 * Verifies that a missing minimum pass rate is refused.
	 *
	 * @throws CapCalibrationException never
	 */
	@Test(expectedExceptions = NullPointerException.class)
	public void rejectsNullMinimumPassRate() throws CapCalibrationException
	{
		create().calibrate(ErrorBudget.certificationLevel(1), null);
	}
}
