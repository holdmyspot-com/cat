"""Checkout for the online store."""

from dataclasses import dataclass


@dataclass
class Line:
	"""One product in the cart."""

	sku: str
	unit_price_cents: int
	quantity: int


def total_cents(lines: list[Line], shipping_cents: int) -> int:
	"""Returns the amount to charge: the price of every line plus shipping."""
	subtotal = sum(line.unit_price_cents * line.quantity for line in lines)
	return subtotal + shipping_cents
