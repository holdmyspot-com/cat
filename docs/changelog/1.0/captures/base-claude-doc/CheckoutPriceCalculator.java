package example;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Calculates a checkout total after applying a discount, optional expedited shipping, and tax.
 *
 * <p>Tax is applied to the discounted subtotal plus any expedited shipping fee. The final amount is
 * rounded to two decimal places using {@link RoundingMode#HALF_UP}.
 */
public final class CheckoutPriceCalculator {
    private static final BigDecimal EXPEDITED_SHIPPING_FEE = new BigDecimal("15.00");

    /**
     * Calculates {@code (subtotal * (1 - discountRate) + shippingFee) * (1 + taxRate)}.
     *
     * @param subtotal the pre-discount price
     * @param discountRate the fractional discount to apply, such as {@code 0.10} for 10%
     * @param taxRate the fractional tax rate to apply, such as {@code 0.08} for 8%
     * @param expeditedShipping whether to add the $15.00 expedited shipping fee before tax
     * @return the final total rounded to two decimal places using half-up rounding
     */
    public BigDecimal calculateTotal(BigDecimal subtotal, BigDecimal discountRate, BigDecimal taxRate,
            boolean expeditedShipping) {
        BigDecimal discountedSubtotal = subtotal.multiply(BigDecimal.ONE.subtract(discountRate));
        BigDecimal shippingFee = expeditedShipping ? EXPEDITED_SHIPPING_FEE : BigDecimal.ZERO;
        return discountedSubtotal.add(shippingFee).multiply(BigDecimal.ONE.add(taxRate)).setScale(2,
                RoundingMode.HALF_UP);
    }
}
