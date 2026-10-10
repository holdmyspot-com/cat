package io.github.cowwoc.cat.common.optimizer.statistics;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.that;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

/**
 * One side of an anytime-valid betting test of the pass probability {@code p} of a cell, one prompt test under one
 * trial profile, against a null rate {@code p_0}, with the fixed bets and weights.
 * <p>
 * With {@code S} adopted passes and {@code F} adopted failures:
 * <ul>
 *   <li>{@link Side#ABOVE} tests the null {@code p <= p_0} with
 *     {@code E_up(S, F) = sum_m w_m (1 - c_m + c_m / p_0)^S (1 - c_m)^F}, where
 *     {@code (c, w) = (0.4, 0.1), (0.6, 0.2), (0.8, 0.5), (1.0, 0.2)}. The {@code c = 1} term vanishes after the
 *     first failure.</li>
 *   <li>{@link Side#BELOW} tests the null {@code p >= p_0} with
 *     {@code E_down(S, F) = sum_m (1 / 3) (1 - lambda_m)^S (1 + lambda_m p_0 / (1 - p_0))^F}. For the campaign's
 *     minimum pass rate {@code p_min}, let {@code n_short = 9, 3, 1} be the number of shortfalls {@code 1 - p_min}
 *     that an alternative pass rate lies below {@code p_min}. Each bet {@code lambda_m = min(1, n_short (1 - p_min)
 *     / p_min)} is growth-optimal against the pass rate {@code p_min - n_short (1 - p_min)}. A bet of 1 makes its
 *     term vanish after the first pass.</li>
 * </ul>
 * Under its null, each e-value is a nonnegative supermartingale with initial value 1, so by Ville's inequality it
 * exceeds a threshold {@code T} with probability at most {@code 1 / T} under every stopping rule. {@code E_up}
 * increases with {@code S} and decreases with {@code F} and {@code p_0}; {@code E_down} does the opposite. By Jensen's
 * inequality, {@code E_up <= 1} whenever {@code S / (S + F) <= p_0}, and {@code E_down <= 1} whenever
 * {@code S / (S + F) >= p_0}, so the two sides never both exceed a threshold above 1.
 *
 * <p><b>Exact decisions and the floating-point shortcut</b>
 * <p>
 * {@link #exceeds(int, int, SignificanceLevel)} decides {@code E > T} exactly; equality never decides. The exact
 * comparison multiplies out the rational terms. To avoid that cost away from the boundary, the process first
 * compares logarithms in double precision and decides from them only when their difference exceeds the tolerance
 * {@code delta = (n + 1)(M + log T + 4) 2^-46}, where {@code n = S + F} and {@code M} is 1 plus the largest magnitude
 * among the computed logarithms of the weights and nonzero factors. The shortcut is used only when every weight and
 * factor has a numerator and denominator of at most 53 bits, so that each converts to {@code double} exactly.
 * <p>
 * Proof that the shortcut never decides wrongly, with unit roundoff {@code u = 2^-53}:
 * <ol>
 *   <li>Each weight or factor {@code x = N / D} is computed as {@code fl(N / D) = x (1 + e)} with {@code |e| <= u}.
 *     {@code Math.log} is within 1 ulp, at most {@code 2u} times its result's magnitude, which is at most
 *     {@code M}. Each computed logarithm is therefore within {@code (2M + 3)u} of the exact one.</li>
 *   <li>Each term's exponent {@code log w + S log P + F log Q} is computed as
 *     {@code fl(fl(w' + fl(S p')) + fl(F q'))}. The inherited error is at most {@code (n + 1)(2M + 3)u}, and the
 *     three roundings add at most {@code 3u (n + 1) M (1 + 2u)}, so each exponent is within
 *     {@code 5(n + 1)(M + 1)u}. A term whose factor is zero is exactly zero and is skipped exactly.</li>
 *   <li>Log-sum-exp is 1-Lipschitz in the maximum norm, so its exact value at the computed exponents is within
 *     {@code 5(n + 1)(M + 1)u} of {@code log E}. It is evaluated as {@code m + log(sum exp(d))} with
 *     {@code d = exponent - m <= 0}. The largest exponent contributes exactly 1, so the sum {@code Z} is at least 1.
 *     Rounding each {@code d} perturbs its summand by at most {@code u |d| e^d <= u / e}, {@code Math.exp} adds at
 *     most {@code 2u e^d}, and the three additions at most {@code 3uZ}, so {@code Z} has relative error at most
 *     {@code 7u}. {@code Math.log} adds at most {@code 2u log 4 < 3u}, and the final addition at most
 *     {@code u (|m| + 2)}. Because {@code |m| <= 1.01 (n + 1) M}, the evaluation error is at most
 *     {@code u ((n + 1) M 1.01 + 13)}.</li>
 *   <li>For {@code T < 2^53}, {@code Math.log(T)} is within {@code 2u log T}. A wider {@code T} is shifted right
 *     by {@code k} bits to a 53-bit {@code T'}, which changes its logarithm by at most {@code 2u}; then
 *     {@code log T' + k log 2} is within {@code u (6 log T + 2)}.</li>
 *   <li>The computed difference is therefore within {@code (n + 1)(7M + 20)u + u (6 log T + 2)} of
 *     {@code log E - log T}, which is less than half of {@code delta = 128 (n + 1)(M + log T + 4)u}. The final
 *     subtraction's relative rounding of at most {@code u} cannot close the remaining gap. If the computed difference
 *     exceeds {@code delta}, then {@code E > T}; if it is below {@code -delta}, then {@code E < T}.</li>
 * </ol>
 * Otherwise, and whenever the shortcut is ineligible, the exact comparison decides.
 * <p>
 * <b>Thread Safety:</b> This class is immutable and thread-safe.
 */
