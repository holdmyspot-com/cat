#include <algorithm>
#include <cstddef>
#include <stdexcept>
#include <string>
#include <utility>
#include <vector>

class CheckoutPriceCalculator
{
public:
    enum class Shipping
    {
        Standard,
        Expedited
    };

    static std::string calculate(const std::string& itemPrice,
                                 const std::string& discountRate,
                                 const std::string& taxRate,
                                 Shipping shipping)
    {
        const Decimal price = parse(itemPrice);
        const Decimal discount = parseRate(discountRate, "discount rate");
        const Decimal tax = parseRate(taxRate, "tax rate");

        Decimal subtotal = multiply(price, subtractFromOne(discount));
        if (shipping == Shipping::Expedited)
            subtotal = add(subtotal, Decimal{BigInteger{0, 0, 5, 1}, 2});
        else if (shipping != Shipping::Standard)
            throw std::invalid_argument("invalid shipping selection");

        return roundToCents(multiply(subtotal, addToOne(tax)));
    }

    static std::string calculate(const std::string& itemPrice,
                                 const std::string& discountRate,
                                 const std::string& taxRate,
                                 bool expedited)
    {
        return calculate(itemPrice, discountRate, taxRate,
                         expedited ? Shipping::Expedited : Shipping::Standard);
    }

    static std::string calculateTotal(const std::string& itemPrice,
                                      const std::string& discountRate,
                                      const std::string& taxRate,
                                      Shipping shipping)
    {
        return calculate(itemPrice, discountRate, taxRate, shipping);
    }

    static std::string calculateTotal(const std::string& itemPrice,
                                      const std::string& discountRate,
                                      const std::string& taxRate,
                                      bool expedited)
    {
        return calculate(itemPrice, discountRate, taxRate, expedited);
    }

private:
    using BigInteger = std::vector<unsigned char>;

    struct Decimal
    {
        BigInteger coefficient;
        std::size_t scale;
    };

    static void normalize(BigInteger& value)
    {
        while (value.size() > 1 && value.back() == 0)
            value.pop_back();
    }

    static BigInteger powerOfTen(std::size_t exponent)
    {
        BigInteger result(exponent + 1, 0);
        result.back() = 1;
        return result;
    }

    static int compare(const BigInteger& left, const BigInteger& right)
    {
        if (left.size() != right.size())
            return left.size() < right.size() ? -1 : 1;
        for (std::size_t index = left.size(); index-- > 0;)
        {
            if (left[index] != right[index])
                return left[index] < right[index] ? -1 : 1;
        }
        return 0;
    }

    static BigInteger addIntegers(const BigInteger& left, const BigInteger& right)
    {
        BigInteger result(std::max(left.size(), right.size()) + 1, 0);
        unsigned carry = 0;
        for (std::size_t index = 0; index + 1 < result.size(); ++index)
        {
            const unsigned sum = carry
                + (index < left.size() ? left[index] : 0)
                + (index < right.size() ? right[index] : 0);
            result[index] = static_cast<unsigned char>(sum % 10);
            carry = sum / 10;
        }
        result.back() = static_cast<unsigned char>(carry);
        normalize(result);
        return result;
    }

    static BigInteger subtractIntegers(const BigInteger& left, const BigInteger& right)
    {
        BigInteger result(left);
        int borrow = 0;
        for (std::size_t index = 0; index < result.size(); ++index)
        {
            int difference = result[index] - borrow
                - (index < right.size() ? right[index] : 0);
            if (difference < 0)
            {
                difference += 10;
                borrow = 1;
            }
            else
            {
                borrow = 0;
            }
            result[index] = static_cast<unsigned char>(difference);
        }
        normalize(result);
        return result;
    }

    static BigInteger multiplyIntegers(const BigInteger& left, const BigInteger& right)
    {
        BigInteger result(left.size() + right.size(), 0);
        for (std::size_t leftIndex = 0; leftIndex < left.size(); ++leftIndex)
        {
            unsigned carry = 0;
            for (std::size_t rightIndex = 0; rightIndex < right.size(); ++rightIndex)
            {
                const std::size_t index = leftIndex + rightIndex;
                const unsigned product = result[index]
                    + left[leftIndex] * right[rightIndex] + carry;
                result[index] = static_cast<unsigned char>(product % 10);
                carry = product / 10;
            }
            std::size_t index = leftIndex + right.size();
            while (carry != 0)
            {
                const unsigned sum = result[index] + carry;
                result[index] = static_cast<unsigned char>(sum % 10);
                carry = sum / 10;
                ++index;
            }
        }
        normalize(result);
        return result;
    }

