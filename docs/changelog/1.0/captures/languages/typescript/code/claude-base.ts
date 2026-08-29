export type ShippingMethod = "standard" | "expedited";

type Decimal = {
    coefficient: bigint;
    scale: number;
};

const DECIMAL_PATTERN = /^\d+(?:\.\d+)?$/;
const EXPEDITED_FEE: Decimal = { coefficient: 1500n, scale: 2 };

function powerOfTen(exponent: number): bigint {
    return 10n ** BigInt(exponent);
}

function parseDecimal(value: string, name: string): Decimal {
    if (!DECIMAL_PATTERN.test(value)) {
        throw new TypeError(`${name} must be a nonnegative plain decimal string`);
    }

    const decimalPoint = value.indexOf(".");
    const scale = decimalPoint === -1 ? 0 : value.length - decimalPoint - 1;
    const digits = decimalPoint === -1
        ? value
        : value.slice(0, decimalPoint) + value.slice(decimalPoint + 1);

    return { coefficient: BigInt(digits), scale };
}

function parseRate(value: string, name: string): Decimal {
    const rate = parseDecimal(value, name);
    if (rate.coefficient > powerOfTen(rate.scale)) {
        throw new RangeError(`${name} must be in the inclusive range [0, 1]`);
    }
    return rate;
}

function add(left: Decimal, right: Decimal): Decimal {
    const scale = Math.max(left.scale, right.scale);
    return {
        coefficient:
            left.coefficient * powerOfTen(scale - left.scale) +
            right.coefficient * powerOfTen(scale - right.scale),
        scale,
    };
}

function multiply(left: Decimal, right: Decimal): Decimal {
    return {
        coefficient: left.coefficient * right.coefficient,
        scale: left.scale + right.scale,
    };
}

function complement(rate: Decimal): Decimal {
    return {
        coefficient: powerOfTen(rate.scale) - rate.coefficient,
        scale: rate.scale,
    };
}

function formatAsCents(value: Decimal): string {
    let cents: bigint;

    if (value.scale <= 2) {
        cents = value.coefficient * powerOfTen(2 - value.scale);
    } else {
        const divisor = powerOfTen(value.scale - 2);
        const quotient = value.coefficient / divisor;
        const remainder = value.coefficient % divisor;
        const doubledRemainder = remainder * 2n;
        const roundUp =
            doubledRemainder > divisor ||
            (doubledRemainder === divisor && quotient % 2n === 1n);

        cents = roundUp ? quotient + 1n : quotient;
    }

    const whole = cents / 100n;
    const fraction = (cents % 100n).toString().padStart(2, "0");
    return `${whole}.${fraction}`;
}

export class CheckoutPriceCalculator {
    calculate(
        itemPrice: string,
        discountRate: string,
        taxRate: string,
        shippingMethod: ShippingMethod,
    ): string {
        const price = parseDecimal(itemPrice, "itemPrice");
        const discount = parseRate(discountRate, "discountRate");
        const tax = parseRate(taxRate, "taxRate");

        if (shippingMethod !== "standard" && shippingMethod !== "expedited") {
            throw new TypeError('shippingMethod must be "standard" or "expedited"');
        }

        const discountedPrice = multiply(price, complement(discount));
        const subtotal = shippingMethod === "expedited"
            ? add(discountedPrice, EXPEDITED_FEE)
            : discountedPrice;
        const total = multiply(subtotal, add({ coefficient: 1n, scale: 0 }, tax));

        return formatAsCents(total);
    }
}