public final class BettingProcess
{
	/**
	 * The largest bit length of an integer that a {@code double} represents exactly.
	 */
	private static final int EXACT_DOUBLE_BITS = 53;
	/**
	 * {@code 2^-46}, which is 128 times the unit roundoff.
	 */
	private static final double TOLERANCE_UNIT = 0x1.0p-46;
	/**
	 * The natural logarithm of 2.
	 */
	private static final double LOG_TWO = Math.log(2);
	/**
	 * The certification bets {@code c_m}, as numerator and denominator pairs.
	 */
	private static final long[][] ABOVE_BETS = {{2, 5}, {3, 5}, {4, 5}, {1, 1}};
	/**
	 * The weights {@code w_m} of the certification bets, as numerator and denominator pairs.
	 */
	private static final long[][] ABOVE_WEIGHTS = {{1, 10}, {1, 5}, {1, 2}, {1, 5}};
	/**
	 * The number of shortfalls {@code 1 - p_min} that each below-minimum bet's alternative pass rate lies below the
	 * minimum pass rate {@code p_min}. The bet against that alternative is
	 * {@code min(1, n_short (1 - p_min) / p_min)}, where {@code n_short} is that number.
	 */
	private static final long[] SHORTFALL_MULTIPLES = {9, 3, 1};

	private final Side side;
	private final BigDecimal nullRate;
	private final List<ExactTerm> exactTerms;
	private final List<LogTerm> logTerms;
	/**
	 * {@code true} if every weight and factor converts to {@code double} exactly.
	 */
	private final boolean shortcutEligible;
	/**
	 * 1 plus the largest magnitude among the finite logarithms in {@link #logTerms}.
	 */
	private final double logMagnitude;

	/**
	 * Returns the certification process, which tests the null {@code p <= nullRate}.
	 *
	 * @param nullRate the null pass rate
	 * @return the process
	 * @throws NullPointerException     if {@code nullRate} is null
	 * @throws IllegalArgumentException if {@code nullRate} is not strictly between 0 and 1
	 */
	public static BettingProcess above(BigDecimal nullRate)
	{
		requireThat(nullRate, "nullRate").isGreaterThan(BigDecimal.ZERO).isLessThan(BigDecimal.ONE);
		return new BettingProcess(Side.ABOVE, nullRate, aboveTerms(Fraction.of(nullRate)));
	}

	/**
	 * Returns the below-minimum process, which tests the null {@code p >= nullRate} with the bets of a minimum pass
	 * rate.
	 *
	 * @param nullRate        the null pass rate
	 * @param minimumPassRate the campaign's minimum pass rate {@code p_min}, which selects the bets
	 * @return the process
	 * @throws NullPointerException     if any of the arguments are null
	 * @throws IllegalArgumentException if {@code nullRate} or {@code minimumPassRate} are not strictly between 0 and 1
	 */
	public static BettingProcess below(BigDecimal nullRate, BigDecimal minimumPassRate)
	{
		requireThat(nullRate, "nullRate").isGreaterThan(BigDecimal.ZERO).isLessThan(BigDecimal.ONE);
		requireThat(minimumPassRate, "minimumPassRate").isGreaterThan(BigDecimal.ZERO).isLessThan(BigDecimal.ONE);
		return new BettingProcess(Side.BELOW, nullRate,
			belowTerms(Fraction.of(nullRate), Fraction.of(minimumPassRate)));
	}

