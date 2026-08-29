import re
from decimal import Decimal, MAX_EMAX, MIN_EMIN, ROUND_HALF_EVEN, localcontext
from enum import Enum


_DECIMAL_PATTERN = re.compile(r"[0-9]+(?:\.[0-9]+)?\Z")
_EXPEDITED_SHIPPING_FEE = Decimal("15.00")
_CENTS = Decimal("0.01")
_ONE = Decimal(1)


class ShippingMethod(Enum):
    """Shipping choices supported by the checkout calculator."""

    STANDARD = "standard"
    EXPEDITED = "expedited"


class CheckoutPriceCalculator:
    """Calculate one item's checkout total using exact decimal inputs."""

    @staticmethod
    def calculate(
        item_price: str,
        discount_rate: str,
        tax_rate: str,
        shipping_method: ShippingMethod,
    ) -> str:
        """Return the item's final checkout total with two decimal places.

        ``item_price``, ``discount_rate``, and ``tax_rate`` must contain only
        ASCII digits with an optional decimal point followed by more digits.
        The rates are decimal fractions in the inclusive range from zero to
        one. The discount applies only to the item. Expedited shipping adds
        15.00 after the discount, and tax applies after any shipping fee.
        The final total is rounded once to cents using half-even rounding.

        Raises:
            TypeError: If a decimal input is not a string or ``shipping_method``
                is not a ``ShippingMethod``.
            ValueError: If a decimal string is malformed or a rate is outside
                the inclusive range from zero to one.
        """
        item_price_value = _parse_decimal(item_price, "item_price")
        discount_rate_value = _parse_rate(discount_rate, "discount_rate")
        tax_rate_value = _parse_rate(tax_rate, "tax_rate")
        if not isinstance(shipping_method, ShippingMethod):
            raise TypeError("shipping_method must be a ShippingMethod")

        precision = sum(
            len(value.replace(".", ""))
            for value in (item_price, discount_rate, tax_rate)
        ) + 10
        with localcontext() as context:
            context.prec = precision
            context.Emax = MAX_EMAX
            context.Emin = MIN_EMIN

            discounted_item_price = item_price_value * (
                _ONE - discount_rate_value
            )
            shipping_fee = (
                _EXPEDITED_SHIPPING_FEE
                if shipping_method is ShippingMethod.EXPEDITED
                else Decimal(0)
            )
            taxable_total = discounted_item_price + shipping_fee
            final_total = taxable_total * (_ONE + tax_rate_value)
            rounded_total = final_total.quantize(_CENTS, rounding=ROUND_HALF_EVEN)

        return format(rounded_total, ".2f")


def _parse_decimal(value: str, parameter_name: str) -> Decimal:
    if not isinstance(value, str):
        raise TypeError(f"{parameter_name} must be a string")
    if _DECIMAL_PATTERN.fullmatch(value) is None:
        raise ValueError(
            f"{parameter_name} must contain digits with an optional decimal point"
        )

    return Decimal(value)


def _parse_rate(value: str, parameter_name: str) -> Decimal:
    rate = _parse_decimal(value, parameter_name)
    if rate > _ONE:
        raise ValueError(f"{parameter_name} must be between 0 and 1 inclusive")

    return rate
