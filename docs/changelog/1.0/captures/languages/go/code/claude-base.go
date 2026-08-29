package checkout

import (
	"errors"
	"fmt"
	"math/big"
	"strings"
)

var (
	// ErrInvalidDecimal indicates that an input is not in plain nonnegative
	// decimal notation.
	ErrInvalidDecimal = errors.New("invalid decimal")
	// ErrRateOutOfRange indicates that a discount or tax rate is outside [0, 1].
	ErrRateOutOfRange = errors.New("rate must be between 0 and 1 inclusive")
)

// CheckoutPriceCalculator calculates checkout totals without using
// floating-point arithmetic.
type CheckoutPriceCalculator struct{}

// Calculate returns the checkout total for one item. The expedited fee is
// applied after the discount and before tax.
func (CheckoutPriceCalculator) Calculate(itemPrice, discountRate, taxRate string, expedited bool) (string, error) {
	return CalculateCheckoutPrice(itemPrice, discountRate, taxRate, expedited)
}

// CalculateCheckoutPrice returns the checkout total for one item. Inputs must
// be plain nonnegative decimals, and rates must be in the inclusive range
// [0, 1]. The result is rounded to cents using half-even rounding.
func CalculateCheckoutPrice(itemPrice, discountRate, taxRate string, expedited bool) (string, error) {
	price, err := parseDecimal(itemPrice)
	if err != nil {
		return "", fmt.Errorf("item price: %w", err)
	}

	discount, err := parseRate(discountRate)
	if err != nil {
		return "", fmt.Errorf("discount rate: %w", err)
	}

	tax, err := parseRate(taxRate)
	if err != nil {
		return "", fmt.Errorf("tax rate: %w", err)
	}

	one := big.NewRat(1, 1)
	total := new(big.Rat).Mul(price, new(big.Rat).Sub(one, discount))
	if expedited {
		total.Add(total, big.NewRat(15, 1))
	}
	total.Mul(total, new(big.Rat).Add(one, tax))

	return formatCentsHalfEven(total), nil
}

func parseRate(value string) (*big.Rat, error) {
	rate, err := parseDecimal(value)
	if err != nil {
		return nil, err
	}
	if rate.Cmp(big.NewRat(1, 1)) > 0 {
		return nil, ErrRateOutOfRange
	}
	return rate, nil
}

func parseDecimal(value string) (*big.Rat, error) {
	if value == "" {
		return nil, ErrInvalidDecimal
	}

	dot := -1
	for i := 0; i < len(value); i++ {
		switch {
		case value[i] >= '0' && value[i] <= '9':
		case value[i] == '.' && dot == -1:
			dot = i
		default:
			return nil, ErrInvalidDecimal
		}
	}
	if dot == 0 || dot == len(value)-1 {
		return nil, ErrInvalidDecimal
	}

	digits := value
	scale := 0
	if dot >= 0 {
		digits = value[:dot] + value[dot+1:]
		scale = len(value) - dot - 1
	}

	numerator, ok := new(big.Int).SetString(digits, 10)
	if !ok {
		return nil, ErrInvalidDecimal
	}
	denominator := new(big.Int).Exp(big.NewInt(10), big.NewInt(int64(scale)), nil)
	return new(big.Rat).SetFrac(numerator, denominator), nil
}

func formatCentsHalfEven(value *big.Rat) string {
	scaledNumerator := new(big.Int).Mul(value.Num(), big.NewInt(100))
	cents, remainder := new(big.Int).QuoRem(scaledNumerator, value.Denom(), new(big.Int))

	twiceRemainder := new(big.Int).Lsh(new(big.Int).Set(remainder), 1)
	comparison := twiceRemainder.Cmp(value.Denom())
	if comparison > 0 || comparison == 0 && cents.Bit(0) == 1 {
		cents.Add(cents, big.NewInt(1))
	}

	digits := cents.String()
	if len(digits) < 3 {
		digits = strings.Repeat("0", 3-len(digits)) + digits
	}
	return digits[:len(digits)-2] + "." + digits[len(digits)-2:]
}
