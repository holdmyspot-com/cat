import BigNumber from "bignumber.js";

export type ShippingMethod = "standard" | "expedited";

const PLAIN_NONNEGATIVE_DECIMAL = /^\d+(?:\.\d+)?$/;
const EXPEDITED_SHIPPING_FEE = new BigNumber("15.00");

function parseDecimal(value: string, parameterName: string): BigNumber {
  if (!PLAIN_NONNEGATIVE_DECIMAL.test(value)) {
    throw new RangeError(
      `${parameterName} must be digits with an optional decimal point; received ${JSON.stringify(value)}`
    );
  }

  return new BigNumber(value);
}

function parseRate(value: string, parameterName: string): BigNumber {
  const rate = parseDecimal(value, parameterName);

  if (rate.isGreaterThan(1)) {
    throw new RangeError(
      `${parameterName} must be between 0 and 1 inclusive; received ${JSON.stringify(value)}`
    );
  }

  return rate;
}

export class CheckoutPriceCalculator {
  calculate(
    itemPrice: string,
    discountRate: string,
    taxRate: string,
    shippingMethod: ShippingMethod
  ): string {
    const price = parseDecimal(itemPrice, "itemPrice");
    const discount = parseRate(discountRate, "discountRate");
    const tax = parseRate(taxRate, "taxRate");

    const discountedPrice = price.multipliedBy(new BigNumber(1).minus(discount));
    const shippingFee = shippingMethod === "expedited" ? EXPEDITED_SHIPPING_FEE : new BigNumber(0);
    const taxableSubtotal = discountedPrice.plus(shippingFee);
    const total = taxableSubtotal.multipliedBy(new BigNumber(1).plus(tax));

    return total.toFixed(2, BigNumber.ROUND_HALF_EVEN);
  }
}
