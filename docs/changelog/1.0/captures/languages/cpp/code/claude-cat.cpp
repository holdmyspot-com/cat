#include <boost/multiprecision/cpp_int.hpp>

#include <cstddef>
#include <limits>
#include <stdexcept>
#include <string>
#include <string_view>

// Shipping is a closed choice so callers cannot accidentally request an
// unsupported shipping mode.
enum class Shipping
{
    Standard,
    Expedited
};

class CheckoutPriceCalculator
{
public:
    // Calculates one checkout total without rounding any intermediate value.
    // Invalid decimal notation and rates outside [0, 1] are rejected.
    static std::string calculate(std::string_view itemPrice,
                                 std::string_view discountRate,
                                 std::string_view taxRate,
                                 Shipping shipping)
    {
        if (shipping != Shipping::Standard && shipping != Shipping::Expedited)
        {
            throw std::invalid_argument("shipping must be standard or expedited");
        }

        const Decimal price = parseDecimal(itemPrice, "item price");
        const Decimal discount = parseRate(discountRate, "discount rate");
        const Decimal tax = parseRate(taxRate, "tax rate");

        const std::size_t discountedScale = addScales(price.scale, discount.scale);
        const Integer discountDenominator = powerOfTen(discount.scale);
        Integer subtotal = price.coefficient * (discountDenominator - discount.coefficient);
        if (shipping == Shipping::Expedited)
        {
            subtotal += expeditedShippingFee * powerOfTen(discountedScale);
        }

        const std::size_t totalScale = addScales(discountedScale, tax.scale);
        const Integer taxDenominator = powerOfTen(tax.scale);
        const Integer total = subtotal * (taxDenominator + tax.coefficient);

        return formatRoundedCents(total, totalScale);
    }

private:
    using Integer = boost::multiprecision::cpp_int;

    struct Decimal
    {
        Integer coefficient;
        std::size_t scale;
    };

    static constexpr unsigned expeditedShippingFee = 15;

    static Decimal parseDecimal(std::string_view text, const char* parameterName)
    {
        if (text.empty())
        {
            throw std::invalid_argument(std::string(parameterName) +
                                        " must contain decimal digits");
        }

        Integer coefficient = 0;
        std::size_t scale = 0;
        bool decimalPointSeen = false;
        std::size_t digitsAfterPoint = 0;
        for (const char character : text)
        {
            if (character == '.')
            {
                if (decimalPointSeen || digitsAfterPoint != 0)
                {
                    throwInvalidDecimal(parameterName);
                }
                decimalPointSeen = true;
                continue;
            }
            if (character < '0' || character > '9')
            {
                throwInvalidDecimal(parameterName);
            }

            coefficient *= 10;
            coefficient += character - '0';
            if (decimalPointSeen)
            {
                ++digitsAfterPoint;
            }
        }

        if ((decimalPointSeen && digitsAfterPoint == 0) || text.front() == '.')
        {
            throwInvalidDecimal(parameterName);
        }
        scale = digitsAfterPoint;

        return {coefficient, scale};
    }

    static Decimal parseRate(std::string_view text, const char* parameterName)
    {
        Decimal rate = parseDecimal(text, parameterName);
        if (rate.coefficient > powerOfTen(rate.scale))
        {
            throw std::invalid_argument(std::string(parameterName) +
                                        " must be in the inclusive range [0, 1]");
        }

        return rate;
    }

    [[noreturn]] static void throwInvalidDecimal(const char* parameterName)
    {
        throw std::invalid_argument(std::string(parameterName) +
                                    " must use plain nonnegative decimal notation");
    }

    static std::size_t addScales(std::size_t left, std::size_t right)
    {
        if (right > std::numeric_limits<std::size_t>::max() - left)
        {
            throw std::length_error("decimal input scale is too large");
        }

        return left + right;
    }

    static Integer powerOfTen(std::size_t exponent)
    {
        Integer result = 1;
        Integer factor = 10;
        while (exponent != 0)
        {
            if ((exponent & 1U) != 0)
            {
                result *= factor;
            }
            exponent >>= 1U;
            if (exponent != 0)
            {
                factor *= factor;
            }
        }

        return result;
    }

    static std::string formatRoundedCents(Integer value, std::size_t scale)
    {
        Integer cents;
        if (scale <= 2)
        {
            cents = value * powerOfTen(2 - scale);
        }
        else
        {
            const Integer discardedUnit = powerOfTen(scale - 2);
            cents = value / discardedUnit;
            const Integer remainder = value % discardedUnit;
            const bool aboveHalf = remainder * 2 > discardedUnit;
            const bool halfWithOddCent = remainder * 2 == discardedUnit &&
                                         (cents & 1) != 0;
            if (aboveHalf || halfWithOddCent)
            {
                ++cents;
            }
        }

        std::string digits = cents.convert_to<std::string>();
        if (digits.size() < 3)
        {
            digits.insert(0, 3 - digits.size(), '0');
        }
        digits.insert(digits.size() - 2, 1, '.');

        return digits;
    }
};