	/**
	 * Creates a process.
	 *
	 * @param side       the side of the null hypothesis that the process tests
	 * @param nullRate   the null pass rate
	 * @param exactTerms the terms of the e-value
	 */
	private BettingProcess(Side side, BigDecimal nullRate, List<ExactTerm> exactTerms)
	{
		this.side = side;
		this.nullRate = nullRate;
		this.exactTerms = exactTerms;
		List<LogTerm> logs = new ArrayList<>(exactTerms.size());
		boolean eligible = true;
		double largestLog = 0;
		for (ExactTerm term : exactTerms)
		{
			LogTerm log = new LogTerm(term.weight().log(), term.pass().log(), term.failure().log());
			logs.add(log);
			eligible &= term.weight().fitsDouble() && term.pass().fitsDouble() && term.failure().fitsDouble();
			largestLog = Math.max(largestLog, log.largestFiniteMagnitude());
		}
		this.logTerms = List.copyOf(logs);
		this.shortcutEligible = eligible;
		this.logMagnitude = 1 + largestLog;
	}

	/**
	 * Returns the certification terms for the null rate {@code p_0 = a / b}.
	 * <p>
	 * The pass factor {@code 1 - c + c / p_0} equals {@code (a (q - p) + p b) / (q a)} for {@code c = p / q}.
	 *
	 * @param rate the null rate
	 * @return the terms
	 */
	private static List<ExactTerm> aboveTerms(Fraction rate)
	{
		BigInteger a = rate.numerator();
		BigInteger b = rate.denominator();
		List<ExactTerm> terms = new ArrayList<>(ABOVE_BETS.length);
		for (int m = 0; m < ABOVE_BETS.length; ++m)
		{
			BigInteger p = BigInteger.valueOf(ABOVE_BETS[m][0]);
			BigInteger q = BigInteger.valueOf(ABOVE_BETS[m][1]);
			Fraction weight = Fraction.of(BigInteger.valueOf(ABOVE_WEIGHTS[m][0]),
				BigInteger.valueOf(ABOVE_WEIGHTS[m][1]));
			Fraction pass = Fraction.of(a.multiply(q.subtract(p)).add(p.multiply(b)), q.multiply(a));
			Fraction failure = Fraction.of(q.subtract(p), q);
			terms.add(new ExactTerm(weight, pass, failure));
		}
		return List.copyOf(terms);
	}

	/**
	 * Returns the below-minimum terms for the null rate {@code p_0 = a / b}.
	 * <p>
	 * The failure factor {@code 1 + lambda p_0 / (1 - p_0)} equals {@code (q (b - a) + p a) / (q (b - a))} for
	 * {@code lambda = p / q}.
	 *
	 * @param rate            the null rate
	 * @param minimumPassRate the minimum pass rate, which selects the bets
	 * @return the terms
	 */
	private static List<ExactTerm> belowTerms(Fraction rate, Fraction minimumPassRate)
	{
		BigInteger a = rate.numerator();
		BigInteger complement = rate.denominator().subtract(a);
		List<Fraction> bets = belowBets(minimumPassRate);
		Fraction weight = Fraction.of(BigInteger.ONE, BigInteger.valueOf(bets.size()));
		List<ExactTerm> terms = new ArrayList<>(bets.size());
		for (Fraction bet : bets)
		{
			BigInteger p = bet.numerator();
			BigInteger q = bet.denominator();
			Fraction pass = Fraction.of(q.subtract(p), q);
			Fraction failure = Fraction.of(q.multiply(complement).add(p.multiply(a)), q.multiply(complement));
			terms.add(new ExactTerm(weight, pass, failure));
		}
		return List.copyOf(terms);
	}

