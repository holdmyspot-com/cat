"""Exact checkout-price calculation.

All monetary and percentage inputs are accepted as decimal strings so that a
binary floating-point approximation can never affect the charged total.
"""

from decimal import Decimal, ROUND_HALF_EVEN, localcontext
import re
from typing import Union


_DECIMAL_STRING = re.compile(r"^\d+(?:\.\d+)?$")
_CENT = Decimal("0.01")
_ONE = Decimal("1")
_EXPEDITED_FEE = Decimal("15.00")
Shipping = Union[bool, str]


def _parse_decimal(value: str, name: str) -> Decimal:
    """Parse one nonnegative decimal in the input format promised by the API."""
    if not isinstance(value, str):
        raise TypeError(f"{name} must be a decimal string")
    if not _DECIMAL_STRING.fullmatch(value):
        raise ValueError(f"{name} must be plain nonnegative decimal notation")
    return Decimal(value)


def _is_expedited(shipping: Shipping) -> bool:
    """Normalize the two supported shipping selections."""
    if isinstance(shipping, bool):
        return shipping
    if shipping == "standard":
        return False
    if shipping == "expedited":
        return True
    raise ValueError("shipping must be True/False or 'standard'/'expedited'")


def calculate_checkout_price(
    item_price: str,
    discount_rate: str,
    tax_rate: str,
    expedited_shipping: Shipping = False,
) -> str:
    """Return the checkout total rounded to cents using half-even rounding.

    ``discount_rate`` and ``tax_rate`` are fractions: for example, ``"0.1"``
    represents ten percent.  A true ``expedited_shipping`` value (or the string
    ``"expedited"``) adds 15.00 after discount and before tax.
    """
    price = _parse_decimal(item_price, "item_price")
    discount = _parse_decimal(discount_rate, "discount_rate")
    tax = _parse_decimal(tax_rate, "tax_rate")

    if discount > _ONE:
        raise ValueError("discount_rate must be between 0 and 1")
    if tax > _ONE:
        raise ValueError("tax_rate must be between 0 and 1")

    # Decimal's default 28-digit context can itself introduce rounding for
    # otherwise exact input strings.  The required precision covers both
    # multiplications and the final cent quantization.
    precision = len(item_price) + len(discount_rate) + len(tax_rate) + 16
    with localcontext() as context:
        context.prec = precision
        subtotal = price * (_ONE - discount)
        if _is_expedited(expedited_shipping):
            subtotal += _EXPEDITED_FEE
        total = (subtotal * (_ONE + tax)).quantize(
            _CENT, rounding=ROUND_HALF_EVEN
        )

    return format(total, ".2f")


class CheckoutPriceCalculator:
    """Object-oriented entry point for :func:`calculate_checkout_price`."""

    def calculate(
        self,
        item_price: str,
        discount_rate: str,
        tax_rate: str,
        expedited_shipping: Shipping = False,
    ) -> str:
        return calculate_checkout_price(
            item_price, discount_rate, tax_rate, expedited_shipping
        )
