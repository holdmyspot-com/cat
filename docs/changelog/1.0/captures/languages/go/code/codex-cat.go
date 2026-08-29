package checkout

import (
	"fmt"
	"math/big"
	"strings"
)

const (
	expeditedShippingFeeCents int64 = 1500
	centsPerUnit              int64 = 100
)

// ShippingMethod is the delivery option selected for a checkout.
type ShippingMethod string

const (
	// StandardShipping adds no shipping fee.
	StandardShipping ShippingMethod = "standard"
	// ExpeditedShipping adds a 15.00 fee before tax is calculated.
	ExpeditedShipping ShippingMethod = "expedited"
)

var one = big.NewRat(1, 1)

// CheckoutPriceCalculator calculates a checkout total for one item.
type CheckoutPriceCalculator struct{}

// Calculate returns the checkout total in one currency with exactly two decimal
// places. Inputs must use plain nonnegative decimal notation. Discount and tax
// rates are decimal fractions from zero through one, inclusive.
func (CheckoutPriceCalculator) Calculate(
	itemPrice string,
	discountRate string,
	taxRate string,
	shipping ShippingMethod,
) (string, error) {
	if shipping != StandardShipping && shipping != ExpeditedShipping {
		return "", fmt.Errorf("shipping method %q is invalid; use %q or %q", shipping, StandardShipping, ExpeditedShipping)
	}

	price, err := parsePlainDecimal("item price", itemPrice)
	if err != nil {
		return "", err
	}

	discount, err := parseRate("discount rate", discountRate)
	if err != nil {
		return "", err
	}

	tax, err := parseRate("tax rate", taxRate)
	if err != nil {
		return "", err
	}

	discountedPrice := new(big.Rat).Mul(price, new(big.Rat).Sub(one, discount))
	subtotal := new(big.Rat).Set(discountedPrice)
	if shipping == ExpeditedShipping {
		subtotal.Add(subtotal, big.NewRat(expeditedShippingFeeCents, centsPerUnit))
	}

	total := new(big.Rat).Mul(subtotal, new(big.Rat).Add(one, tax))
	return formatRoundedToCents(total), nil
}

func parseRate(name string, value string) (*big.Rat, error) {
	rate, err := parsePlainDecimal(name, value)
	if err != nil {
		return nil, err
	}
	if rate.Cmp(one) > 0 {
		return nil, fmt.Errorf("%s %q is outside the allowed range [0, 1]", name, value)
	}

	return rate, nil
}

func parsePlainDecimal(name string, value string) (*big.Rat, error) {
	if !isPlainNonnegativeDecimal(value) {
		return nil, fmt.Errorf("%s %q is invalid; use digits with an optional decimal point", name, value)
	}

	coefficientText := strings.ReplaceAll(value, ".", "")
	coefficient, ok := new(big.Int).SetString(coefficientText, 10)
	if !ok {
		return nil, fmt.Errorf("%s %q cannot be parsed", name, value)
	}

	decimalPointIndex := strings.Index(value, ".")
	decimalPlaces := 0
	if decimalPointIndex >= 0 {
		decimalPlaces = len(value) - decimalPointIndex - 1
	}

	denominator := new(big.Int).Exp(big.NewInt(10), big.NewInt(int64(decimalPlaces)), nil)
	return new(big.Rat).SetFrac(coefficient, denominator), nil
}

func isPlainNonnegativeDecimal(value string) bool {
	if value == "" {
		return false
	}

	decimalPointSeen := false
	for _, character := range value {
		switch {
		case character >= '0' && character <= '9':
		case character == '.' && !decimalPointSeen:
			decimalPointSeen = true
		default:
			return false
		}
	}

	return value[0] != '.' && value[len(value)-1] != '.'
}

func formatRoundedToCents(amount *big.Rat) string {
	numerator := new(big.Int).Mul(amount.Num(), big.NewInt(centsPerUnit))
	denominator := amount.Denom()
	cents := new(big.Int)
	remainder := new(big.Int)
	cents.QuoRem(numerator, denominator, remainder)

	doubledRemainder := new(big.Int).Lsh(remainder, 1)
	if doubledRemainder.Cmp(denominator) > 0 || (doubledRemainder.Cmp(denominator) == 0 && cents.Bit(0) == 1) {
		cents.Add(cents, big.NewInt(1))
	}

	centsText := cents.String()
	if len(centsText) < 3 {
		centsText = strings.Repeat("0", 3-len(centsText)) + centsText
	}

	return centsText[:len(centsText)-2] + "." + centsText[len(centsText)-2:]
}
