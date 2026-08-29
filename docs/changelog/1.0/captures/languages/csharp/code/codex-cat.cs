using System;
using System.Globalization;
using System.Numerics;

public enum ShippingMethod
{
    Standard,
    Expedited
}

/// <summary>
/// Calculates checkout totals for a single item in one implicit currency.
/// All inputs are nonnegative plain decimal strings. Rates are fractions in [0, 1].
/// </summary>
public sealed class CheckoutPriceCalculator
{
    private static readonly ExactDecimal ExpeditedShippingFee = new ExactDecimal(new BigInteger(15), BigInteger.One);

    /// <summary>
    /// Applies the item discount, adds expedited shipping when selected, applies tax, and returns the total rounded
    /// to two decimal places using half-even rounding.
    /// </summary>
    public static string Calculate(
        string itemPrice,
        string discountRate,
        string taxRate,
        ShippingMethod shippingMethod)
    {
        ValidateShippingMethod(shippingMethod);

        ExactDecimal price = ParseNonnegativeDecimal(itemPrice, nameof(itemPrice));
        ExactDecimal discount = ParseRate(discountRate, nameof(discountRate));
        ExactDecimal tax = ParseRate(taxRate, nameof(taxRate));

        ExactDecimal discountedItemPrice = price.Multiply(ExactDecimal.One.Subtract(discount));
        ExactDecimal shippingFee = shippingMethod == ShippingMethod.Expedited
            ? ExpeditedShippingFee
            : ExactDecimal.Zero;
        ExactDecimal subtotal = discountedItemPrice.Add(shippingFee);
        ExactDecimal totalBeforeRounding = subtotal.Multiply(ExactDecimal.One.Add(tax));

        return FormatRoundedToCents(totalBeforeRounding);
    }

    private static ExactDecimal ParseRate(string value, string parameterName)
    {
        ExactDecimal rate = ParseNonnegativeDecimal(value, parameterName);
        if (rate.CompareTo(ExactDecimal.One) > 0)
        {
            throw new ArgumentOutOfRangeException(parameterName, "Rates must be between 0 and 1 inclusive.");
        }

        return rate;
    }

    private static ExactDecimal ParseNonnegativeDecimal(string value, string parameterName)
    {
        if (value == null)
        {
            throw new ArgumentNullException(parameterName);
        }

        int decimalPointIndex = value.IndexOf('.');
        if (value.Length == 0 || decimalPointIndex == 0 || decimalPointIndex == value.Length - 1 ||
            (decimalPointIndex >= 0 && value.IndexOf('.', decimalPointIndex + 1) >= 0))
        {
            throw new ArgumentException("Value must be a plain nonnegative decimal string.", parameterName);
        }

        BigInteger unscaledValue = BigInteger.Zero;
        for (int index = 0; index < value.Length; index++)
        {
            char character = value[index];
            if (character == '.')
            {
                continue;
            }

            if (character < '0' || character > '9')
            {
                throw new ArgumentException("Value must be a plain nonnegative decimal string.", parameterName);
            }

            unscaledValue = unscaledValue * 10 + (character - '0');
        }

        int fractionalDigits = decimalPointIndex < 0 ? 0 : value.Length - decimalPointIndex - 1;
        return new ExactDecimal(unscaledValue, BigInteger.Pow(10, fractionalDigits));
    }

    private static void ValidateShippingMethod(ShippingMethod shippingMethod)
    {
        if (shippingMethod != ShippingMethod.Standard && shippingMethod != ShippingMethod.Expedited)
        {
            throw new ArgumentOutOfRangeException(nameof(shippingMethod), "Shipping method must be standard or expedited.");
        }
    }

    private static string FormatRoundedToCents(ExactDecimal amount)
    {
        BigInteger centsNumerator = amount.Numerator * 100;
        BigInteger cents = BigInteger.DivRem(centsNumerator, amount.Denominator, out BigInteger remainder);
        int comparisonWithHalf = (remainder * 2).CompareTo(amount.Denominator);
        if (comparisonWithHalf > 0 || (comparisonWithHalf == 0 && !cents.IsEven))
        {
            cents += BigInteger.One;
        }

        BigInteger wholeUnits = BigInteger.DivRem(cents, 100, out BigInteger fractionalCents);
        return wholeUnits.ToString(CultureInfo.InvariantCulture) + "." +
            fractionalCents.ToString("D2", CultureInfo.InvariantCulture);
    }

    private sealed class ExactDecimal
    {
        internal static readonly ExactDecimal Zero = new ExactDecimal(BigInteger.Zero, BigInteger.One);
        internal static readonly ExactDecimal One = new ExactDecimal(BigInteger.One, BigInteger.One);

        internal ExactDecimal(BigInteger numerator, BigInteger denominator)
        {
            Numerator = numerator;
            Denominator = denominator;
        }

        internal BigInteger Numerator { get; }

        internal BigInteger Denominator { get; }

        internal ExactDecimal Add(ExactDecimal other)
        {
            return new ExactDecimal(
                Numerator * other.Denominator + other.Numerator * Denominator,
                Denominator * other.Denominator);
        }

        internal int CompareTo(ExactDecimal other)
        {
            return (Numerator * other.Denominator).CompareTo(other.Numerator * Denominator);
        }

        internal ExactDecimal Multiply(ExactDecimal other)
        {
            return new ExactDecimal(Numerator * other.Numerator, Denominator * other.Denominator);
        }

        internal ExactDecimal Subtract(ExactDecimal other)
        {
            return new ExactDecimal(
                Numerator * other.Denominator - other.Numerator * Denominator,
                Denominator * other.Denominator);
        }
    }
}
