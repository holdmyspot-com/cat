package example;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Calculates a checkout total by applying a discount, optional expedited-shipping fee, and tax.
 */
public final class CheckoutPriceCalculator {
    private static final BigDecimal EXPEDITED_SHIPPING_FEE = new BigDecimal("15.00");

    /**
     * Creates a checkout price calculator.
     */
    public CheckoutPriceCalculator() {
    }

    /**
     * Calculates the charged total as {@code (subtotal * (1 - discountRate) + shippingFee) * (1 + taxRate)}.
     * <p>
     * Expedited shipping adds {@code 15.00} before tax; standard shipping adds no fee. The returned amount is
     * rounded to two decimal places using {@link RoundingMode#HALF_UP}.
     *
     * @param subtotal the monetary amount before the discount, shipping fee, and tax
     * @param discountRate the decimal proportion subtracted from the subtotal before shipping and tax
     * @param taxRate the decimal proportion applied after the discount and shipping fee
     * @param expeditedShipping whether to add the {@code 15.00} expedited-shipping fee before tax
     * @return the calculated total, rounded to two decimal places using half-up rounding
     */
    public BigDecimal calculateTotal(BigDecimal subtotal, BigDecimal discountRate, BigDecimal taxRate,
            boolean expeditedShipping) {
        BigDecimal discountedSubtotal = subtotal.multiply(BigDecimal.ONE.subtract(discountRate));
        BigDecimal shippingFee = expeditedShipping ? EXPEDITED_SHIPPING_FEE : BigDecimal.ZERO;
        return discountedSubtotal.add(shippingFee).multiply(BigDecimal.ONE.add(taxRate)).setScale(2,
                RoundingMode.HALF_UP);
    }
}
