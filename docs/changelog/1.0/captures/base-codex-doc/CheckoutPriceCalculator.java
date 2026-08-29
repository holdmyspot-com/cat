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
 *   <li>Round the result to two decimal places using
 *       {@link RoundingMode#HALF_UP}.</li>
 * </ol>
 *
 * <p>Rates are expressed as decimal fractions. For example, {@code 0.10}
 * represents 10 percent.</p>
 */
public final class CheckoutPriceCalculator {
    private static final BigDecimal EXPEDITED_SHIPPING_FEE = new BigDecimal("15.00");

    /**
     * Calculates the final amount charged at checkout.
     *
     * @param subtotal the price before discounts, shipping, and tax
     * @param discountRate the fraction of the subtotal to deduct; for example,
     *        {@code 0.20} for a 20 percent discount
     * @param taxRate the tax fraction applied after adding any shipping fee;
     *        for example, {@code 0.13} for 13 percent tax
     * @param expeditedShipping whether to add the fixed expedited shipping fee
     *        of {@code 15.00}
     * @return the final total, rounded to two decimal places using half-up
     *         rounding
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
