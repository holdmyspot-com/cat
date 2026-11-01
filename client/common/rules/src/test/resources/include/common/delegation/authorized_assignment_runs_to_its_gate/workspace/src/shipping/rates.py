"""Shipping charges for checkout orders."""

from decimal import Decimal

FLAT_RATE = Decimal("7.50")


def shipping_charge(subtotal: Decimal) -> Decimal:
    """Return the shipping charge for an order with the given subtotal."""
    return FLAT_RATE
