<?php

declare(strict_types=1);

use Brick\Math\BigDecimal;
use Brick\Math\RoundingMode;

enum ShippingMethod
{
    case Standard;
    case Expedited;
}

final class CheckoutPriceCalculator
{
    private const string EXPEDITED_SHIPPING_FEE = '15.00';
    private const string DECIMAL_PATTERN = '/\A\d+(?:\.\d+)?\z/D';

    /**
     * Calculates the exact checkout total for one item in the currency unit used by the item price.
     *
     * The discount applies only to the item price. Expedited shipping is then added, tax is applied,
     * and the result is rounded once to two decimal places using half-even rounding.
     *
     * @throws InvalidArgumentException if an amount or rate does not satisfy its documented format or range
     */
    public function calculate(
        string $itemPrice,
        string $discountRate,
        string $taxRate,
        ShippingMethod $shippingMethod
    ): string {
        $price = $this->parseNonnegativeDecimal(
            $itemPrice,
            'Item price must be a nonnegative decimal string in plain notation.'
        );
        $discount = $this->parseRate(
            $discountRate,
            'Discount rate must be a decimal string in the inclusive range [0, 1].'
        );
        $tax = $this->parseRate(
            $taxRate,
            'Tax rate must be a decimal string in the inclusive range [0, 1].'
        );

        $discountedPrice = $price->multipliedBy(BigDecimal::one()->minus($discount));
        $shippingFee = $shippingMethod === ShippingMethod::Expedited
            ? BigDecimal::of(self::EXPEDITED_SHIPPING_FEE)
            : BigDecimal::zero();
        $taxableSubtotal = $discountedPrice->plus($shippingFee);
        $total = $taxableSubtotal->multipliedBy(BigDecimal::one()->plus($tax));

        return (string) $total->toScale(2, RoundingMode::HalfEven);
    }

    private function parseNonnegativeDecimal(string $value, string $failureMessage): BigDecimal
    {
        if (preg_match(self::DECIMAL_PATTERN, $value) !== 1) {
            throw new InvalidArgumentException($failureMessage);
        }

        return BigDecimal::of($value);
    }

    private function parseRate(string $value, string $failureMessage): BigDecimal
    {
        $rate = $this->parseNonnegativeDecimal($value, $failureMessage);
        if ($rate->isGreaterThan(BigDecimal::one())) {
            throw new InvalidArgumentException($failureMessage);
        }

        return $rate;
    }
}
