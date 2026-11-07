package com.holdmyspot.cat.common.optimizer.execution.test;

import com.holdmyspot.cat.common.optimizer.execution.CapCalibrator;
import com.holdmyspot.cat.common.optimizer.statistics.CapCalibration;
import com.holdmyspot.cat.common.optimizer.statistics.SignificanceLevel;

import java.math.BigDecimal;

/**
 * A {@link CapCalibrator} that gives every attempt the same cap without running calibration. The tests that commit or
 * propose campaigns use it, so that only {@code CapCalibrationTest} runs {@link CapCalibration}. The caps of real
 * attempts grow with the attempt's index, which these tests do not depend on. {@code CapCalibratorContract} checks the
 * behavior that this class and the real calibration share.
 * <p>
 * <b>Thread Safety:</b> This class is immutable and thread-safe.
 *
 * @param cap the cap of every attempt, in {@code [1, CapCalibration.MAXIMUM_CAP]}
 */
public record FixedCalibrator(int cap) implements CapCalibrator
{
	/**
	 * The first attempt's cap of 262 trials, which {@code CapCalibrationTest} checks for the default minimum pass rate.
	 */
	public static final int STANDARD_CAP = 262;

	@Override
	public CapCalibration calibrate(SignificanceLevel level, BigDecimal minimumPassRate)
	{
		return new CapCalibration(level, minimumPassRate, cap);
	}
}