	/**
	 * Returns the below-minimum bets of a minimum pass rate {@code p_min = a / b}:
	 * {@code min(1, n_short (1 - p_min) / p_min)} for each number of shortfalls {@code n_short}, which equal
	 * {@code min(1, n_short (b - a) / a)}.
	 *
	 * @param minimumPassRate the minimum pass rate
	 * @return the bets, each in {@code (0, 1]}
	 */
	private static List<Fraction> belowBets(Fraction minimumPassRate)
	{
		BigInteger a = minimumPassRate.numerator();
		BigInteger complement = minimumPassRate.denominator().subtract(a);
		List<Fraction> bets = new ArrayList<>(SHORTFALL_MULTIPLES.length);
		for (long shortfallMultiple : SHORTFALL_MULTIPLES)
		{
			BigInteger numerator = complement.multiply(BigInteger.valueOf(shortfallMultiple));
			if (numerator.compareTo(a) >= 0)
				bets.add(Fraction.of(BigInteger.ONE, BigInteger.ONE));
			else
				bets.add(Fraction.of(numerator, a));
		}
		return bets;
	}

	/**
	 * Indicates whether the e-value after the given outcomes exceeds a level's threshold, compared exactly.
	 *
	 * @param passes   the number of adopted passes {@code S}
	 * @param failures the number of adopted failures {@code F}
	 * @param level    the level
	 * @return {@code true} if the e-value is strictly greater than {@code level.threshold()}
	 * @throws NullPointerException     if {@code level} is null
	 * @throws IllegalArgumentException if {@code passes} or {@code failures} are negative
	 */
	public boolean exceeds(int passes, int failures, SignificanceLevel level)
	{
		requireThat(passes, "passes").isNotNegative();
		requireThat(failures, "failures").isNotNegative();
		requireThat(level, "level").isNotNull();
		return compare(passes, failures, level.threshold()) > 0;
	}

	/**
	 * Returns the natural logarithm of the e-value after the given outcomes, for display.
	 *
	 * @param passes   the number of adopted passes {@code S}
	 * @param failures the number of adopted failures {@code F}
	 * @return the logarithm, accurate to the tolerance proven in the class description
	 * @throws IllegalArgumentException if {@code passes} or {@code failures} are negative
	 */
	public double logValue(int passes, int failures)
	{
		requireThat(passes, "passes").isNotNegative();
		requireThat(failures, "failures").isNotNegative();
		return logSum(logTerms, passes, failures);
	}

	/**
	 * Returns the natural logarithm of the certification e-value at a floating-point null rate, for display bounds.
	 *
	 * @param nullRate the null rate, strictly between 0 and 1
	 * @param passes   the number of adopted passes {@code S}
	 * @param failures the number of adopted failures {@code F}
	 * @return the logarithm
	 */
	static double logAboveValue(double nullRate, int passes, int failures)
	{
		assert that(nullRate, "nullRate").isBetween(0.0, false, 1.0, false).elseThrow();
		double odds = (1 - nullRate) / nullRate;
		List<LogTerm> terms = new ArrayList<>(ABOVE_BETS.length);
		for (int m = 0; m < ABOVE_BETS.length; ++m)
		{
			double bet = (double) ABOVE_BETS[m][0] / ABOVE_BETS[m][1];
			double weight = (double) ABOVE_WEIGHTS[m][0] / ABOVE_WEIGHTS[m][1];
			terms.add(new LogTerm(Math.log(weight), Math.log1p(bet * odds), Math.log1p(-bet)));
		}
		return logSum(terms, passes, failures);
	}

	/**
	 * Returns the natural logarithm of the below-minimum e-value at a floating-point null rate, for display bounds.
	 *
	 * @param nullRate        the null rate, strictly between 0 and 1
	 * @param minimumPassRate the campaign's minimum pass rate {@code p_min}, which selects the bets
	 * @param passes          the number of adopted passes {@code S}
	 * @param failures        the number of adopted failures {@code F}
	 * @return the logarithm
	 */
	static double logBelowValue(double nullRate, BigDecimal minimumPassRate, int passes, int failures)
	{
		assert that(nullRate, "nullRate").isBetween(0.0, false, 1.0, false).elseThrow();
		double odds = nullRate / (1 - nullRate);
		List<Fraction> bets = belowBets(Fraction.of(minimumPassRate));
		double weight = 1.0 / bets.size();
		List<LogTerm> terms = new ArrayList<>(bets.size());
		for (Fraction fraction : bets)
		{
			double bet = fraction.numerator().doubleValue() / fraction.denominator().doubleValue();
			terms.add(new LogTerm(Math.log(weight), Math.log1p(-bet), Math.log1p(bet * odds)));
		}
		return logSum(terms, passes, failures);
	}

