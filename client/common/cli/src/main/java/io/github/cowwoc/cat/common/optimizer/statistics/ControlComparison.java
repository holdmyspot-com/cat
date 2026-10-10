package io.github.cowwoc.cat.common.optimizer.statistics;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;
import java.util.function.IntFunction;

/**
 * The comparison of one assertion between a cell's candidate trials and its control trials, which run the incumbent
 * with every target prompt file emptied.
 * <p>
 * Pair {@code j} holds the cell's {@code j}-th candidate formal trial and its {@code j}-th control trial, which ran the
 * same test case. With {@code X_j} and {@code Y_j} equal to 1 when the candidate trial and the control trial pass the
 * assertion, and {@code Z_j = (X_j - Y_j + 1) / 2}:
 * <ul>
 *   <li>{@code E_up = sum_m (1 / 4) prod_j (1 + (f_m / m) (Z_j - m))} tests the null
 *     {@code pC <= pN - 0.05}, under which the mean of {@code Z} is at most
 *     {@code m = (1 - 0.05) / 2 = 0.475};</li>
 *   <li>{@code E_down = sum_m (1 / 4) prod_j (1 + (f_m / (1 - m)) (m - Z_j))} tests the opposite null,
 *     {@code pC >= pN - 0.05};</li>
 * </ul>
 * with {@code f = 1/4, 1/2, 3/4, 9/10} and the fixed {@link #MARGIN}. Every factor is positive, and each e-value
 * depends only on how many pairs gave each value of {@code Z}. Under its null, each e-value is a nonnegative
 * supermartingale with initial value 1, so it exceeds a threshold {@code T} with probability at most {@code 1 / T}
 * under every stopping rule.
 * <p>
 * Every decision compares exact rationals, and equality never decides.
 * <p>
 * <b>Thread Safety:</b> This class is stateless and thread-safe.
 */
public final class ControlComparison
{
	/**
	 * The margin by which the candidate's pass probability of an assertion may fall below the control's.
	 */
	public static final BigDecimal MARGIN = new BigDecimal("0.05");
	/**
	 * The null mean {@code m = (1 - margin) / 2}, as a numerator and denominator.
	 */
	private static final BigInteger[] MIDPOINT = midpoint();
	/**
	 * The bets {@code f_m}, as numerator and denominator pairs.
	 */
	private static final List<long[]> BETS = List.of(new long[]{1, 4}, new long[]{1, 2}, new long[]{3, 4},
		new long[]{9, 10});
	/**
	 * The terms of {@code E_up}.
	 */
	private static final List<Term> ABOVE = terms(true);
	/**
	 * The terms of {@code E_down}.
	 */
	private static final List<Term> BELOW = terms(false);

	/**
	 * Prevents instantiation.
	 */
	private ControlComparison()
	{
	}

	/**
	 * Returns {@code m = (1 - margin) / 2} in lowest terms.
	 *
	 * @return the numerator and the denominator
	 */
	private static BigInteger[] midpoint()
	{
		BigInteger unscaled = MARGIN.unscaledValue();
		BigInteger scale = BigInteger.TEN.pow(MARGIN.scale());
		BigInteger numerator = scale.subtract(unscaled);
		BigInteger denominator = scale.shiftLeft(1);
		BigInteger divisor = numerator.gcd(denominator);
		return new BigInteger[]{numerator.divide(divisor), denominator.divide(divisor)};
	}

	/**
	 * Returns the terms of one e-value. With {@code f = p / q}, {@code m = a / b} and {@code Z = n / 2}, the factor of
	 * {@code E_up} is {@code (2qa + p (n b - 2a)) / (2qa)}, and that of {@code E_down} is
	 * {@code (2q (b - a) + p (2a - n b)) / (2q (b - a))}.
	 *
	 * @param above {@code true} for {@code E_up}, {@code false} for {@code E_down}
	 * @return the terms
	 */
	private static List<Term> terms(boolean above)
	{
		BigInteger a = MIDPOINT[0];
		BigInteger b = MIDPOINT[1];
		BigInteger two = BigInteger.TWO;
		return BETS.stream().map(bet ->
		{
			BigInteger p = BigInteger.valueOf(bet[0]);
			BigInteger q = BigInteger.valueOf(bet[1]);
			BigInteger denominator;
			if (above)
				denominator = two.multiply(q).multiply(a);
			else
				denominator = two.multiply(q).multiply(b.subtract(a));
			BigInteger[] factors = new BigInteger[3];
			for (int n = 0; n < factors.length; ++n)
			{
				BigInteger nb = b.multiply(BigInteger.valueOf(n));
				BigInteger shift;
				if (above)
					shift = p.multiply(nb.subtract(two.multiply(a)));
				else
					shift = p.multiply(two.multiply(a).subtract(nb));
				factors[n] = denominator.add(shift);
			}
			return new Term(factors[0], factors[1], factors[2], denominator);
		}).toList();
	}

	/**
	 * Indicates whether the comparison passes: {@code E_up} exceeds a level's threshold.
	 *
	 * @param tally the adopted pairs
	 * @param level the attempt's certification level
	 * @return {@code true} if {@code E_up} is strictly greater than {@code level.threshold()}
	 * @throws NullPointerException if any of the arguments are null
	 */
	public static boolean passes(PairTally tally, SignificanceLevel level)
	{
		requireThat(level, "level").isNotNull();
		return compareAbove(tally, level.threshold()) > 0;
	}

