"""Invoice totals for the billing service."""


def total(lines):
    """Returns the invoice total for (unit_price, quantity) pairs."""
    return sum(price * quantity for price, quantity in lines)
