import java.math.BigDecimal;
import java.math.RoundingMode;

public final class CheckoutPriceCalculator {
    private static final BigDecimal EXPEDITED_SHIPPING_FEE = new BigDecimal("15.00");
    private static final BigDecimal ONE_HUNDRED_PERCENT = BigDecimal.ONE;

    public BigDecimal calculate(
            BigDecimal subtotal,
            BigDecimal discountRate,
            BigDecimal taxRate,
            boolean expeditedShipping
    ) {
        BigDecimal discountedSubtotal = subtotal.multiply(ONE_HUNDRED_PERCENT.subtract(discountRate));
        BigDecimal taxableAmount = expeditedShipping
                ? discountedSubtotal.add(EXPEDITED_SHIPPING_FEE)
                : discountedSubtotal;

        return taxableAmount.multiply(ONE_HUNDRED_PERCENT.add(taxRate)).setScale(2, RoundingMode.HALF_UP);
    }
}