	/**
	 * Indicates whether the candidate is below control: {@code E_down} exceeds a level's threshold.
	 *
	 * @param tally the adopted pairs
	 * @param level the attempt's certification level
	 * @return {@code true} if {@code E_down} is strictly greater than {@code level.threshold()}
	 * @throws NullPointerException if any of the arguments are null
	 */
	public static boolean isBelowControl(PairTally tally, SignificanceLevel level)
	{
		requireThat(level, "level").isNotNull();
		return compareBelow(tally, level.threshold()) > 0;
	}

	/**
	 * Returns the sign of {@code E_up - threshold}, compared exactly.
	 *
	 * @param tally     the adopted pairs
	 * @param threshold the threshold
	 * @return a negative number, zero or a positive number as {@code E_up} is less than, equal to or greater than
	 *   {@code threshold}
	 * @throws NullPointerException if any of the arguments are null
	 */
	public static int compareAbove(PairTally tally, BigInteger threshold)
	{
		return compare(ABOVE, tally, threshold);
	}

	/**
	 * Returns the sign of {@code E_down - threshold}, compared exactly.
	 *
	 * @param tally     the adopted pairs
	 * @param threshold the threshold
	 * @return a negative number, zero or a positive number as {@code E_down} is less than, equal to or greater than
	 *   {@code threshold}
	 * @throws NullPointerException if any of the arguments are null
	 */
	public static int compareBelow(PairTally tally, BigInteger threshold)
	{
		return compare(BELOW, tally, threshold);
	}

	/**
	 * Returns the sign of an e-value minus a threshold, using exact rational arithmetic.
	 *
	 * @param terms     the e-value's terms
	 * @param tally     the adopted pairs
	 * @param threshold the threshold
	 * @return the sign of the difference
	 */
	private static int compare(List<Term> terms, PairTally tally, BigInteger threshold)
	{
		requireThat(tally, "tally").isNotNull();
		requireThat(threshold, "threshold").isNotNull();
		BigInteger numerator = BigInteger.ZERO;
		BigInteger denominator = BigInteger.ONE;
		int pairs = tally.pairs();
		for (Term term : terms)
		{
			BigInteger termNumerator = term.controlAhead().pow(tally.controlAhead()).
				multiply(term.even().pow(tally.even())).
				multiply(term.candidateAhead().pow(tally.candidateAhead()));
			BigInteger termDenominator = term.denominator().pow(pairs).multiply(BigInteger.valueOf(terms.size()));
			numerator = numerator.multiply(termDenominator).add(termNumerator.multiply(denominator));
			denominator = denominator.multiply(termDenominator);
		}
		return numerator.compareTo(threshold.multiply(denominator));
	}

	/**
	 * Returns the fewest further pairs in which both trials pass, after which the comparison passes. This is the
	 * remaining work of an undecided comparison when every further trial passes.
	 *
	 * @param tally   the adopted pairs
	 * @param level   the attempt's certification level
	 * @param maximum the largest number of further pairs to consider
	 * @return the fewest further pairs in {@code [0, maximum]}, or {@code maximum + 1} if there is none
	 * @throws NullPointerException     if {@code tally} or {@code level} are null
	 * @throws IllegalArgumentException if {@code maximum} is negative
	 */
	public static int fewestEvenPairsToPass(PairTally tally, SignificanceLevel level, int maximum)
	{
		requireThat(tally, "tally").isNotNull();
		return fewestPairsToPass(level, maximum, further ->
			new PairTally(tally.controlAhead(), tally.even() + further, tally.candidateAhead()));
	}

	/**
	 * Returns the fewest further pairs in which only the candidate trial passes, after which the comparison passes.
	 * No further pairs can pass it sooner, so this bounds the control trials worth running at once.
	 *
	 * @param tally   the adopted pairs
	 * @param level   the attempt's certification level
	 * @param maximum the largest number of further pairs to consider
	 * @return the fewest further pairs in {@code [0, maximum]}, or {@code maximum + 1} if there is none
	 * @throws NullPointerException     if {@code tally} or {@code level} are null
	 * @throws IllegalArgumentException if {@code maximum} is negative
	 */
	public static int fewestCandidateAheadPairsToPass(PairTally tally, SignificanceLevel level, int maximum)
	{
		requireThat(tally, "tally").isNotNull();
		return fewestPairsToPass(level, maximum, further ->
			new PairTally(tally.controlAhead(), tally.even(), tally.candidateAhead() + further));
	}

	/**
	 * Returns the fewest further pairs after which the comparison passes, when each further pair raises
	 * {@code E_up}.
	 *
	 * @param level   the attempt's certification level
	 * @param maximum the largest number of further pairs to consider
	 * @param extend  returns the tally after a number of further pairs
	 * @return the fewest further pairs in {@code [0, maximum]}, or {@code maximum + 1} if there is none
	 */
	private static int fewestPairsToPass(SignificanceLevel level, int maximum,
		IntFunction<PairTally> extend)
	{
		requireThat(level, "level").isNotNull();
		requireThat(maximum, "maximum").isNotNegative();
		int low = 0;
		int high = maximum + 1;
		while (low < high)
		{
			int middle = (low + high) >>> 1;
			if (passes(extend.apply(middle), level))
				high = middle;
			else
				low = middle + 1;
		}
		return low;
	}

	/**
	 * One term of an e-value: a weight of {@code 1 / 4} times the product of one factor per pair. Each factor is a
	 * numerator over the shared denominator.
	 *
	 * @param controlAhead   the numerator of the factor of a pair with {@code Z = 0}
	 * @param even           the numerator of the factor of a pair with {@code Z = 1/2}
	 * @param candidateAhead the numerator of the factor of a pair with {@code Z = 1}
	 * @param denominator    the shared denominator of the factors
	 */
	private record Term(BigInteger controlAhead, BigInteger even, BigInteger candidateAhead, BigInteger denominator)
	{
	}
}
