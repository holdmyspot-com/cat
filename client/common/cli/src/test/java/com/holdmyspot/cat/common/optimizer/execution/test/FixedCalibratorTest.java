package com.holdmyspot.cat.common.optimizer.execution.test;

import com.holdmyspot.cat.common.optimizer.execution.CapCalibrator;

/**
 * Tests {@link FixedCalibrator} against {@link CapCalibratorContract}, which {@code CapCalibrationTest} runs on the
 * real calibration.
 */
public final class FixedCalibratorTest extends CapCalibratorContract
{
	/**
	 * Creates a test instance.
	 */
	public FixedCalibratorTest()
	{
	}

	@Override
	protected CapCalibrator create()
	{
		return new FixedCalibrator(FixedCalibrator.STANDARD_CAP);
	}
}
