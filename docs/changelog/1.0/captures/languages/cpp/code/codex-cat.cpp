#include <algorithm>
#include <stdexcept>
#include <string>
#include <vector>

enum class ShippingMethod
{
    Standard,
    Expedited
};

class CheckoutPriceCalculator
{
public:
    std::string calculate(const std::string& itemPrice,
                          const std::string& discountRate,
                          const std::string& taxRate,
                          ShippingMethod shippingMethod) const
    {
        const Decimal price = parseDecimal(itemPrice, "item price");
        const Decimal discount = parseDecimal(discountRate, "discount rate");
        const Decimal tax = parseDecimal(taxRate, "tax rate");

        validateRate(discount, "discount rate");
        validateRate(tax, "tax rate");
        validateShippingMethod(shippingMethod);

        const std::size_t priceDiscountDigits =
            price.fractionalDigits + discount.fractionalDigits;
        const std::string discountFactor =
            subtract(powerOfTen(discount.fractionalDigits), discount.value);
        const std::string itemCents = appendZeros(multiply(price.value, discountFactor), 2);
        const std::string shippingCents = shippingMethod == ShippingMethod::Expedited
            ? appendZeros("1500", priceDiscountDigits)
            : "0";
        const std::string subtotalInScaledCents = add(itemCents, shippingCents);
        const std::string taxFactor = add(powerOfTen(tax.fractionalDigits), tax.value);
        const std::string centsNumerator = multiply(subtotalInScaledCents, taxFactor);

        return formatCents(roundHalfEven(centsNumerator,
                                        priceDiscountDigits + tax.fractionalDigits));
    }

private:
    struct Decimal
    {
        std::string value;
        std::size_t fractionalDigits;
    };

    static Decimal parseDecimal(const std::string& text, const char* name)
    {
        const std::size_t point = text.find('.');
        const bool hasPoint = point != std::string::npos;
        const std::size_t integerDigits = hasPoint ? point : text.size();
        const std::size_t fractionalDigits = hasPoint ? text.size() - point - 1 : 0;

        if (integerDigits == 0 || (hasPoint && fractionalDigits == 0)) {
            throw std::invalid_argument(std::string(name) + " must be a nonnegative decimal");
        }

        std::string value;
        for (std::size_t index = 0; index < text.size(); ++index) {
            const char character = text[index];
            if (character == '.' && index == point) {
                continue;
            }
            if (character < '0' || character > '9') {
                throw std::invalid_argument(std::string(name) + " must be a nonnegative decimal");
            }
            value += character;
        }

        return {normalize(value), fractionalDigits};
    }

    static void validateRate(const Decimal& rate, const char* name)
    {
        if (compare(rate.value, powerOfTen(rate.fractionalDigits)) > 0) {
            throw std::invalid_argument(std::string(name) + " must be between 0 and 1");
        }
    }

    static void validateShippingMethod(ShippingMethod shippingMethod)
    {
        if (shippingMethod != ShippingMethod::Standard &&
            shippingMethod != ShippingMethod::Expedited) {
            throw std::invalid_argument("shipping method is invalid");
        }
    }

    static std::string powerOfTen(std::size_t exponent)
    {
        return "1" + std::string(exponent, '0');
    }

    static std::string normalize(const std::string& value)
    {
        const std::size_t first = value.find_first_not_of('0');
        return first == std::string::npos ? "0" : value.substr(first);
    }

    static int compare(const std::string& left, const std::string& right)
    {
        const std::string normalizedLeft = normalize(left);
        const std::string normalizedRight = normalize(right);
        if (normalizedLeft.size() != normalizedRight.size()) {
            return normalizedLeft.size() < normalizedRight.size() ? -1 : 1;
        }
        return normalizedLeft.compare(normalizedRight);
    }

    static std::string add(const std::string& left, const std::string& right)
    {
        std::string result;
        int carry = 0;
        std::size_t leftIndex = left.size();
        std::size_t rightIndex = right.size();
        while (leftIndex > 0 || rightIndex > 0 || carry != 0) {
            const int leftDigit = leftIndex > 0 ? left[--leftIndex] - '0' : 0;
            const int rightDigit = rightIndex > 0 ? right[--rightIndex] - '0' : 0;
            const int sum = leftDigit + rightDigit + carry;
            result += static_cast<char>('0' + sum % 10);
            carry = sum / 10;
        }
        std::reverse(result.begin(), result.end());
        return normalize(result);
    }

    static std::string subtract(const std::string& larger, const std::string& smaller)
    {
        std::string result;
        int borrow = 0;
        std::size_t largerIndex = larger.size();
        std::size_t smallerIndex = smaller.size();
        while (largerIndex > 0) {
            int digit = larger[--largerIndex] - '0' - borrow;
            const int subtrahend = smallerIndex > 0 ? smaller[--smallerIndex] - '0' : 0;
            if (digit < subtrahend) {
                digit += 10;
                borrow = 1;
            } else {
                borrow = 0;
            }
            result += static_cast<char>('0' + digit - subtrahend);
        }
        std::reverse(result.begin(), result.end());
        return normalize(result);
    }

    static std::string multiply(const std::string& left, const std::string& right)
    {
        if (left == "0" || right == "0") {
            return "0";
        }
        std::vector<int> digits(left.size() + right.size());
        for (std::size_t leftOffset = 0; leftOffset < left.size(); ++leftOffset) {
            const std::size_t leftIndex = left.size() - 1 - leftOffset;
            for (std::size_t rightOffset = 0; rightOffset < right.size(); ++rightOffset) {
                const std::size_t rightIndex = right.size() - 1 - rightOffset;
                digits[leftOffset + rightOffset] +=
                    (left[leftIndex] - '0') * (right[rightIndex] - '0');
            }
        }
        for (std::size_t index = 0; index + 1 < digits.size(); ++index) {
            digits[index + 1] += digits[index] / 10;
            digits[index] %= 10;
        }
        std::string result;
        for (std::vector<int>::reverse_iterator it = digits.rbegin(); it != digits.rend(); ++it) {
            result += static_cast<char>('0' + *it);
        }
        return normalize(result);
    }

    static std::string appendZeros(const std::string& value, std::size_t count)
    {
        return value == "0" ? "0" : value + std::string(count, '0');
    }

    static std::string roundHalfEven(const std::string& numerator, std::size_t decimalDigits)
    {
        if (decimalDigits == 0) {
            return numerator;
        }
        const std::string padded = numerator.size() <= decimalDigits
            ? std::string(decimalDigits + 1 - numerator.size(), '0') + numerator
            : numerator;
        std::string quotient = padded.substr(0, padded.size() - decimalDigits);
        const char firstDiscarded = padded[padded.size() - decimalDigits];

        if (firstDiscarded > '5' ||
            (firstDiscarded == '5' &&
             (padded.find_first_not_of('0', padded.size() - decimalDigits + 1) != std::string::npos ||
              (quotient.back() - '0') % 2 != 0))) {
            quotient = add(quotient, "1");
        }
        return normalize(quotient);
    }

    static std::string formatCents(const std::string& cents)
    {
        const std::string padded = cents.size() < 3 ? std::string(3 - cents.size(), '0') + cents : cents;
        return padded.substr(0, padded.size() - 2) + "." + padded.substr(padded.size() - 2);
    }
};