    static BigInteger scaleInteger(const BigInteger& value, std::size_t places)
    {
        if (value.size() == 1 && value[0] == 0)
            return value;
        BigInteger result(places, 0);
        result.insert(result.end(), value.begin(), value.end());
        return result;
    }

    static Decimal parse(const std::string& text)
    {
        if (text.empty())
            throw std::invalid_argument("decimal string is empty");

        std::string digits;
        std::size_t scale = 0;
        bool sawPoint = false;
        bool digitAfterPoint = false;
        for (char character : text)
        {
            if (character >= '0' && character <= '9')
            {
                digits.push_back(character);
                if (sawPoint)
                {
                    ++scale;
                    digitAfterPoint = true;
                }
            }
            else if (character == '.' && !sawPoint)
            {
                sawPoint = true;
            }
            else
            {
                throw std::invalid_argument("invalid decimal string");
            }
        }

        if (text.front() == '.' || (sawPoint && !digitAfterPoint))
            throw std::invalid_argument("invalid decimal string");

        BigInteger coefficient;
        coefficient.reserve(digits.size());
        for (auto iterator = digits.rbegin(); iterator != digits.rend(); ++iterator)
            coefficient.push_back(static_cast<unsigned char>(*iterator - '0'));
        normalize(coefficient);
        return {std::move(coefficient), scale};
    }

    static Decimal parseRate(const std::string& text, const char* name)
    {
        Decimal rate = parse(text);
        if (compare(rate.coefficient, powerOfTen(rate.scale)) > 0)
            throw std::invalid_argument(std::string(name) + " must be in [0, 1]");
        return rate;
    }

    static Decimal multiply(const Decimal& left, const Decimal& right)
    {
        return {multiplyIntegers(left.coefficient, right.coefficient),
                left.scale + right.scale};
    }

    static Decimal add(const Decimal& left, const Decimal& right)
    {
        const std::size_t scale = std::max(left.scale, right.scale);
        return {addIntegers(scaleInteger(left.coefficient, scale - left.scale),
                            scaleInteger(right.coefficient, scale - right.scale)),
                scale};
    }

    static Decimal subtractFromOne(const Decimal& value)
    {
        return {subtractIntegers(powerOfTen(value.scale), value.coefficient), value.scale};
    }

    static Decimal addToOne(const Decimal& value)
    {
        return {addIntegers(powerOfTen(value.scale), value.coefficient), value.scale};
    }

    static std::string roundToCents(const Decimal& value)
    {
        BigInteger cents;
        if (value.scale <= 2)
        {
            cents = scaleInteger(value.coefficient, 2 - value.scale);
        }
        else
        {
            const std::size_t discarded = value.scale - 2;
            if (discarded >= value.coefficient.size())
                cents = BigInteger{0};
            else
                cents.assign(value.coefficient.begin() + discarded, value.coefficient.end());

            const unsigned leadingDiscarded = discarded == 0 ? 0
                : (discarded - 1 < value.coefficient.size()
                       ? value.coefficient[discarded - 1]
                       : 0);
            bool lowerDigitsNonzero = false;
            for (std::size_t index = 0;
                 index + 1 < discarded && index < value.coefficient.size(); ++index)
            {
                lowerDigitsNonzero = lowerDigitsNonzero || value.coefficient[index] != 0;
            }

            const bool aboveHalf = leadingDiscarded > 5
                || (leadingDiscarded == 5 && lowerDigitsNonzero);
            const bool exactlyHalf = leadingDiscarded == 5 && !lowerDigitsNonzero;
            if (aboveHalf || (exactlyHalf && cents[0] % 2 != 0))
                cents = addIntegers(cents, BigInteger{1});
        }

        while (cents.size() < 3)
            cents.push_back(0);
        std::string result;
        result.reserve(cents.size() + 1);
        for (std::size_t index = cents.size(); index-- > 2;)
            result.push_back(static_cast<char>('0' + cents[index]));
        result.push_back('.');
        result.push_back(static_cast<char>('0' + cents[1]));
        result.push_back(static_cast<char>('0' + cents[0]));
        return result;
    }
};
