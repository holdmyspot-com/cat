using System;
using System.Numerics;

public enum ShippingMethod
{
    Standard,
    Expedited
}

public sealed class CheckoutPriceCalculator
{
    private const int ExpeditedShippingFee = 15;

    /// <summary>
    /// Calculates a checkout total, rounded to cents using midpoint-to-even rounding.
    /// </summary>
    public string Calculate(
        string itemPrice,
        string discountRate,
        string taxRate,
        bool expeditedShipping)
    {
        return Calculate(
            itemPrice,
            discountRate,
            taxRate,
            expeditedShipping ? ShippingMethod.Expedited : ShippingMethod.Standard);
    }

    /// <summary>
    /// Calculates a checkout total, rounded to cents using midpoint-to-even rounding.
    /// </summary>
    public string Calculate(
        string itemPrice,
        string discountRate,
        string taxRate,
        ShippingMethod shippingMethod)
    {
        DecimalValue price = ParseDecimal(itemPrice, nameof(itemPrice));
        DecimalValue discount = ParseRate(discountRate, nameof(discountRate));
        DecimalValue tax = ParseRate(taxRate, nameof(taxRate));

        if (shippingMethod != ShippingMethod.Standard && shippingMethod != ShippingMethod.Expedited)
            throw new ArgumentOutOfRangeException(nameof(shippingMethod));

        // All input denominators are powers of ten.  Keeping a single denominator
        // avoids any loss of precision before the one required rounding operation.
        BigInteger discountScale = PowerOfTen(discount.Scale);
        BigInteger taxScale = PowerOfTen(tax.Scale);
        BigInteger priceAndDiscountScale = PowerOfTen(price.Scale + discount.Scale);

        BigInteger subtotalNumerator = price.UnscaledValue * (discountScale - discount.UnscaledValue);
        if (shippingMethod == ShippingMethod.Expedited)
            subtotalNumerator += ExpeditedShippingFee * priceAndDiscountScale;

        BigInteger totalNumerator = subtotalNumerator * (taxScale + tax.UnscaledValue);
        BigInteger totalDenominator = priceAndDiscountScale * taxScale;

        return FormatCents(RoundHalfEven(totalNumerator * 100, totalDenominator));
    }

    private static DecimalValue ParseRate(string value, string parameterName)
    {
        DecimalValue rate = ParseDecimal(value, parameterName);
        if (rate.UnscaledValue > PowerOfTen(rate.Scale))
            throw new ArgumentOutOfRangeException(parameterName, "Rates must be between 0 and 1 inclusive.");

        return rate;
    }

    private static DecimalValue ParseDecimal(string value, string parameterName)
    {
        if (value == null)
            throw new ArgumentNullException(parameterName);
        if (value.Length == 0)
            throw new FormatException($"{parameterName} must be a plain nonnegative decimal string.");

        int decimalPoint = -1;
        for (int index = 0; index < value.Length; index++)
        {
            char character = value[index];
            if (character == '.')
            {
                if (decimalPoint >= 0 || index == 0 || index == value.Length - 1)
                    throw new FormatException($"{parameterName} must be a plain nonnegative decimal string.");
                decimalPoint = index;
            }
            else if (character < '0' || character > '9')
            {
                throw new FormatException($"{parameterName} must be a plain nonnegative decimal string.");
            }
        }

        string digits = decimalPoint < 0 ? value : value.Remove(decimalPoint, 1);
        int scale = decimalPoint < 0 ? 0 : value.Length - decimalPoint - 1;
        return new DecimalValue(BigInteger.Parse(digits), scale);
    }

    private static BigInteger RoundHalfEven(BigInteger numerator, BigInteger denominator)
    {
        BigInteger quotient = BigInteger.DivRem(numerator, denominator, out BigInteger remainder);
        int comparison = (remainder * 2).CompareTo(denominator);

        if (comparison > 0 || (comparison == 0 && !quotient.IsEven))
            return quotient + BigInteger.One;

        return quotient;
    }

    private static string FormatCents(BigInteger cents)
    {
        string value = cents.ToString();
        if (value.Length == 1)
            return "0.0" + value;
        if (value.Length == 2)
            return "0." + value;
        return value.Insert(value.Length - 2, ".");
    }

    private static BigInteger PowerOfTen(int exponent)
    {
        return BigInteger.Pow(10, exponent);
    }

    private readonly struct DecimalValue
    {
        public DecimalValue(BigInteger unscaledValue, int scale)
        {
            UnscaledValue = unscaledValue;
            Scale = scale;
        }

        public BigInteger UnscaledValue { get; }
        public int Scale { get; }
    }
}
