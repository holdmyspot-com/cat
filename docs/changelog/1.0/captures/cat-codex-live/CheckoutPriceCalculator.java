public final class CheckoutPriceCalculator {
    private static final double EXPEDITED_SHIPPING_FEE = 15.00;

    public double calculateTotal(double subtotal, double discountRate, double taxRate,
                                 boolean expeditedShipping) {
        double discountedSubtotal = subtotal * (1 - discountRate);
        double preTaxTotal = discountedSubtotal
                + (expeditedShipping ? EXPEDITED_SHIPPING_FEE : 0);
        return preTaxTotal * (1 + taxRate);
    }
}
