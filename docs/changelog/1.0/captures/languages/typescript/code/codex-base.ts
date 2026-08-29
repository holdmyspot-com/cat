export type ShippingMethod = "standard" | "expedited";

type Decimal = {
    coefficient: bigint;
    scale: number;
};

/**
 * Calculates a checkout total without converting caller-provided decimal
 * values to JavaScript numbers.  The result is a USD amount with two digits
 * after the decimal point.
 */
export class CheckoutPriceCalculator {
    public calculate(
        itemPrice: string,
        discountRate: string,
        taxRate: string,
        shipping: ShippingMethod,
    ): string {
        const item = parseDecimal(itemPrice, "item price");
        const discount = parseDecimal(discountRate, "discount rate");
        const tax = parseDecimal(taxRate, "tax rate");

        if (shipping !== "standard" && shipping !== "expedited") {
            throw new TypeError("shipping must be standard or expedited");
        }
        if (item.coefficient < 0n) {
            throw new RangeError("item price must not be negative");
        }
        if (discount.coefficient < 0n || tax.coefficient < 0n) {
            throw new RangeError("rates must not be negative");
        }

        // item × (1 - discount), with shipping intentionally excluded from
        // the discount.  Tax is then applied to that subtotal.
        const discountedItem = subtract(one(), discount);
        let subtotal = multiply(item, discountedItem);
        if (shipping === "expedited") {
            subtotal = add(subtotal, { coefficient: 1500n, scale: 2 });
        }

        const total = multiply(subtotal, add(one(), tax));
        return formatUsd(roundToCentsHalfEven(total));
    }
}

function parseDecimal(value: string, name: string): Decimal {
    // Accept ordinary base-10 decimal notation only; scientific notation would
    // otherwise make it too easy to accidentally accept an inexact input.
    const match = /^([+-]?)(\d+)(?:\.(\d*))?$/.exec(value);
    if (!match || match[3] === "") {
        throw new TypeError(`${name} must be a decimal string`);
    }

    const sign = match[1] === "-" ? -1n : 1n;
    const fraction = match[3] ?? "";
    return { coefficient: sign * BigInt(match[2] + fraction), scale: fraction.length };
}

function one(): Decimal {
    return { coefficient: 1n, scale: 0 };
}

function add(left: Decimal, right: Decimal): Decimal {
    const scale = Math.max(left.scale, right.scale);
    return {
        coefficient: left.coefficient * pow10(scale - left.scale) + right.coefficient * pow10(scale - right.scale),
        scale,
    };
}

function subtract(left: Decimal, right: Decimal): Decimal {
    return add(left, { coefficient: -right.coefficient, scale: right.scale });
}

function multiply(left: Decimal, right: Decimal): Decimal {
    return { coefficient: left.coefficient * right.coefficient, scale: left.scale + right.scale };
}

function roundToCentsHalfEven(value: Decimal): bigint {
    const numerator = value.coefficient * 100n;
    const divisor = pow10(value.scale);
    const quotient = numerator / divisor;
    const remainder = numerator % divisor;
    const absoluteRemainder = remainder < 0n ? -remainder : remainder;
    const twiceRemainder = absoluteRemainder * 2n;

    if (twiceRemainder < divisor || (twiceRemainder === divisor && quotient % 2n === 0n)) {
        return quotient;
    }
    return quotient + (numerator < 0n ? -1n : 1n);
}

function formatUsd(cents: bigint): string {
    const sign = cents < 0n ? "-" : "";
    const absoluteCents = cents < 0n ? -cents : cents;
    return `${sign}${absoluteCents / 100n}.${(absoluteCents % 100n).toString().padStart(2, "0")}`;
}

function pow10(exponent: number): bigint {
    return 10n ** BigInt(exponent);
}
