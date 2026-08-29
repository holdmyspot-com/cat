import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Calculates a checkout total for one item using exact decimal arithmetic.
 */
public final class CheckoutPriceCalculator {
    private static final BigDecimal ONE = BigDecimal.ONE;
    private static final BigDecimal EXPEDITED_SHIPPING_FEE = new BigDecimal("15.00");
    private static final Pattern PLAIN_NONNEGATIVE_DECIMAL = Pattern.compile("\\d+(?:\\.\\d+)?");

    /**
     * Creates a calculator with the standard checkout pricing rules.
     */
    public CheckoutPriceCalculator() {
    }

    /**
     * Calculates the total after applying the item discount, shipping, and tax.
     *
     * <p>
     * The discount applies only to the item. Expedited shipping adds 15.00 after
     * the discount and before tax. The returned total is rounded once to two
     * decimal places using half-even rounding.
     *
     * @param itemPrice nonnegative item price in plain decimal notation
     * @param discountRate discount fraction in plain decimal notation, from 0 through 1 inclusive
     * @param taxRate tax fraction in plain decimal notation, from 0 through 1 inclusive
     * @param shippingMethod selected standard or expedited shipping method
     * @return the exact calculated total formatted with exactly two decimal places
     * @throws NullPointerException if any parameter is {@code null}
     * @throws IllegalArgumentException if a decimal input is not plain nonnegative decimal notation, or either rate is
     *         outside the inclusive range from 0 through 1
     */
    public String calculate(
            String itemPrice,
            String discountRate,
            String taxRate,
            ShippingMethod shippingMethod) {
        BigDecimal parsedItemPrice = parseNonnegativeDecimal(itemPrice, "itemPrice");
        BigDecimal parsedDiscountRate = parseRate(discountRate, "discountRate");
        BigDecimal parsedTaxRate = parseRate(taxRate, "taxRate");
        ShippingMethod selectedShippingMethod = Objects.requireNonNull(shippingMethod, "shippingMethod may not be null");

        BigDecimal discountedItemPrice = parsedItemPrice.multiply(ONE.subtract(parsedDiscountRate));
        BigDecimal shippingFee = selectedShippingMethod == ShippingMethod.EXPEDITED
                ? EXPEDITED_SHIPPING_FEE
                : BigDecimal.ZERO;
        BigDecimal subtotal = discountedItemPrice.add(shippingFee);
        BigDecimal totalWithTax = subtotal.multiply(ONE.add(parsedTaxRate));

        return totalWithTax.setScale(2, RoundingMode.HALF_EVEN).toPlainString();
    }

    /**
     * Parses a rate and enforces its inclusive fractional range.
     *
     * @param rateText rate in plain nonnegative decimal notation
     * @param parameterName caller-facing name used in validation failures
     * @return the parsed rate from 0 through 1 inclusive
     * @throws NullPointerException if {@code rateText} or {@code parameterName} is {@code null}
     * @throws IllegalArgumentException if {@code rateText} is not a plain nonnegative decimal or is outside its range
     */
    private static BigDecimal parseRate(String rateText, String parameterName) {
        BigDecimal rate = parseNonnegativeDecimal(rateText, parameterName);
        if (rate.compareTo(ONE) > 0) {
            throw new IllegalArgumentException(parameterName + " must be between 0 and 1 inclusive");
        }
        return rate;
    }

    /**
     * Parses a decimal string only when it uses the supported plain nonnegative notation.
     *
     * @param value decimal text to parse
     * @param parameterName caller-facing name used in validation failures
     * @return the exact parsed nonnegative decimal
     * @throws NullPointerException if {@code value} or {@code parameterName} is {@code null}
     * @throws IllegalArgumentException if {@code value} is not plain nonnegative decimal notation
     */
    private static BigDecimal parseNonnegativeDecimal(String value, String parameterName) {
        Objects.requireNonNull(value, parameterName + " may not be null");
        Objects.requireNonNull(parameterName, "parameterName may not be null");
        if (!PLAIN_NONNEGATIVE_DECIMAL.matcher(value).matches()) {
            throw new IllegalArgumentException(parameterName + " must be a plain nonnegative decimal");
        }
        return new BigDecimal(value);
    }

    /**
     * Available shipping methods for checkout.
     */
    public enum ShippingMethod {
        /** Standard shipping adds no fee. */
        STANDARD,

        /** Expedited shipping adds a 15.00 fee before tax. */
        EXPEDITED
    }
}
