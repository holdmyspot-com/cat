package com.holdmyspot.cat.common.optimizer.execution.test;

import com.holdmyspot.cat.common.optimizer.execution.CapCalibrator;
import com.holdmyspot.cat.common.optimizer.statistics.CapCalibration;
import com.holdmyspot.cat.common.optimizer.statistics.CapCalibrationException;
import com.holdmyspot.cat.common.optimizer.statistics.SignificanceLevel;

import java.math.BigDecimal;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A {@link CapCalibrator} that returns the result of {@link CapCalibration#calibrate(SignificanceLevel, BigDecimal)}
 * and remembers it for the next request of the same level and minimum pass rate. The calibration is a pure function of
 * those two values and takes tens of milliseconds, so the tests that commit or propose many campaigns run it once per
 * pair instead of once per test. It adds no behavior of its own.
 * <p>
 * <b>Thread Safety:</b> This class is thread-safe.
 */
public final class MemoizedCalibration implements CapCalibrator
{
	/**
	 * The calibrator that every test shares.
	 */
	public static final MemoizedCalibration INSTANCE = new MemoizedCalibration();
	private final Map<Request, CapCalibration> calibrations = new ConcurrentHashMap<>();

	/**
	 * Creates a calibrator that has remembered nothing.
	 */
	private MemoizedCalibration()
	{
	}

	@Override
	public CapCalibration calibrate(SignificanceLevel level, BigDecimal minimumPassRate)
		throws CapCalibrationException
	{
		Request request = new Request(level, minimumPassRate);
		CapCalibration known = calibrations.get(request);
		if (known != null)
			return known;
		// Two threads may calibrate the same request at once; both get the same value
		CapCalibration calibration = CapCalibration.calibrate(level, minimumPassRate);
		calibrations.put(request, calibration);
		return calibration;
	}

	/**
	 * A calibration request.
	 *
	 * @param level the attempt's certification level
	 * @param minimumPassRate the campaign's minimum pass rate
	 */
	private record Request(SignificanceLevel level, BigDecimal minimumPassRate)
	{
	}
}
