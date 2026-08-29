<?php

declare(strict_types=1);

final class CheckoutPriceCalculator
{
    public const STANDARD_SHIPPING = 'standard';
    public const EXPEDITED_SHIPPING = 'expedited';

    /**
     * Calculates the checkout total, rounded to cents using half-even rounding.
     *
     * @throws \InvalidArgumentException when an amount, rate, or shipping method is invalid.
     */
    public static function calculate(
        string $itemPrice,
        string $discountRate,
        string $taxRate,
        string $shippingMethod
    ): string {
        [$price, $priceScale] = self::parseDecimal($itemPrice, 'item price');
        [$discount, $discountScale] = self::parseRate($discountRate, 'discount rate');
        [$tax, $taxScale] = self::parseRate($taxRate, 'tax rate');

        if ($shippingMethod !== self::STANDARD_SHIPPING
            && $shippingMethod !== self::EXPEDITED_SHIPPING) {
            throw new \InvalidArgumentException('Shipping method must be "standard" or "expedited".');
        }

        // price * (1 - discount)
        $discountMultiplier = self::subtract(self::powerOfTen($discountScale), $discount);
        $total = self::multiply($price, $discountMultiplier);
        $totalScale = $priceScale + $discountScale;

        // Shipping is added after the discount but before tax.
        if ($shippingMethod === self::EXPEDITED_SHIPPING) {
            $total = self::add($total, '15' . str_repeat('0', $totalScale));
        }

        // (discounted price + shipping) * (1 + tax)
        $taxMultiplier = self::add(self::powerOfTen($taxScale), $tax);
        $total = self::multiply($total, $taxMultiplier);
        $totalScale += $taxScale;

        return self::roundToCents($total, $totalScale);
    }

    /** @return array{string, int} unscaled nonnegative integer and decimal scale */
    private static function parseDecimal(string $value, string $name): array
    {
        if (preg_match('/\A\d+(?:\.\d+)?\z/D', $value) !== 1) {
            throw new \InvalidArgumentException(sprintf('%s must be a plain nonnegative decimal string.', $name));
        }

        $parts = explode('.', $value, 2);
        $integer = ltrim($parts[0], '0');
        $fraction = $parts[1] ?? '';

        // Trailing zeroes do not change the value and needlessly enlarge arithmetic.
        $fraction = rtrim($fraction, '0');
        $scale = strlen($fraction);
        $digits = ltrim(($integer === '' ? '0' : $integer) . $fraction, '0');

        return [$digits === '' ? '0' : $digits, $scale];
    }

    /** @return array{string, int} */
    private static function parseRate(string $value, string $name): array
    {
        [$digits, $scale] = self::parseDecimal($value, $name);

        if (self::compare($digits, self::powerOfTen($scale)) > 0) {
            throw new \InvalidArgumentException(sprintf('%s must be between 0 and 1 inclusive.', $name));
        }

        return [$digits, $scale];
    }

    private static function roundToCents(string $digits, int $scale): string
    {
        if ($scale <= 2) {
            $cents = $digits . str_repeat('0', 2 - $scale);
        } else {
            $discardedLength = $scale - 2;
            $split = strlen($digits) - $discardedLength;
            $wholeCents = $split > 0 ? substr($digits, 0, $split) : '0';
            $discarded = $split > 0 ? substr($digits, $split) : str_repeat('0', -$split) . $digits;
            $wholeCents = self::trimLeadingZeroes($wholeCents);

            $firstDiscarded = $discarded[0];
            $remainingDiscarded = substr($discarded, 1);
            $roundUp = $firstDiscarded > '5'
                || ($firstDiscarded === '5'
                    && (trim($remainingDiscarded, '0') !== '' || ((int) substr($wholeCents, -1)) % 2 === 1));
            $cents = $roundUp ? self::add($wholeCents, '1') : $wholeCents;
        }

        $cents = self::trimLeadingZeroes($cents);
        $cents = str_pad($cents, 3, '0', STR_PAD_LEFT);

        return substr($cents, 0, -2) . '.' . substr($cents, -2);
    }

    private static function powerOfTen(int $scale): string
    {
        return '1' . str_repeat('0', $scale);
    }

    private static function add(string $left, string $right): string
    {
        $carry = 0;
        $result = '';
        $leftIndex = strlen($left) - 1;
        $rightIndex = strlen($right) - 1;

        while ($leftIndex >= 0 || $rightIndex >= 0 || $carry !== 0) {
            $sum = $carry;
            if ($leftIndex >= 0) {
                $sum += (int) $left[$leftIndex--];
            }
            if ($rightIndex >= 0) {
                $sum += (int) $right[$rightIndex--];
            }
            $result = ($sum % 10) . $result;
            $carry = intdiv($sum, 10);
        }

        return self::trimLeadingZeroes($result);
    }

    /** Subtracts $right from $left.  The caller must ensure $left >= $right. */
    private static function subtract(string $left, string $right): string
    {
        $borrow = 0;
        $result = '';
        $leftIndex = strlen($left) - 1;
        $rightIndex = strlen($right) - 1;

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
            $result = $difference . $result;
        }

        return self::trimLeadingZeroes($result);
    }

    private static function multiply(string $left, string $right): string
    {
        if ($left === '0' || $right === '0') {
            return '0';
        }

        $length = strlen($left) + strlen($right);
        $digits = array_fill(0, $length, 0);

        for ($leftIndex = strlen($left) - 1; $leftIndex >= 0; --$leftIndex) {
            for ($rightIndex = strlen($right) - 1; $rightIndex >= 0; --$rightIndex) {
                $position = $leftIndex + $rightIndex + 1;
                $digits[$position] += (int) $left[$leftIndex] * (int) $right[$rightIndex];
            }
        }

        for ($position = $length - 1; $position > 0; --$position) {
            $digits[$position - 1] += intdiv($digits[$position], 10);
            $digits[$position] %= 10;
        }

        return self::trimLeadingZeroes(implode('', $digits));
    }

    private static function compare(string $left, string $right): int
    {
        $left = self::trimLeadingZeroes($left);
        $right = self::trimLeadingZeroes($right);

        return strlen($left) === strlen($right) ? strcmp($left, $right) : strlen($left) <=> strlen($right);
    }

    private static function trimLeadingZeroes(string $value): string
    {
        $value = ltrim($value, '0');

        return $value === '' ? '0' : $value;
    }
}
