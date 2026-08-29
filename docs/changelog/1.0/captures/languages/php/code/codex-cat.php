<?php

declare(strict_types=1);

final class CheckoutPriceCalculator
{
    private const EXPEDITED_SHIPPING_FEE = ['digits' => '1500', 'scale' => 2];

    /**
     * Calculates a two-decimal checkout total for one item in one currency.
     *
     * The price and rates must be nonnegative plain decimal strings. Rates are
     * decimal fractions from 0 through 1. Shipping is either "standard" or
     * "expedited"; expedited shipping adds 15.00 after the item discount and
     * before tax. The returned amount is rounded once, to two decimal places,
     * using half-even rounding.
     *
     * @throws InvalidArgumentException when an input is not in its required form or range
     */
    public function calculate(
        string $itemPrice,
        string $discountRate,
        string $taxRate,
        string $shipping
    ): string {
        $price = $this->parseNonnegativeDecimal($itemPrice, 'item price');
        $discount = $this->parseRate($discountRate, 'discount rate');
        $tax = $this->parseRate($taxRate, 'tax rate');

        if ($shipping !== 'standard' && $shipping !== 'expedited') {
            throw new InvalidArgumentException(
                'Shipping must be "standard" or "expedited"; received "' . $shipping . '".'
            );
        }

        $discountedPrice = $this->multiply($price, $this->subtract($this->one(), $discount));
        $shippingFee = $shipping === 'expedited' ? self::EXPEDITED_SHIPPING_FEE : $this->zero();
        $subtotal = $this->add($discountedPrice, $shippingFee);
        $totalBeforeRounding = $this->multiply($subtotal, $this->add($this->one(), $tax));

        return $this->formatTwoPlacesHalfEven($totalBeforeRounding);
    }

    /** @return array{digits: string, scale: int} */
    private function parseRate(string $value, string $name): array
    {
        $rate = $this->parseNonnegativeDecimal($value, $name);

        if ($this->compare($rate, $this->one()) > 0) {
            throw new InvalidArgumentException(
                ucfirst($name) . ' must be between 0 and 1 inclusive; received "' . $value . '".'
            );
        }

        return $rate;
    }

    /** @return array{digits: string, scale: int} */
    private function parseNonnegativeDecimal(string $value, string $name): array
    {
        if (preg_match('/^\d+(?:\.\d+)?$/D', $value) !== 1) {
            throw new InvalidArgumentException(
                ucfirst($name) . ' must be a nonnegative plain decimal string; received "' . $value . '".'
            );
        }

        $parts = explode('.', $value, 2);
        $fraction = $parts[1] ?? '';

        return [
            'digits' => $this->normalize($parts[0] . $fraction),
            'scale' => strlen($fraction),
        ];
    }

    /** @return array{digits: string, scale: int} */
    private function one(): array
    {
        return ['digits' => '1', 'scale' => 0];
    }

    /** @return array{digits: string, scale: int} */
    private function zero(): array
    {
        return ['digits' => '0', 'scale' => 0];
    }

    /** @param array{digits: string, scale: int} $left @param array{digits: string, scale: int} $right */
    private function compare(array $left, array $right): int
    {
        $scale = max($left['scale'], $right['scale']);
        $leftDigits = $left['digits'] . str_repeat('0', $scale - $left['scale']);
        $rightDigits = $right['digits'] . str_repeat('0', $scale - $right['scale']);

        return $this->compareDigits($leftDigits, $rightDigits);
    }

    /** @param array{digits: string, scale: int} $left @param array{digits: string, scale: int} $right @return array{digits: string, scale: int} */
    private function add(array $left, array $right): array
    {
        $scale = max($left['scale'], $right['scale']);
        $leftDigits = $left['digits'] . str_repeat('0', $scale - $left['scale']);
        $rightDigits = $right['digits'] . str_repeat('0', $scale - $right['scale']);

        return ['digits' => $this->addDigits($leftDigits, $rightDigits), 'scale' => $scale];
    }

