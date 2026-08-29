using System;
using System.Globalization;
using System.Numerics;

public sealed class CheckoutPriceCalculator
{
    public string Calculate(
        string itemPrice,
        string discountRate,
        string taxRate,
        bool expeditedShipping)
    {
        ExactDecimal price = ExactDecimal.Parse(itemPrice, nameof(itemPrice));
        ExactDecimal discount = ExactDecimal.Parse(discountRate, nameof(discountRate));
        ExactDecimal tax = ExactDecimal.Parse(taxRate, nameof(taxRate));

        EnsureRate(discount, nameof(discountRate));
        EnsureRate(tax, nameof(taxRate));

        ExactDecimal discountedPrice = price * (ExactDecimal.One - discount);
        ExactDecimal subtotal = expeditedShipping
            ? discountedPrice + ExactDecimal.Fifteen
            : discountedPrice;
        ExactDecimal total = subtotal * (ExactDecimal.One + tax);

        return total.ToFixedTwoHalfEven();
    }

    private static void EnsureRate(ExactDecimal rate, string parameterName)
    {
        if (rate > ExactDecimal.One)
        {
            throw new ArgumentOutOfRangeException(
                parameterName,
                "The rate must be in the inclusive range [0, 1].");
        }
    }

    private readonly struct ExactDecimal : IComparable<ExactDecimal>
    {
        public static readonly ExactDecimal One = new ExactDecimal(BigInteger.One, 0);
        public static readonly ExactDecimal Fifteen = new ExactDecimal(new BigInteger(15), 0);

        private ExactDecimal(BigInteger coefficient, int scale)
        {
            while (scale > 0 && coefficient % 10 == 0)
            {
                coefficient /= 10;
                scale--;
            }

            Coefficient = coefficient;
            Scale = scale;
        }

        private BigInteger Coefficient { get; }
        private int Scale { get; }

        public static ExactDecimal Parse(string value, string parameterName)
        {
            ArgumentNullException.ThrowIfNull(value, parameterName);

            if (value.Length == 0)
            {
                throw InvalidDecimal(parameterName);
            }

            int decimalPoint = -1;
            for (int i = 0; i < value.Length; i++)
            {
                char character = value[i];
                if (character == '.')
                {
                    if (decimalPoint >= 0 || i == 0 || i == value.Length - 1)
                    {
                        throw InvalidDecimal(parameterName);
                    }

                    decimalPoint = i;
                }
                else if (character < '0' || character > '9')
                {
                    throw InvalidDecimal(parameterName);
                }
            }

            int scale = decimalPoint < 0 ? 0 : value.Length - decimalPoint - 1;
            string digits = decimalPoint < 0 ? value : value.Remove(decimalPoint, 1);
            BigInteger coefficient = BigInteger.Parse(digits, CultureInfo.InvariantCulture);
            return new ExactDecimal(coefficient, scale);
        }

        public static ExactDecimal operator +(ExactDecimal left, ExactDecimal right)
        {
            int scale = Math.Max(left.Scale, right.Scale);
            BigInteger leftCoefficient = left.Coefficient * PowerOfTen(scale - left.Scale);
            BigInteger rightCoefficient = right.Coefficient * PowerOfTen(scale - right.Scale);
            return new ExactDecimal(leftCoefficient + rightCoefficient, scale);
        }

        public static ExactDecimal operator -(ExactDecimal left, ExactDecimal right)
        {
            int scale = Math.Max(left.Scale, right.Scale);
            BigInteger leftCoefficient = left.Coefficient * PowerOfTen(scale - left.Scale);
            BigInteger rightCoefficient = right.Coefficient * PowerOfTen(scale - right.Scale);
            return new ExactDecimal(leftCoefficient - rightCoefficient, scale);
        }

        public static ExactDecimal operator *(ExactDecimal left, ExactDecimal right)
        {
            return new ExactDecimal(
                left.Coefficient * right.Coefficient,
                checked(left.Scale + right.Scale));
        }

        public static bool operator >(ExactDecimal left, ExactDecimal right) =>
            left.CompareTo(right) > 0;

        public static bool operator <(ExactDecimal left, ExactDecimal right) =>
            left.CompareTo(right) < 0;

        public int CompareTo(ExactDecimal other)
        {
            int scale = Math.Max(Scale, other.Scale);
            return (Coefficient * PowerOfTen(scale - Scale)).CompareTo(
                other.Coefficient * PowerOfTen(scale - other.Scale));
        }

        public string ToFixedTwoHalfEven()
        {
            BigInteger cents;
            if (Scale <= 2)
            {
                cents = Coefficient * PowerOfTen(2 - Scale);
            }
            else
            {
                BigInteger divisor = PowerOfTen(Scale - 2);
                cents = BigInteger.DivRem(Coefficient, divisor, out BigInteger remainder);
                int midpointComparison = (remainder * 2).CompareTo(divisor);

                if (midpointComparison > 0 ||
                    (midpointComparison == 0 && !cents.IsEven))
                {
                    cents++;
                }
            }

            BigInteger whole = BigInteger.DivRem(cents, 100, out BigInteger fractional);
            return string.Concat(
                whole.ToString(CultureInfo.InvariantCulture),
                ".",
                fractional.ToString("D2", CultureInfo.InvariantCulture));
        }

        private static BigInteger PowerOfTen(int exponent) => BigInteger.Pow(10, exponent);

        private static FormatException InvalidDecimal(string parameterName) =>
            new FormatException(
                $"{parameterName} must be a nonnegative decimal containing digits and at most one decimal point.");
    }
}
