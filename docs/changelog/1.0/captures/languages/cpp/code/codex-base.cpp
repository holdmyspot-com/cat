#include <algorithm>
#include <stdexcept>
#include <string>
#include <vector>

// Prices and rates are strings so input decimal values remain exact until the
// one required rounding operation.
class CheckoutPriceCalculator
{
public:
    enum class ShippingMethod { Standard, Expedited };

    // Rates are fractions: for example, "0.1" represents ten percent.
    std::string calculate(const std::string& itemPrice,
                          const std::string& discountRate,
                          const std::string& taxRate,
                          ShippingMethod shipping) const
    {
        const Decimal price = parseDecimal(itemPrice, "item price");
        const Decimal discount = parseDecimal(discountRate, "discount rate");
        const Decimal tax = parseDecimal(taxRate, "tax rate");
        requireRate(discount, "discount rate");
        requireRate(tax, "tax rate");

        // (price * (1 - discount) + shipping) * (1 + tax)
        const std::string discounted = multiply(price.digits,
                                                subtract(powerOfTen(discount.scale), discount.digits));
        std::string subtotal = add(discounted,
                                   shipping == ShippingMethod::Expedited
                                       ? appendZeros("15", price.scale + discount.scale)
                                       : "0");
        const std::string total = multiply(subtotal,
                                           add(powerOfTen(tax.scale), tax.digits));
        return roundAndFormatCents(total, price.scale + discount.scale + tax.scale);
    }

    std::string calculate(const std::string& itemPrice,
                          const std::string& discountRate,
                          const std::string& taxRate,
                          bool expeditedShipping) const
    {
        return calculate(itemPrice, discountRate, taxRate,
                         expeditedShipping ? ShippingMethod::Expedited
                                            : ShippingMethod::Standard);
    }

private:
    struct Decimal { std::string digits; std::size_t scale; };

    static Decimal parseDecimal(const std::string& value, const char* field)
    {
        if (value.empty())
            throw std::invalid_argument(std::string(field) + " must not be empty");

        bool seenPoint = false;
        std::size_t scale = 0;
        std::string digits;
        for (std::size_t i = 0; i < value.size(); ++i)
        {
            const char ch = value[i];
            if (ch == '.')
            {
                if (seenPoint || i == 0 || i + 1 == value.size())
                    throw std::invalid_argument(std::string(field) + " is not a plain decimal");
                seenPoint = true;
            }
            else if (ch >= '0' && ch <= '9')
            {
                digits += ch;
                if (seenPoint) ++scale;
            }
            else
                throw std::invalid_argument(std::string(field) + " is not a plain decimal");
        }
        return {stripLeadingZeros(digits), scale};
    }

    static void requireRate(const Decimal& rate, const char* field)
    {
        if (compare(rate.digits, powerOfTen(rate.scale)) > 0)
            throw std::invalid_argument(std::string(field) + " must be in [0, 1]");
    }

    static std::string stripLeadingZeros(const std::string& value)
    {
        const std::size_t first = value.find_first_not_of('0');
        return first == std::string::npos ? "0" : value.substr(first);
    }

    static int compare(const std::string& left, const std::string& right)
    {
        if (left.size() != right.size()) return left.size() < right.size() ? -1 : 1;
        return left == right ? 0 : (left < right ? -1 : 1);
    }

    static std::string add(const std::string& left, const std::string& right)
    {
        std::string result;
        int carry = 0;
        for (std::size_t i = 0; i < std::max(left.size(), right.size()) || carry; ++i)
        {
            const int a = i < left.size() ? left[left.size() - 1 - i] - '0' : 0;
            const int b = i < right.size() ? right[right.size() - 1 - i] - '0' : 0;
            result += static_cast<char>('0' + (a + b + carry) % 10);
            carry = (a + b + carry) / 10;
        }
        std::reverse(result.begin(), result.end());
        return result;
    }

    // Precondition: left >= right.
    static std::string subtract(const std::string& left, const std::string& right)
    {
        std::string result;
        int borrow = 0;
        for (std::size_t i = 0; i < left.size(); ++i)
        {
            int a = left[left.size() - 1 - i] - '0' - borrow;
            const int b = i < right.size() ? right[right.size() - 1 - i] - '0' : 0;
            if (a < b) { a += 10; borrow = 1; } else borrow = 0;
            result += static_cast<char>('0' + a - b);
        }
        std::reverse(result.begin(), result.end());
        return stripLeadingZeros(result);
    }

    static std::string multiply(const std::string& left, const std::string& right)
    {
        if (left == "0" || right == "0") return "0";
        std::vector<unsigned int> result(left.size() + right.size());
        for (std::size_t i = left.size(); i-- > 0;)
        {
            unsigned int carry = 0;
            for (std::size_t j = right.size(); j-- > 0;)
            {
                const unsigned int product = result[i + j + 1] +
                    (left[i] - '0') * (right[j] - '0') + carry;
                result[i + j + 1] = product % 10;
                carry = product / 10;
            }
            result[i] += carry;
        }
        std::string text;
        std::size_t first = 0;
        while (first + 1 < result.size() && result[first] == 0) ++first;
        for (; first < result.size(); ++first) text += static_cast<char>('0' + result[first]);
        return text;
    }

    static std::string powerOfTen(std::size_t exponent) { return appendZeros("1", exponent); }
    static std::string appendZeros(const std::string& value, std::size_t count)
    {
        return value == "0" ? "0" : value + std::string(count, '0');
    }

    static void increment(std::string& value)
    {
        for (std::size_t i = value.size(); i-- > 0;)
            if (value[i] != '9') { ++value[i]; return; } else value[i] = '0';
        value.insert(value.begin(), '1');
    }

    static std::string roundAndFormatCents(const std::string& numerator, std::size_t scale)
    {
        std::string cents;
        if (scale <= 2)
            cents = appendZeros(numerator, 2 - scale);
        else
        {
            const std::size_t discarded = scale - 2;
            cents = numerator.size() > discarded
                        ? numerator.substr(0, numerator.size() - discarded) : "0";
            const char firstDiscarded = numerator.size() >= discarded
                                            ? numerator[numerator.size() - discarded] : '0';
            const bool laterDiscardedNonzero = numerator.size() > discarded &&
                numerator.find_first_not_of('0', numerator.size() - discarded + 1) != std::string::npos;
            if (firstDiscarded > '5' ||
                (firstDiscarded == '5' && (laterDiscardedNonzero || (cents.back() - '0') % 2 != 0)))
                increment(cents);
        }

        cents = stripLeadingZeros(cents);
        while (cents.size() < 3) cents.insert(cents.begin(), '0');
        return cents.substr(0, cents.size() - 2) + "." + cents.substr(cents.size() - 2);
    }
};
