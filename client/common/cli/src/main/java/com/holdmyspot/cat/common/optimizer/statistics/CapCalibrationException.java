package com.holdmyspot.cat.common.optimizer.statistics;

import java.io.Serial;

/**
 * Thrown when no cap up to {@link CapCalibration#MAXIMUM_CAP} gives a cell, one prompt test under one trial profile, at
 * the planning pass rate the required power to certify. A certification attempt without a cap must not be admitted.
 */
public final class CapCalibrationException extends Exception
{
	@Serial
	private static final long serialVersionUID = 0L;

	/**
	 * Creates an exception.
	 *
	 * @param message the reason that calibration failed
	 */
	public CapCalibrationException(String message)
	{
		super(message);
	}
}