	/**
	 * Returns the logarithm of {@code sum_m w_m P_m^S Q_m^F} by log-sum-exp.
	 *
	 * @param terms    the logarithms of each term's weight and factors
	 * @param passes   the number of passes {@code S}
	 * @param failures the number of failures {@code F}
	 * @return the logarithm, or negative infinity if every term is zero
	 */
	private static double logSum(List<LogTerm> terms, int passes, int failures)
	{
		double[] exponents = new double[terms.size()];
		double largest = Double.NEGATIVE_INFINITY;
		for (int m = 0; m < exponents.length; ++m)
		{
			LogTerm term = terms.get(m);
			// A zero factor has a logarithm of negative infinity, which must not meet a zero count
			double exponent = term.weight();
			if (passes > 0)
				exponent += passes * term.pass();
			if (failures > 0)
				exponent += failures * term.failure();
			exponents[m] = exponent;
			largest = Math.max(largest, exponent);
		}
		if (largest == Double.NEGATIVE_INFINITY)
			return largest;

		double sum = 0;
		for (double exponent : exponents)
			sum += Math.exp(exponent - largest);
		return largest + Math.log(sum);
	}

	/**
	 * Returns the sign of {@code E - threshold}, using the proven floating-point shortcut when it decides.
	 *
	 * @param passes    the number of passes {@code S}
	 * @param failures  the number of failures {@code F}
	 * @param threshold the threshold {@code T}
	 * @return a negative number, zero or a positive number as {@code E} is less than, equal to or greater than
	 *   {@code threshold}
	 */
	public int compare(int passes, int failures, BigInteger threshold)
	{
		if (shortcutEligible)
		{
			double logThreshold = log(threshold);
			double difference = logSum(logTerms, passes, failures) - logThreshold;
			double tolerance = ((double) passes + failures + 1) * (logMagnitude + logThreshold + 4) * TOLERANCE_UNIT;
			if (difference > tolerance)
				return 1;
			if (difference < -tolerance)
				return -1;
		}
		return compareExactly(passes, failures, threshold);
	}

	/**
	 * Returns the sign of {@code E - threshold}, using exact rational arithmetic.
	 *
	 * @param passes    the number of passes {@code S}
	 * @param failures  the number of failures {@code F}
	 * @param threshold the threshold {@code T}
	 * @return a negative number, zero or a positive number as {@code E} is less than, equal to or greater than
	 *   {@code threshold}
	 */
	public int compareExactly(int passes, int failures, BigInteger threshold)
	{
		BigInteger numerator = BigInteger.ZERO;
		BigInteger denominator = BigInteger.ONE;
		for (ExactTerm term : exactTerms)
		{
			// BigInteger.ZERO.pow(0) is 1, so the vanishing certainty bet needs no special case
			BigInteger termNumerator = term.weight().numerator().
				multiply(term.pass().numerator().pow(passes)).
				multiply(term.failure().numerator().pow(failures));
			BigInteger termDenominator = term.weight().denominator().
				multiply(term.pass().denominator().pow(passes)).
				multiply(term.failure().denominator().pow(failures));
			numerator = numerator.multiply(termDenominator).add(termNumerator.multiply(denominator));
			denominator = denominator.multiply(termDenominator);
		}
		return numerator.compareTo(threshold.multiply(denominator));
	}

	/**
	 * Returns the fewest passes at which the certification e-value exceeds a level's threshold.
	 *
	 * @param failures      the number of failures {@code F}
	 * @param level         the level
	 * @param maximumPasses the largest number of passes to consider
	 * @return the smallest {@code S} in {@code [0, maximumPasses]} at which the e-value exceeds the threshold, or
	 *   {@code maximumPasses + 1} if there is none
	 */
	int findMinimumExceedingPasses(int failures, SignificanceLevel level, int maximumPasses)
	{
		assert that(side, "side").isEqualTo(Side.ABOVE).elseThrow();
		int low = 0;
		int high = maximumPasses + 1;
		while (low < high)
		{
			int middle = (low + high) / 2;
			if (compare(middle, failures, level.threshold()) > 0)
				high = middle;
			else
				low = middle + 1;
		}
		return low;
	}

	/**
	 * Returns the most passes at which the below-minimum e-value exceeds a level's threshold.
	 *
	 * @param failures      the number of failures {@code F}
	 * @param level         the level
	 * @param maximumPasses the largest number of passes to consider
	 * @return the largest {@code S} in {@code [0, maximumPasses]} at which the e-value exceeds the threshold, or
	 *   {@code -1} if there is none
	 */
	int findMaximumExceedingPasses(int failures, SignificanceLevel level, int maximumPasses)
	{
		assert that(side, "side").isEqualTo(Side.BELOW).elseThrow();
		int low = -1;
		int high = maximumPasses;
		while (low < high)
		{
			int middle = (low + high + 1) / 2;
			if (compare(middle, failures, level.threshold()) > 0)
				low = middle;
			else
				high = middle - 1;
		}
		return low;
	}

