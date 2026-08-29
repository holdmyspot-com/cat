"""Exact, decimal-string checkout-price calculations."""

from __future__ import annotations

from decimal import Decimal, ROUND_HALF_EVEN, localcontext
import re
from typing import Literal


_DECIMAL_STRING = re.compile(r"\d+(?:\.\d+)?\Z")
_ONE = Decimal("1")
_EXPEDITED_FEE = Decimal("15.00")
_CENTS = Decimal("0.01")


class CheckoutPriceCalculator:
    """Calculate a checkout total without converting caller input to floats."""

    @staticmethod
    def calculate(
        item_price: str,
        discount_rate: str,
        tax_rate: str,
        shipping: Literal["standard", "expedited"],
    ) -> str:
        """Return the final total, rounded to cents with half-even rounding.

        ``shipping`` must be either ``"standard"`` or ``"expedited"``.  Rates
        are decimal fractions: for example, ``"0.1"`` represents ten percent.
        """
        price = _parse_nonnegative_decimal("item_price", item_price)
        discount = _parse_rate("discount_rate", discount_rate)
        tax = _parse_rate("tax_rate", tax_rate)

        if shipping not in ("standard", "expedited"):
            raise ValueError("shipping must be 'standard' or 'expedited'")

        # Decimal arithmetic is context-sensitive.  Give each finite operation
        # enough precision to preserve all input digits before the one required
        # cent-rounding operation.
        precision = sum(_digit_count(value) for value in (item_price, discount_rate, tax_rate)) + 10
        with localcontext() as context:
            context.prec = max(context.prec, precision)
            subtotal = price * (_ONE - discount)
            if shipping == "expedited":
                subtotal += _EXPEDITED_FEE
            total = subtotal * (_ONE + tax)
            return format(total.quantize(_CENTS, rounding=ROUND_HALF_EVEN), ".2f")

    @staticmethod
    def calculate_total(
        item_price: str,
        discount_rate: str,
        tax_rate: str,
        shipping: Literal["standard", "expedited"],
    ) -> str:
        """Alias for :meth:`calculate`."""
        return CheckoutPriceCalculator.calculate(item_price, discount_rate, tax_rate, shipping)


def _parse_nonnegative_decimal(name: str, value: str) -> Decimal:
    if not isinstance(value, str) or _DECIMAL_STRING.fullmatch(value) is None:
        raise ValueError(f"{name} must be a plain nonnegative decimal string")
    return Decimal(value)


def _parse_rate(name: str, value: str) -> Decimal:
    rate = _parse_nonnegative_decimal(name, value)
    if rate > _ONE:
        raise ValueError(f"{name} must be between 0 and 1 inclusive")
    return rate


def _digit_count(value: str) -> int:
    return len(value) - value.count(".")
