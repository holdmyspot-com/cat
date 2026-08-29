import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.regex.Pattern;

public final class CheckoutPriceCalculator {
    private static final Pattern DECIMAL_PATTERN = Pattern.compile("[0-9]+(?:\\.[0-9]+)?");
    private static final BigDecimal ONE = BigDecimal.ONE;
    private static final BigDecimal EXPEDITED_SHIPPING_FEE = new BigDecimal("15.00");

    private CheckoutPriceCalculator() {
    }

    /**
     * Calculates the checkout total for one item.
     *
     * @param itemPrice the nonnegative item price in plain decimal notation
     * @param discountRate the discount fraction, from 0 through 1
     * @param taxRate the tax fraction, from 0 through 1
     * @param expeditedShipping whether to add the expedited shipping fee
     * @return the final total with exactly two decimal places
     * @throws IllegalArgumentException if a decimal is malformed or a rate is outside [0, 1]
     */
    public static String calculateTotal(
            String itemPrice,
            String discountRate,
            String taxRate,
            boolean expeditedShipping) {
        BigDecimal price = parseDecimal(itemPrice, "itemPrice");
        BigDecimal discount = parseRate(discountRate, "discountRate");
        BigDecimal tax = parseRate(taxRate, "taxRate");

        BigDecimal subtotal = price.multiply(ONE.subtract(discount));
        if (expeditedShipping) {
            subtotal = subtotal.add(EXPEDITED_SHIPPING_FEE);
        }

        return subtotal.multiply(ONE.add(tax))
                .setScale(2, RoundingMode.HALF_EVEN)
                .toPlainString();
    }

    public static String calculate(
            String itemPrice,
            String discountRate,
            String taxRate,
            boolean expeditedShipping) {
        return calculateTotal(itemPrice, discountRate, taxRate, expeditedShipping);
    }

    private static BigDecimal parseRate(String value, String name) {
        BigDecimal rate = parseDecimal(value, name);
        if (rate.compareTo(ONE) > 0) {
            throw new IllegalArgumentException(name + " must be in the range [0, 1]");
        }
        return rate;
    }

    private static BigDecimal parseDecimal(String value, String name) {
        if (value == null || !DECIMAL_PATTERN.matcher(value).matches()) {
            throw new IllegalArgumentException(name + " must be a nonnegative plain decimal");
        }
        return new BigDecimal(value);
    }
}
