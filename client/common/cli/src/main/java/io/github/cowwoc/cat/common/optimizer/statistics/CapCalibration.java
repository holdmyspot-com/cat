package io.github.cowwoc.cat.common.optimizer.statistics;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.util.List;

/**
 * A certification attempt's per-cell cap, calibrated once before admission and persisted with the attempt. A cell is
 * one category under one trial profile.
 * <p>
 * The cap is the smallest number of adopted outcomes at which a cell whose true pass rate is
 * {@link #PLANNING_PASS_RATE} certifies with probability at least {@link #POWER}. A cell stops early when it
 * certifies or is clearly below, so the probability comes from an exact absorbing-state calculation over the states
 * {@code (S, F)}: after each outcome, the mass of every certifying state is absorbed as certified and the mass of every
 * clearly-below state is discarded.
 * <p>
 * The calculation sums probabilities in {@code double}. The decisions that bound the absorbing states are exact
 * {@link DecisionBoundaries}.
 *
 * @param level the attempt's certification level {@code alpha_i}
 * @param cap   the per-cell cap, in {@code [1, MAXIMUM_CAP]}
 */
public record CapCalibration(SignificanceLevel level, int cap)
{
	/**
	 * The true pass rate at which a cell must certify with the required power.
	 */
	public static final double PLANNING_PASS_RATE = 0.99;
	/**
	 * The probability with which a cell at the planning pass rate must certify within the cap.
	 */
	public static final double POWER = 0.90;
	/**
	 * The largest cap that calibration considers.
	 * <p>
 * The caps grow roughly with the logarithm of the attempt's threshold: attempt 100 has a cap of 712, and an
	 * attempt index near the largest {@code int} would need a cap of about 2,500.
	 */
	public static final int MAXIMUM_CAP = 10_000;

	/**
	 * Creates a calibration.
	 *
	 * @param level the attempt's certification level {@code alpha_i}
	 * @param cap   the per-cell cap
	 * @throws NullPointerException     if {@code level} is null
	 * @throws IllegalArgumentException if {@code cap} is not in {@code [1, MAXIMUM_CAP]}
	 */
	public CapCalibration
	{
		requireThat(level, "level").isNotNull();
		requireThat(cap, "cap").isBetween(1, true, MAXIMUM_CAP, true);
	}

	/**
	 * Calibrates the cap of an attempt.
	 *
	 * @param level the attempt's certification level {@code alpha_i}
	 * @return the calibration
	 * @throws NullPointerException    if {@code level} is null
	 * @throws CapCalibrationException if no cap up to {@link #MAXIMUM_CAP} reaches the required power
	 */
	public static CapCalibration calibrate(SignificanceLevel level) throws CapCalibrationException
	{
		requireThat(level, "level").isNotNull();
		Walk walk = new Walk(DecisionBoundaries.compute(level, MAXIMUM_CAP, List.of()), PLANNING_PASS_RATE);
		double certified = 0;
		for (int outcomes = 1; outcomes <= MAXIMUM_CAP; ++outcomes)
		{
			certified = walk.advance();
			if (certified >= POWER)
				return new CapCalibration(level, outcomes);
		}
		throw new CapCalibrationException("A cell whose true pass rate is " + PLANNING_PASS_RATE +
			" certifies at level 1/" + level.threshold() + " within " + MAXIMUM_CAP +
			" adopted outcomes with probability " + certified + ", which is below the required power of " + POWER +
			". No cap up to " + MAXIMUM_CAP + " exists for this level, so an attempt at this level cannot be admitted.");
	}

	/**
	 * The distribution of a cell's undecided states, advanced one adopted outcome at a time.
	 * <p>
	 * After {@code n} outcomes, a state with {@code F} failures has {@code n - F} passes, so the distribution is
	 * indexed by {@code F} alone.
	 * <p>
	 * <b>Thread Safety:</b> This class is not thread-safe.
	 */
	static final class Walk
	{
		/**
		 * The fewest passes that certify, indexed by failures.
		 */
		private final int[] minimumCertifyingPasses;
		/**
		 * The most passes that are clearly below, indexed by failures.
		 */
		private final int[] maximumClearlyBelowPasses;
		private final double passRate;
		/**
		 * The probability of each undecided state after {@link #outcomes} outcomes, indexed by its failures.
		 */
		private final double[] undecided;
		private int outcomes;
		private double certified;

		/**
		 * Creates a walk that starts with no outcomes.
		 *
		 * @param boundaries the decisions, whose cap bounds the number of outcomes
		 * @param passRate   the cell's true pass rate
		 */
		Walk(DecisionBoundaries boundaries, double passRate)
		{
			// Unboxed copies keep the quadratic number of lookups cheap
			this.minimumCertifyingPasses = boundaries.minimumCertifyingPasses().stream().
				mapToInt(Integer::intValue).toArray();
			this.maximumClearlyBelowPasses = boundaries.maximumClearlyBelowPasses().stream().
				mapToInt(Integer::intValue).toArray();
			this.passRate = passRate;
			this.undecided = new double[boundaries.cap() + 1];
			undecided[0] = 1;
		}

		/**
		 * Advances the distribution by one outcome and absorbs the states that decide.
		 *
		 * @return the probability of having certified within the outcomes so far
		 */
		double advance()
		{
			++outcomes;
			double failureRate = 1 - passRate;
			// Descend so that each update reads the previous outcome's value at failures - 1
			undecided[outcomes] = failureRate * undecided[outcomes - 1];
			for (int failures = outcomes - 1; failures > 0; --failures)
				undecided[failures] = passRate * undecided[failures] + failureRate * undecided[failures - 1];
			undecided[0] *= passRate;

			for (int failures = 0; failures <= outcomes; ++failures)
			{
				int passes = outcomes - failures;
				if (passes >= minimumCertifyingPasses[failures])
				{
					certified += undecided[failures];
					undecided[failures] = 0;
				}
				else if (passes <= maximumClearlyBelowPasses[failures])
					undecided[failures] = 0;
			}
			return certified;
		}
	}
}
