import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Calculates the final price of one checkout item using exact decimal arithmetic.
 */
public final class CheckoutPriceCalculator {
    private static final Pattern PLAIN_NONNEGATIVE_DECIMAL =
            Pattern.compile("[0-9]+(?:\\.[0-9]+)?");
    private static final BigDecimal EXPEDITED_SHIPPING_FEE = new BigDecimal("15.00");

    /**
     * Prevents construction of this utility class.
     */
    private CheckoutPriceCalculator() {
    }

    /**
     * Applies the discount to the item price, adds a 15.00 fee when expedited
     * shipping is selected, and then applies tax. The result is rounded once to
     * two decimal places using half-even rounding.
     *
     * @param itemPrice item price as a plain nonnegative decimal string
     * @param discountRate discount fraction from 0 through 1, inclusive, as a
     *        plain nonnegative decimal string
     * @param taxRate tax fraction from 0 through 1, inclusive, as a plain
     *        nonnegative decimal string
     * @param shippingMethod {@link ShippingMethod#EXPEDITED} to add the 15.00
     *        expedited shipping fee after the discount and before tax, or
     *        {@link ShippingMethod#STANDARD} for shipping with no fee
     * @return the final total as an exact decimal string with two decimal places
     * @throws NullPointerException if {@code itemPrice}, {@code discountRate},
     *         {@code taxRate}, or {@code shippingMethod} is {@code null}
     * @throws IllegalArgumentException if any decimal input is not in the required
     *         plain nonnegative format, or if either rate is outside [0, 1]
     */
    public static String calculate(
            String itemPrice,
            String discountRate,
            String taxRate,
            ShippingMethod shippingMethod) {
        BigDecimal price = parsePlainNonnegativeDecimal(itemPrice, "itemPrice");
        BigDecimal discount = parseRate(discountRate, "discountRate");
        BigDecimal tax = parseRate(taxRate, "taxRate");
        Objects.requireNonNull(shippingMethod, "shippingMethod may not be null");

        BigDecimal discountedPrice = price.multiply(BigDecimal.ONE.subtract(discount));
        BigDecimal shippingFee = shippingMethod == ShippingMethod.EXPEDITED
                ? EXPEDITED_SHIPPING_FEE
                : BigDecimal.ZERO;
        BigDecimal taxableSubtotal = discountedPrice.add(shippingFee);
        BigDecimal total = taxableSubtotal.multiply(BigDecimal.ONE.add(tax));

        return total.setScale(2, RoundingMode.HALF_EVEN).toPlainString();
    }

    /**
     * Parses one plain nonnegative decimal input.
     *
     * @param value decimal text to parse
     * @param parameterName caller-visible parameter name for failure diagnostics
     * @return the exact parsed decimal
     * @throws NullPointerException if {@code value} is {@code null}
     * @throws IllegalArgumentException if {@code value} is not in plain
     *         nonnegative decimal notation
     */
    private static BigDecimal parsePlainNonnegativeDecimal(String value, String parameterName) {
        Objects.requireNonNull(value, parameterName + " may not be null");
        if (!PLAIN_NONNEGATIVE_DECIMAL.matcher(value).matches()) {
            throw new IllegalArgumentException(
                    parameterName + " must contain digits with an optional decimal point");
        }

        return new BigDecimal(value);
    }

    /**
     * Parses one decimal fraction in the inclusive range [0, 1].
     *
     * @param value rate text to parse
     * @param parameterName caller-visible parameter name for failure diagnostics
     * @return the exact parsed rate
     * @throws NullPointerException if {@code value} is {@code null}
     * @throws IllegalArgumentException if {@code value} is not a plain
     *         nonnegative decimal or lies outside [0, 1]
     */
    private static BigDecimal parseRate(String value, String parameterName) {
        BigDecimal rate = parsePlainNonnegativeDecimal(value, parameterName);
        if (rate.compareTo(BigDecimal.ONE) > 0) {
            throw new IllegalArgumentException(parameterName + " must be between 0 and 1 inclusive");
        }

        return rate;
    }
}
