package checkout

import (
	"errors"
	"fmt"
	"math/big"
)

var expeditedShippingFee = mustParseDecimal("15.00")

// ShippingMethod selects whether the checkout uses standard or expedited shipping.
type ShippingMethod string

const (
	StandardShipping  ShippingMethod = "standard"
	ExpeditedShipping ShippingMethod = "expedited"
)

// CheckoutPriceCalculator calculates the total for one item without losing decimal precision.
type CheckoutPriceCalculator struct{}

// Calculate returns the checkout total with exactly two decimal places, rounded
// half-even. It discounts only the item price, adds 15.00 for expedited
// shipping, and then applies tax. Amounts and rates must use digits with an
// optional fractional part; rates must be between zero and one inclusive.
func (CheckoutPriceCalculator) Calculate(
	itemPrice string,
	discountRate string,
	taxRate string,
	shippingMethod ShippingMethod,
) (string, error) {
	price, err := parseDecimal("item price", itemPrice)
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

	shippingFee, err := feeForShippingMethod(shippingMethod)
	if err != nil {
		return "", err
	}

	one := big.NewRat(1, 1)
	priceAfterDiscount := new(big.Rat).Mul(price, new(big.Rat).Sub(one, discount))
	taxableSubtotal := new(big.Rat).Add(priceAfterDiscount, shippingFee)
	finalTotal := new(big.Rat).Mul(taxableSubtotal, new(big.Rat).Add(one, tax))

	return formatCentsHalfEven(finalTotal), nil
}

func parseRate(name string, value string) (*big.Rat, error) {
	rate, err := parseDecimal(name, value)
	if err != nil {
		return nil, err
	}
	if rate.Cmp(big.NewRat(1, 1)) > 0 {
		return nil, fmt.Errorf("%s %q is outside the inclusive range [0, 1]", name, value)
	}

	return rate, nil
}

func parseDecimal(name string, value string) (*big.Rat, error) {
	if !isPlainNonnegativeDecimal(value) {
		return nil, fmt.Errorf("%s %q must contain digits with an optional decimal point and fractional digits", name, value)
	}

	decimal, ok := new(big.Rat).SetString(value)
	if !ok {
		return nil, fmt.Errorf("%s %q cannot be represented as an exact decimal", name, value)
	}

	return decimal, nil
}

func isPlainNonnegativeDecimal(value string) bool {
	if value == "" {
		return false
	}

	digitSeen := false
	decimalPointSeen := false
	digitAfterDecimalPoint := false
	for _, character := range value {
		switch {
		case character >= '0' && character <= '9':
			digitSeen = true
			if decimalPointSeen {
				digitAfterDecimalPoint = true
			}
		case character == '.' && !decimalPointSeen && digitSeen:
			decimalPointSeen = true
		default:
			return false
		}
	}

	return digitSeen && (!decimalPointSeen || digitAfterDecimalPoint)
}

func feeForShippingMethod(shippingMethod ShippingMethod) (*big.Rat, error) {
	switch shippingMethod {
	case StandardShipping:
		return new(big.Rat), nil
	case ExpeditedShipping:
		return new(big.Rat).Set(expeditedShippingFee), nil
	default:
		return nil, fmt.Errorf("shipping method %q must be %q or %q", shippingMethod, StandardShipping, ExpeditedShipping)
	}
}

func mustParseDecimal(value string) *big.Rat {
	decimal, ok := new(big.Rat).SetString(value)
	if !ok {
		panic(errors.New("invalid exact decimal constant: " + value))
	}

	return decimal
}

func formatCentsHalfEven(amount *big.Rat) string {
	scaledNumerator := new(big.Int).Mul(amount.Num(), big.NewInt(100))
	wholeCents, remainder := new(big.Int).QuoRem(scaledNumerator, amount.Denom(), new(big.Int))
	twiceRemainder := new(big.Int).Lsh(remainder, 1)
	comparison := twiceRemainder.Cmp(amount.Denom())
	if comparison > 0 || comparison == 0 && wholeCents.Bit(0) == 1 {
		wholeCents.Add(wholeCents, big.NewInt(1))
	}

	dollars, cents := new(big.Int).QuoRem(wholeCents, big.NewInt(100), new(big.Int))
	return fmt.Sprintf("%s.%02d", dollars.String(), cents.Int64())
}
