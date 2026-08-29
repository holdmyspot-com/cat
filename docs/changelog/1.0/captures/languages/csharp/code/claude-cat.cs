using System;
using System.Globalization;
using System.Numerics;

public sealed class CheckoutPriceCalculator
{
    private static readonly BigInteger ExpeditedShippingFee = new BigInteger(15);

    /// <summary>
    /// Calculates the checkout total for one item.
    /// </summary>
    /// <param name="itemPrice">
    /// The nonnegative item price as digits with at most one decimal point.
    /// </param>
    /// <param name="discountRate">
    /// The discount fraction in the inclusive range from 0 to 1, in the same decimal format.
    /// </param>
    /// <param name="taxRate">
    /// The tax fraction in the inclusive range from 0 to 1, in the same decimal format.
    /// </param>
    /// <param name="expeditedShipping">
    /// <see langword="true"/> to add a 15.00 shipping fee after the discount and before tax;
    /// otherwise, <see langword="false"/> for standard shipping with no fee.
    /// </param>
    /// <returns>
    /// The total as an exact decimal string with two decimal places, rounded once using half-even rounding.
    /// </returns>
    /// <exception cref="ArgumentNullException">An input string is <see langword="null"/>.</exception>
    /// <exception cref="FormatException">
    /// An input string is not plain nonnegative decimal notation.
    /// </exception>
    /// <exception cref="ArgumentOutOfRangeException">
    /// A discount or tax rate is greater than 1.
    /// </exception>
    public static string Calculate(
        string itemPrice,
        string discountRate,
        string taxRate,
        bool expeditedShipping)
    {
        ExactDecimal price = ParseExactDecimal(itemPrice, nameof(itemPrice));
        ExactDecimal discount = ParseRate(discountRate, nameof(discountRate));
        ExactDecimal tax = ParseRate(taxRate, nameof(taxRate));

        BigInteger discountScale = PowerOfTen(discount.Scale);
        BigInteger priceScale = PowerOfTen(price.Scale);
        BigInteger subtotalDenominator = priceScale * discountScale;
        BigInteger subtotalNumerator = price.Coefficient * (discountScale - discount.Coefficient);

        if (expeditedShipping)
        {
            subtotalNumerator += ExpeditedShippingFee * subtotalDenominator;
        }

        BigInteger taxScale = PowerOfTen(tax.Scale);
        BigInteger totalNumerator = subtotalNumerator * (taxScale + tax.Coefficient);
        BigInteger totalDenominator = subtotalDenominator * taxScale;

        return FormatRoundedCents(totalNumerator, totalDenominator);
    }

    private static ExactDecimal ParseRate(string value, string parameterName)
    {
        ExactDecimal rate = ParseExactDecimal(value, parameterName);

        if (rate.Coefficient > PowerOfTen(rate.Scale))
        {
            throw new ArgumentOutOfRangeException(
                parameterName,
                value,
                "The rate must be in the inclusive range from 0 to 1.");
        }

        return rate;
    }

    private static ExactDecimal ParseExactDecimal(string value, string parameterName)
    {
        if (value == null)
        {
            throw new ArgumentNullException(parameterName);
        }

        int decimalPoint = -1;
        for (int index = 0; index < value.Length; index++)
        {
            char character = value[index];
            if (character == '.')
            {
                if (decimalPoint >= 0)
                {
                    throw InvalidDecimal(parameterName, value);
                }

                decimalPoint = index;
            }
            else if (character < '0' || character > '9')
            {
                throw InvalidDecimal(parameterName, value);
            }
        }

        if (value.Length == 0 || decimalPoint == 0 || decimalPoint == value.Length - 1)
        {
            throw InvalidDecimal(parameterName, value);
        }

        string digits = decimalPoint < 0
            ? value
            : value.Remove(decimalPoint, 1);
        int scale = decimalPoint < 0 ? 0 : value.Length - decimalPoint - 1;
        BigInteger coefficient = BigInteger.Parse(digits, NumberStyles.None, CultureInfo.InvariantCulture);

        return new ExactDecimal(coefficient, scale);
    }

    private static FormatException InvalidDecimal(string parameterName, string value)
    {
        return new FormatException(
            $"{parameterName} must contain digits with an optional decimal point; received '{value}'.");
    }

    private static string FormatRoundedCents(BigInteger numerator, BigInteger denominator)
    {
        BigInteger wholeCents = BigInteger.DivRem(numerator * 100, denominator, out BigInteger remainder);
        int midpointComparison = (remainder * 2).CompareTo(denominator);
        bool roundsUp = midpointComparison > 0 || (midpointComparison == 0 && !wholeCents.IsEven);

        if (roundsUp)
        {
            wholeCents++;
        }

        BigInteger wholeUnits = BigInteger.DivRem(wholeCents, 100, out BigInteger cents);
        return string.Concat(
            wholeUnits.ToString(CultureInfo.InvariantCulture),
            ".",
            cents.ToString("D2", CultureInfo.InvariantCulture));
    }

    private static BigInteger PowerOfTen(int exponent)
    {
        return BigInteger.Pow(10, exponent);
    }

    private readonly struct ExactDecimal
    {
        public ExactDecimal(BigInteger coefficient, int scale)
        {
            Coefficient = coefficient;
            Scale = scale;
        }

        public BigInteger Coefficient { get; }

        public int Scale { get; }
    }
}
