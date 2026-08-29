import { Decimal } from "decimal.js";

const ExactDecimal = Decimal.clone({
    precision: 1_000_000_000,
    rounding: Decimal.ROUND_HALF_EVEN,
});

const EXPEDITED_SHIPPING_FEE_USD = new ExactDecimal("15.00");
const ONE = new ExactDecimal("1");
const ZERO = new ExactDecimal("0");

export type ShippingMethod = "standard" | "expedited";

export interface CheckoutPriceRequest {
    readonly itemPriceUsd: string;
    readonly discountRate: string;
    readonly taxRate: string;
    readonly shippingMethod: ShippingMethod;
}

export class CheckoutPriceCalculator {
    calculate({
        itemPriceUsd,
        discountRate,
        taxRate,
        shippingMethod,
    }: CheckoutPriceRequest): string {
        const itemPrice = parseExactDecimal(itemPriceUsd, "itemPriceUsd");
        const discountRateDecimal = parseExactDecimal(discountRate, "discountRate");
        const taxRateDecimal = parseExactDecimal(taxRate, "taxRate");
        validateShippingMethod(shippingMethod);

        const discountedItemPrice = itemPrice.times(ONE.minus(discountRateDecimal));
        const shippingFee = shippingMethod === "expedited" ? EXPEDITED_SHIPPING_FEE_USD : ZERO;
        const subtotalBeforeTax = discountedItemPrice.plus(shippingFee);
        const totalAfterTax = subtotalBeforeTax.times(ONE.plus(taxRateDecimal));
        const totalUsd = totalAfterTax.toDecimalPlaces(2, ExactDecimal.ROUND_HALF_EVEN);

        return totalUsd.toFixed(2);
    }
}

function parseExactDecimal(value: string, parameterName: string): Decimal.Instance {
    if (typeof value !== "string") {
        throw new TypeError(`${parameterName} must be a valid decimal string.`);
    }

    try {
        const decimal = new ExactDecimal(value);

        if (!decimal.isFinite()) {
            throw new RangeError(`${parameterName} must be a finite decimal string.`);
        }

        return decimal;
    } catch (error) {
        if (error instanceof RangeError) {
            throw error;
        }

        throw new TypeError(`${parameterName} must be a valid decimal string.`);
    }
}

function validateShippingMethod(shippingMethod: ShippingMethod): void {
    if (shippingMethod !== "standard" && shippingMethod !== "expedited") {
        throw new RangeError("shippingMethod must be standard or expedited.");
    }
}
