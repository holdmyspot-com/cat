package io.github.cowwoc.cat.common.optimizer.execution;

import io.github.cowwoc.cat.common.optimizer.statistics.CapCalibration;
import io.github.cowwoc.cat.common.optimizer.statistics.CapCalibrationException;
import io.github.cowwoc.cat.common.optimizer.statistics.SignificanceLevel;

/**
 * Determines the maximum trial count for a certification attempt when the {@link Driver} commits a candidate.
 * <p>
 * Production passes {@link CapCalibration#calibrate(SignificanceLevel)}; tests pass a precomputed calibration.
 */
@FunctionalInterface
public interface CapCalibrator
{
	/**
	 * Determines the trial cap that reaches the required certification power at a significance level.
	 *
	 * @param level the attempt's certification level
	 * @return the calibration
	 * @throws NullPointerException if {@code level} is null
	 * @throws CapCalibrationException if no cap reaches the required power
	 */
	CapCalibration calibrate(SignificanceLevel level) throws CapCalibrationException;
}
