public final class CheckoutPriceCalculator {
    public double calculateTotal(
            double subtotal,
            double discountRate,
            double taxRate,
            boolean expeditedShipping) {
        double discountedSubtotal = subtotal * (1 - discountRate);
        double shippingFee = expeditedShipping ? 15.00 : 0.00;
        return (discountedSubtotal + shippingFee) * (1 + taxRate);
    }
}
