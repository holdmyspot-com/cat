package io.github.cowwoc.cat.common.optimizer.execution;

import io.github.cowwoc.cat.common.optimizer.statistics.CapCalibration;
import io.github.cowwoc.cat.common.optimizer.statistics.CapCalibrationException;
import io.github.cowwoc.cat.common.optimizer.statistics.SignificanceLevel;

import java.math.BigDecimal;

/**
 * Determines the maximum trial count for a certification attempt when the {@link Driver} commits a candidate.
 * <p>
 * Production passes {@link CapCalibration#calibrate(SignificanceLevel, BigDecimal)}; tests pass a precomputed
 * calibration.
 */
@FunctionalInterface
public interface CapCalibrator
{
	/**
	 * Determines the trial cap that reaches the required certification power at a significance level.
	 *
	 * @param level           the attempt's certification level
	 * @param minimumPassRate the campaign's minimum pass rate {@code p_min}
	 * @return the calibration
	 * @throws NullPointerException    if any of the arguments are null
	 * @throws CapCalibrationException if no cap reaches the required power
	 */
	CapCalibration calibrate(SignificanceLevel level, BigDecimal minimumPassRate) throws CapCalibrationException;
}
