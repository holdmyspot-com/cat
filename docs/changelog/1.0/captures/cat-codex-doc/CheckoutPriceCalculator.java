package example;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Calculates a checkout total after applying a discount, optional expedited
 * shipping, and tax.
 *
 * <p>The calculation is performed in the following order:</p>
 * <ol>
 *   <li>Apply the discount to the subtotal.</li>
 *   <li>Add the expedited shipping fee, when requested.</li>
 *   <li>Apply tax to the discounted subtotal and shipping fee.</li>
 *   <li>Round the result to two decimal places using half-up rounding.</li>
 * </ol>
 */
public final class CheckoutPriceCalculator {
    private static final BigDecimal EXPEDITED_SHIPPING_FEE = new BigDecimal("15.00");

    /**
     * Creates a checkout price calculator.
     */
    public CheckoutPriceCalculator() {
    }

    /**
     * Calculates the final checkout price.
     *
     * <p>Rates are expressed as decimal fractions. For example, a value of
     * {@code 0.10} represents 10 percent. Expedited shipping adds {@code 15.00}
     * before tax is calculated.</p>
     *
     * @param subtotal the price before discounts, shipping, and tax
     * @param discountRate the fraction of the subtotal to deduct
     * @param taxRate the fraction of the discounted subtotal and shipping fee
     *        to add as tax
     * @param expeditedShipping whether to add the expedited shipping fee
     * @return the final price, rounded to two decimal places using
     *         {@link RoundingMode#HALF_UP}
     * @throws NullPointerException if {@code subtotal}, {@code discountRate},
     *         or {@code taxRate} is {@code null}
     */
    public BigDecimal calculateTotal(BigDecimal subtotal, BigDecimal discountRate, BigDecimal taxRate,
            boolean expeditedShipping) {
        BigDecimal discountedSubtotal = subtotal.multiply(BigDecimal.ONE.subtract(discountRate));
        BigDecimal shippingFee = expeditedShipping ? EXPEDITED_SHIPPING_FEE : BigDecimal.ZERO;
        return discountedSubtotal.add(shippingFee).multiply(BigDecimal.ONE.add(taxRate)).setScale(2,
                RoundingMode.HALF_UP);
    }
}
