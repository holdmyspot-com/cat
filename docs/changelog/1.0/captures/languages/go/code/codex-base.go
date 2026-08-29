// Package checkout calculates checkout totals using exact decimal arithmetic.
package checkout

import (
	"errors"
	"fmt"
	"math/big"
	"strings"
)

// ShippingMethod selects the shipping service used for a checkout.
type ShippingMethod uint8

const (
	// StandardShipping adds no shipping fee.
	StandardShipping ShippingMethod = iota
	// ExpeditedShipping adds a 15.00 fee before tax is applied.
	ExpeditedShipping
)

// CheckoutPriceCalculator calculates totals for one item.
type CheckoutPriceCalculator struct{}

// Calculate returns the final checkout total with exactly two decimal places.
//
// itemPrice, discountRate, and taxRate must be nonnegative decimal strings in
// the form digits or digits.digits. Rates must not exceed one. The calculation
// is (itemPrice * (1 - discountRate) + shippingFee) * (1 + taxRate), rounded
// to cents using round-half-to-even.
func (CheckoutPriceCalculator) Calculate(itemPrice, discountRate, taxRate string, shipping ShippingMethod) (string, error) {
	item, err := parseDecimal(itemPrice)
	if err != nil {
		return "", fmt.Errorf("invalid item price: %w", err)
	}
	discount, err := parseRate(discountRate)
	if err != nil {
		return "", fmt.Errorf("invalid discount rate: %w", err)
	}
	tax, err := parseRate(taxRate)
	if err != nil {
		return "", fmt.Errorf("invalid tax rate: %w", err)
	}

	fee := new(big.Rat)
	switch shipping {
	case StandardShipping:
	case ExpeditedShipping:
		fee.SetInt64(15)
	default:
		return "", errors.New("invalid shipping method")
	}

	one := big.NewRat(1, 1)
	discountMultiplier := new(big.Rat).Sub(one, discount)
	subtotal := new(big.Rat).Mul(item, discountMultiplier)
	subtotal.Add(subtotal, fee)
	total := subtotal.Mul(subtotal, new(big.Rat).Add(one, tax))

	return formatCents(roundHalfEven(total)), nil
}

// CalculateCheckoutPrice is a convenience wrapper around
// CheckoutPriceCalculator.Calculate.
func CalculateCheckoutPrice(itemPrice, discountRate, taxRate string, shipping ShippingMethod) (string, error) {
	return CheckoutPriceCalculator{}.Calculate(itemPrice, discountRate, taxRate, shipping)
}

func parseRate(value string) (*big.Rat, error) {
	rate, err := parseDecimal(value)
	if err != nil {
		return nil, err
	}
	if rate.Cmp(big.NewRat(1, 1)) > 0 {
		return nil, errors.New("must be between 0 and 1")
	}
	return rate, nil
}

func parseDecimal(value string) (*big.Rat, error) {
	if value == "" {
		return nil, errors.New("must not be empty")
	}

	parts := strings.Split(value, ".")
	if len(parts) > 2 || !isDigits(parts[0]) || (len(parts) == 2 && !isDigits(parts[1])) {
		return nil, errors.New("must use plain nonnegative decimal notation")
	}

	digits := parts[0]
	decimalPlaces := 0
	if len(parts) == 2 {
		digits += parts[1]
		decimalPlaces = len(parts[1])
	}

	numerator, ok := new(big.Int).SetString(digits, 10)
	if !ok {
		return nil, errors.New("is not a decimal number")
	}
	denominator := new(big.Int).Exp(big.NewInt(10), big.NewInt(int64(decimalPlaces)), nil)
	return new(big.Rat).SetFrac(numerator, denominator), nil
}

func isDigits(value string) bool {
	if value == "" {
		return false
	}
	for _, r := range value {
		if r < '0' || r > '9' {
			return false
		}
	}
	return true
}

// roundHalfEven converts a nonnegative rational amount to its nearest cent.
func roundHalfEven(amount *big.Rat) *big.Int {
	scaledNumerator := new(big.Int).Mul(amount.Num(), big.NewInt(100))
	quotient, remainder := new(big.Int), new(big.Int)
	quotient.QuoRem(scaledNumerator, amount.Denom(), remainder)

	comparison := new(big.Int).Lsh(remainder, 1).Cmp(amount.Denom())
	if comparison > 0 || (comparison == 0 && quotient.Bit(0) == 1) {
		quotient.Add(quotient, big.NewInt(1))
	}
	return quotient
}

func formatCents(cents *big.Int) string {
	dollars, remainder := new(big.Int), new(big.Int)
	dollars.QuoRem(cents, big.NewInt(100), remainder)
	return fmt.Sprintf("%s.%02d", dollars.String(), remainder.Int64())
}