	/**
	 * Returns the natural logarithm of a positive integer of any size.
	 *
	 * @param value the integer
	 * @return its logarithm
	 */
	static double log(BigInteger value)
	{
		int shift = value.bitLength() - EXACT_DOUBLE_BITS;
		if (shift <= 0)
			return Math.log(value.doubleValue());
		return Math.log(value.shiftRight(shift).doubleValue()) + shift * LOG_TWO;
	}

	@Override
	public String toString()
	{
		return "BettingProcess(side: " + side + ", nullRate: " + nullRate + ")";
	}

	/**
	 * The side of a null hypothesis about a pass probability.
	 */
	public enum Side
	{
		/**
		 * For a null rate {@code p_0}, tests the null {@code p <= p_0}; exceeding a threshold shows that the pass
		 * probability is above {@code p_0}.
		 */
		ABOVE,
		/**
		 * For a null rate {@code p_0}, tests the null {@code p >= p_0}; exceeding a threshold shows that the pass
		 * probability is below {@code p_0}.
		 */
		BELOW
	}

	/**
	 * A nonnegative rational number in lowest terms.
	 *
	 * @param numerator   the nonnegative numerator
	 * @param denominator the positive denominator
	 */
	private record Fraction(BigInteger numerator, BigInteger denominator)
	{
		/**
		 * Returns the fraction equal to a decimal.
		 *
		 * @param value a positive decimal
		 * @return the fraction in lowest terms
		 */
		static Fraction of(BigDecimal value)
		{
			BigInteger unscaled = value.unscaledValue();
			int scale = value.scale();
			if (scale < 0)
				return of(unscaled.multiply(BigInteger.TEN.pow(-scale)), BigInteger.ONE);
			return of(unscaled, BigInteger.TEN.pow(scale));
		}

		/**
		 * Returns a fraction in lowest terms.
		 *
		 * @param numerator   the nonnegative numerator
		 * @param denominator the positive denominator
		 * @return the fraction in lowest terms
		 */
		static Fraction of(BigInteger numerator, BigInteger denominator)
		{
			BigInteger divisor = numerator.gcd(denominator);
			return new Fraction(numerator.divide(divisor), denominator.divide(divisor));
		}

		/**
		 * Indicates whether the numerator and the denominator convert to {@code double} exactly.
		 *
		 * @return {@code true} if both have at most 53 bits
		 */
		boolean fitsDouble()
		{
			return numerator.bitLength() <= EXACT_DOUBLE_BITS && denominator.bitLength() <= EXACT_DOUBLE_BITS;
		}

		/**
		 * Returns the natural logarithm of the fraction.
		 *
		 * @return the logarithm, or negative infinity for zero
		 */
		double log()
		{
			if (numerator.signum() == 0)
				return Double.NEGATIVE_INFINITY;
			if (fitsDouble())
				return Math.log(numerator.doubleValue() / denominator.doubleValue());
			return BettingProcess.log(numerator) - BettingProcess.log(denominator);
		}
	}

	/**
	 * One term {@code w P^S Q^F} of an e-value, in exact rationals.
	 *
	 * @param weight  the weight {@code w}
	 * @param pass    the factor {@code P} applied for each pass
	 * @param failure the factor {@code Q} applied for each failure
	 */
	private record ExactTerm(Fraction weight, Fraction pass, Fraction failure)
	{
	}

	/**
	 * The natural logarithms of one term's weight and factors.
	 *
	 * @param weight  the logarithm of the weight
	 * @param pass    the logarithm of the factor applied for each pass, negative infinity for a zero factor
	 * @param failure the logarithm of the factor applied for each failure, negative infinity for a zero factor
	 */
	private record LogTerm(double weight, double pass, double failure)
	{
		/**
		 * Returns the largest magnitude among the finite logarithms.
		 *
		 * @return the magnitude
		 */
		double largestFiniteMagnitude()
		{
			double largest = Math.abs(weight);
			if (Double.isFinite(pass))
				largest = Math.max(largest, Math.abs(pass));
			if (Double.isFinite(failure))
				largest = Math.max(largest, Math.abs(failure));
			return largest;
		}
	}
}