    /** @param array{digits: string, scale: int} $left @param array{digits: string, scale: int} $right @return array{digits: string, scale: int} */
    private function subtract(array $left, array $right): array
    {
        $scale = max($left['scale'], $right['scale']);
        $leftDigits = $left['digits'] . str_repeat('0', $scale - $left['scale']);
        $rightDigits = $right['digits'] . str_repeat('0', $scale - $right['scale']);

        return ['digits' => $this->subtractDigits($leftDigits, $rightDigits), 'scale' => $scale];
    }

    /** @param array{digits: string, scale: int} $left @param array{digits: string, scale: int} $right @return array{digits: string, scale: int} */
    private function multiply(array $left, array $right): array
    {
        return [
            'digits' => $this->multiplyDigits($left['digits'], $right['digits']),
            'scale' => $left['scale'] + $right['scale'],
        ];
    }

    /** @param array{digits: string, scale: int} $value */
    private function formatTwoPlacesHalfEven(array $value): string
    {
        $digits = $value['digits'];

        if ($value['scale'] > 2) {
            $cut = strlen($digits) - ($value['scale'] - 2);
            $kept = $cut > 0 ? substr($digits, 0, $cut) : '0';
            $discarded = $cut > 0 ? substr($digits, $cut) : str_pad($digits, $value['scale'] - 2, '0', STR_PAD_LEFT);
            $half = '5' . str_repeat('0', strlen($discarded) - 1);
            $comparison = $this->compareDigits($discarded, $half);
            $lastKeptDigit = (int) substr($kept, -1);

            if ($comparison > 0 || ($comparison === 0 && $lastKeptDigit % 2 === 1)) {
                $kept = $this->addDigits($kept, '1');
            }

            $digits = $kept;
        } else {
            $digits .= str_repeat('0', 2 - $value['scale']);
        }

        $digits = str_pad($this->normalize($digits), 3, '0', STR_PAD_LEFT);

        return substr($digits, 0, -2) . '.' . substr($digits, -2);
    }

    private function compareDigits(string $left, string $right): int
    {
        $left = $this->normalize($left);
        $right = $this->normalize($right);

        return strlen($left) <=> strlen($right) ?: $left <=> $right;
    }

    private function addDigits(string $left, string $right): string
    {
        $carry = 0;
        $result = '';
        $leftIndex = strlen($left) - 1;
        $rightIndex = strlen($right) - 1;

        while ($leftIndex >= 0 || $rightIndex >= 0 || $carry > 0) {
            $sum = ($leftIndex >= 0 ? (int) $left[$leftIndex--] : 0)
                + ($rightIndex >= 0 ? (int) $right[$rightIndex--] : 0)
                + $carry;
            $result = ($sum % 10) . $result;
            $carry = intdiv($sum, 10);
        }

        return $this->normalize($result);
    }

    private function subtractDigits(string $left, string $right): string
    {
        $borrow = 0;
        $result = '';
        $leftIndex = strlen($left) - 1;
        $rightIndex = strlen($right) - 1;

        while ($leftIndex >= 0) {
            $difference = (int) $left[$leftIndex--] - ($rightIndex >= 0 ? (int) $right[$rightIndex--] : 0) - $borrow;
            $borrow = 0;

            if ($difference < 0) {
                $difference += 10;
                $borrow = 1;
            }

            $result = $difference . $result;
        }

        return $this->normalize($result);
    }

    private function multiplyDigits(string $left, string $right): string
    {
        $result = '0';
        $zeros = '';

        for ($rightIndex = strlen($right) - 1; $rightIndex >= 0; $rightIndex--) {
            $carry = 0;
            $partial = '';
            $rightDigit = (int) $right[$rightIndex];

            for ($leftIndex = strlen($left) - 1; $leftIndex >= 0; $leftIndex--) {
                $product = (int) $left[$leftIndex] * $rightDigit + $carry;
                $partial = ($product % 10) . $partial;
                $carry = intdiv($product, 10);
            }

            $result = $this->addDigits($result, $this->normalize($carry . $partial) . $zeros);
            $zeros .= '0';
        }

        return $this->normalize($result);
    }

    private function normalize(string $digits): string
    {
        return ltrim($digits, '0') ?: '0';
    }
}
