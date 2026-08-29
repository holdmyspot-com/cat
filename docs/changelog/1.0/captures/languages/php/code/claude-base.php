<?php

declare(strict_types=1);

final class CheckoutPriceCalculator
{
    private const EXPEDITED_SHIPPING_FEE = '15';

    public static function calculate(
        string $itemPrice,
        string $discountRate,
        string $taxRate,
        bool $expeditedShipping = false
    ): string {
        [$priceCoefficient, $priceScale] = self::parseDecimal($itemPrice, 'item price');
        [$discountCoefficient, $discountScale] = self::parseRate($discountRate, 'discount rate');
        [$taxCoefficient, $taxScale] = self::parseRate($taxRate, 'tax rate');

        $discountFactor = self::subtract(
            self::powerOfTen($discountScale),
            $discountCoefficient
        );
        $subtotal = self::multiply($priceCoefficient, $discountFactor);
        $subtotalScale = $priceScale + $discountScale;

        if ($expeditedShipping) {
            $shippingFee = self::EXPEDITED_SHIPPING_FEE . str_repeat('0', $subtotalScale);
            $subtotal = self::add($subtotal, $shippingFee);
        }

        $taxFactor = self::add(self::powerOfTen($taxScale), $taxCoefficient);
        $total = self::multiply($subtotal, $taxFactor);

        return self::roundToCents($total, $subtotalScale + $taxScale);
    }

    /** @return array{string, int} */
    private static function parseDecimal(string $value, string $name): array
    {
        if (preg_match('/\A[0-9]+(?:\.[0-9]+)?\z/D', $value) !== 1) {
            throw new InvalidArgumentException(
                sprintf('The %s must be a nonnegative decimal string.', $name)
            );
        }

        $point = strpos($value, '.');
        if ($point === false) {
            return [self::normalize($value), 0];
        }

        $scale = strlen($value) - $point - 1;
        $coefficient = substr($value, 0, $point) . substr($value, $point + 1);

        return [self::normalize($coefficient), $scale];
    }

    /** @return array{string, int} */
    private static function parseRate(string $value, string $name): array
    {
        [$coefficient, $scale] = self::parseDecimal($value, $name);

        if (self::compare($coefficient, self::powerOfTen($scale)) > 0) {
            throw new InvalidArgumentException(
                sprintf('The %s must be between 0 and 1 inclusive.', $name)
            );
        }

        return [$coefficient, $scale];
    }

    private static function roundToCents(string $coefficient, int $scale): string
    {
        if ($scale <= 2) {
            $cents = $coefficient . str_repeat('0', 2 - $scale);
        } else {
            $discardedLength = $scale - 2;
            $coefficient = str_pad(
                $coefficient,
                $discardedLength + 1,
                '0',
                STR_PAD_LEFT
            );

            $keptLength = strlen($coefficient) - $discardedLength;
            $cents = substr($coefficient, 0, $keptLength);
            $discarded = substr($coefficient, $keptLength);
            $firstDiscardedDigit = (int) $discarded[0];
            $hasFollowingNonzeroDigit = strspn($discarded, '0', 1) !== strlen($discarded) - 1;
            $lastKeptDigitIsOdd = ((int) $cents[strlen($cents) - 1] % 2) === 1;

            if (
                $firstDiscardedDigit > 5
                || ($firstDiscardedDigit === 5
                    && ($hasFollowingNonzeroDigit || $lastKeptDigitIsOdd))
            ) {
                $cents = self::add($cents, '1');
            }
        }

        $cents = str_pad(self::normalize($cents), 3, '0', STR_PAD_LEFT);

        return substr($cents, 0, -2) . '.' . substr($cents, -2);
    }

    private static function powerOfTen(int $power): string
    {
        return '1' . str_repeat('0', $power);
    }

    private static function normalize(string $integer): string
    {
        $integer = ltrim($integer, '0');

        return $integer === '' ? '0' : $integer;
    }

    private static function compare(string $left, string $right): int
    {
        $left = self::normalize($left);
        $right = self::normalize($right);

        if (strlen($left) !== strlen($right)) {
            return strlen($left) <=> strlen($right);
        }

        return strcmp($left, $right) <=> 0;
    }

    private static function add(string $left, string $right): string
    {
        $leftIndex = strlen($left) - 1;
        $rightIndex = strlen($right) - 1;
        $carry = 0;
        $result = '';

        while ($leftIndex >= 0 || $rightIndex >= 0 || $carry !== 0) {
            $sum = $carry;
            if ($leftIndex >= 0) {
                $sum += (int) $left[$leftIndex--];
            }
            if ($rightIndex >= 0) {
                $sum += (int) $right[$rightIndex--];
            }

            $result .= (string) ($sum % 10);
            $carry = intdiv($sum, 10);
        }

        return strrev($result);
    }

    private static function subtract(string $left, string $right): string
    {
        $leftIndex = strlen($left) - 1;
        $rightIndex = strlen($right) - 1;
        $borrow = 0;
        $result = '';

        while ($leftIndex >= 0) {
            $difference = (int) $left[$leftIndex--] - $borrow;
            if ($rightIndex >= 0) {
                $difference -= (int) $right[$rightIndex--];
            }

            if ($difference < 0) {
                $difference += 10;
                $borrow = 1;
            } else {
                $borrow = 0;
            }

            $result .= (string) $difference;
        }

        return self::normalize(strrev($result));
    }

    private static function multiply(string $left, string $right): string
    {
        $left = self::normalize($left);
        $right = self::normalize($right);
        if ($left === '0' || $right === '0') {
            return '0';
        }

        $digits = array_fill(0, strlen($left) + strlen($right), 0);

        for ($leftIndex = strlen($left) - 1; $leftIndex >= 0; --$leftIndex) {
            for ($rightIndex = strlen($right) - 1; $rightIndex >= 0; --$rightIndex) {
                $position = $leftIndex + $rightIndex + 1;
                $product = ((int) $left[$leftIndex] * (int) $right[$rightIndex])
                    + $digits[$position];
                $digits[$position] = $product % 10;
                $digits[$position - 1] += intdiv($product, 10);
            }
        }

        return self::normalize(implode('', $digits));
    }
}
