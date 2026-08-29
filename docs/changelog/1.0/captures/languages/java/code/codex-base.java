import java.math.BigDecimal;
import java.math.RoundingMode;

public final class CheckoutPriceCalculator {
    private static final BigDecimal ONE = BigDecimal.ONE;
    private static final BigDecimal EXPEDITED_SHIPPING_FEE = new BigDecimal("15.00");

    /** The shipping choices supported by the calculator. */
    public enum Shipping {
        STANDARD,
        EXPEDITED
    }

    private CheckoutPriceCalculator() {
        // Utility class.
    }

    /**
     * Calculates the checkout total using exact decimal arithmetic.
     *
     * @param itemPrice nonnegative item price in plain decimal notation
     * @param discountRate discount fraction in the inclusive range [0, 1]
     * @param taxRate tax fraction in the inclusive range [0, 1]
     * @param shipping selected shipping method
     * @return the total rounded to exactly two decimal places using half-even rounding
     * @throws IllegalArgumentException if an input is not in the required format or range
     */
    public static String calculateTotal(
            String itemPrice, String discountRate, String taxRate, Shipping shipping) {
        BigDecimal price = parseNonnegativeDecimal(itemPrice, "item price");
        BigDecimal discount = parseRate(discountRate, "discount rate");
        BigDecimal tax = parseRate(taxRate, "tax rate");

        if (shipping == null) {
            throw new IllegalArgumentException("shipping must not be null");
        }

        BigDecimal taxableAmount = price.multiply(ONE.subtract(discount));
        if (shipping == Shipping.EXPEDITED) {
            taxableAmount = taxableAmount.add(EXPEDITED_SHIPPING_FEE);
        }

        return taxableAmount.multiply(ONE.add(tax))
                .setScale(2, RoundingMode.HALF_EVEN)
                .toPlainString();
    }

    /** Convenience overload for callers that represent expedited shipping as a flag. */
    public static String calculateTotal(
            String itemPrice, String discountRate, String taxRate, boolean expeditedShipping) {
        return calculateTotal(itemPrice, discountRate, taxRate,
                expeditedShipping ? Shipping.EXPEDITED : Shipping.STANDARD);
    }

    private static BigDecimal parseRate(String value, String name) {
        BigDecimal rate = parseNonnegativeDecimal(value, name);
        if (rate.compareTo(ONE) > 0) {
            throw new IllegalArgumentException(name + " must be between 0 and 1 inclusive");
        }
        return rate;
    }

    private static BigDecimal parseNonnegativeDecimal(String value, String name) {
        if (value == null || !value.matches("[0-9]+(?:\\.[0-9]+)?")) {
            throw new IllegalArgumentException(name + " must be a plain nonnegative decimal");
        }
        return new BigDecimal(value);
    }
}
