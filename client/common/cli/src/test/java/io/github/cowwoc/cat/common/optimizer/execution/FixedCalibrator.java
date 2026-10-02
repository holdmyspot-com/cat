package io.github.cowwoc.cat.common.optimizer.execution;

import io.github.cowwoc.cat.common.optimizer.statistics.CapCalibration;
import io.github.cowwoc.cat.common.optimizer.statistics.CapCalibrationException;
import io.github.cowwoc.cat.common.optimizer.statistics.SignificanceLevel;

/**
 * A {@link CapCalibrator} that returns a fixed cap without running calibration, or fails if it has no cap.
 * <p>
 * <b>Thread Safety:</b> This class is immutable and thread-safe.
 *
 * @param cap the cap of every attempt, or {@code 0} to fail
 */
public record FixedCalibrator(int cap) implements CapCalibrator
{
	/**
	 * The first attempt's cap of 262 trials, calibrated for 90% certification power at a 99% pass rate.
	 */
	public static final int STANDARD_CAP = 262;

	@Override
	public CapCalibration calibrate(SignificanceLevel level) throws CapCalibrationException
	{
		if (cap == 0)
			throw new CapCalibrationException("The calibration found no cap");
		return new CapCalibration(level, cap);
	}
}
