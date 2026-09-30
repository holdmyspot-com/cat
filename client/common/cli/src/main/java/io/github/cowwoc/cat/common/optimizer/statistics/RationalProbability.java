package io.github.cowwoc.cat.common.optimizer.statistics;

import java.math.BigDecimal;
import java.math.BigInteger;

/**
 * An immutable exact rational probability.
 *
 * @param numerator the nonnegative numerator
 * @param denominator the positive denominator
 */
public record RationalProbability(BigInteger numerator, BigInteger denominator)
	implements Comparable<RationalProbability>
{
	/**
	 * Converts a decimal probability without a binary floating-point round trip.
	 *
	 * @param value decimal probability between zero and one
	 * @return the same probability in reduced rational form
	 * @throws IllegalArgumentException if the value is outside the probability range
	 */
	public static RationalProbability fromDecimalExact(BigDecimal value)
	{
		if (value == null)
			throw new NullPointerException("value");
		if (value.compareTo(BigDecimal.ZERO) < 0 || value.compareTo(BigDecimal.ONE) > 0)
			throw new IllegalArgumentException("value must be between zero and one");
		BigDecimal reduced = value.stripTrailingZeros();
		return new RationalProbability(reduced.unscaledValue(), BigInteger.TEN.pow(reduced.scale()));
	}

	/**
	 * Converts a finite binary floating-point probability to its exact rational value.
	 *
	 * @param value a finite probability between zero and one
	 * @return the exact value represented by {@code value}
	 * @throws IllegalArgumentException if {@code value} is outside the probability range or nonfinite
	 */
	static RationalProbability fromDoubleExact(double value)
	{
		if (!Double.isFinite(value) || value < 0.0 || value > 1.0)
			throw new IllegalArgumentException("value must be finite and between zero and one");

		long bits = Double.doubleToLongBits(value);
		int encodedExponent = (int) ((bits >>> 52) & 0x7ffL);
		long significand = bits & 0x000f_ffff_ffff_ffffL;
		int exponent;
		if (encodedExponent == 0)
			exponent = 1 - 1023 - 52;
		else
		{
			significand |= 1L << 52;
			exponent = encodedExponent - 1023 - 52;
		}
		BigInteger exactNumerator = BigInteger.valueOf(significand);
		BigInteger exactDenominator = BigInteger.ONE;
		if (exponent >= 0)
			exactNumerator = exactNumerator.shiftLeft(exponent);
		else
			exactDenominator = exactDenominator.shiftLeft(-exponent);
		return new RationalProbability(exactNumerator, exactDenominator);
	}

	/**
	 * Normalizes a probability to reduced form.
	 */
	public RationalProbability
	{
		if (numerator == null || denominator == null)
			throw new NullPointerException("numerator and denominator must not be null");
		if (denominator.signum() <= 0)
			throw new IllegalArgumentException("denominator must be positive");
		if (numerator.signum() < 0 || numerator.compareTo(denominator) > 0)
			throw new IllegalArgumentException("probability must be between zero and one");
		BigInteger divisor = numerator.gcd(denominator);
		numerator = numerator.divide(divisor);
		denominator = denominator.divide(divisor);
	}

	/**
	 * Returns the sum of this probability and another probability.
	 *
	 * @param other the probability to add
	 * @return the reduced exact sum
	 */
	public RationalProbability add(RationalProbability other)
	{
		if (other == null)
			throw new NullPointerException("other must not be null");
		return new RationalProbability(numerator.multiply(other.denominator).
			add(other.numerator.multiply(denominator)), denominator.multiply(other.denominator));
	}

	/**
	 * Converts to the greatest representable double that does not exceed this exact probability.
	 *
	 * @return a conservative binary approximation
	 */
	double toDoubleFloor()
	{
		double result = numerator.doubleValue() / denominator.doubleValue();
		while (compareDouble(result) > 0)
			result = Math.nextDown(result);
		return result;
	}

	/**
	 * Converts to the least representable double that is not less than this exact probability.
	 *
	 * @return a conservative upper binary approximation
	 */
	double toDoubleCeiling()
	{
		double result = numerator.doubleValue() / denominator.doubleValue();
		while (compareDouble(result) < 0)
			result = Math.nextUp(result);
		return result;
	}

	/**
	 * Compares an exactly decoded nonnegative double with this exact probability.
	 *
	 * @param value the finite value to compare
	 * @return negative, zero, or positive as the double is less than, equal to, or greater than this value
	 */
	int compareDouble(double value)
	{
		if (!Double.isFinite(value) || value < 0.0)
			throw new IllegalArgumentException("value must be finite and nonnegative");
		if (value > 1.0)
			return 1;
		return fromDoubleExact(value).compareTo(this);
	}

	/**
	 * Compares this probability to another probability exactly.
	 *
	 * @param other the probability to compare
	 * @return a negative value, zero, or a positive value as this value is less than, equal to, or greater than other
	 */
	@Override
	public int compareTo(RationalProbability other)
	{
		if (other == null)
			throw new NullPointerException("other must not be null");
		return numerator.multiply(other.denominator).compareTo(other.numerator.multiply(denominator));
	}
}
