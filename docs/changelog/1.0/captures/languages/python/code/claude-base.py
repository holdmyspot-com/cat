import re
from decimal import Decimal, ROUND_HALF_EVEN, localcontext


_DECIMAL_PATTERN = re.compile(r"\A[0-9]+(?:\.[0-9]+)?\Z")
_CENTS = Decimal("0.01")
_EXPEDITED_FEE = Decimal("15.00")


class CheckoutPriceCalculator:
    """Calculate a discounted, taxed checkout total."""

    @staticmethod
    def calculate(
        item_price: str,
        discount_rate: str,
        tax_rate: str,
        expedited_shipping: bool = False,
    ) -> str:
        """Return the checkout total as a two-decimal-place string.

        ``item_price``, ``discount_rate``, and ``tax_rate`` must use plain
        nonnegative decimal notation. Rates are decimal fractions in [0, 1].
        """
        price = CheckoutPriceCalculator._parse_decimal(item_price, "item_price")
        discount = CheckoutPriceCalculator._parse_decimal(
            discount_rate, "discount_rate"
        )
        tax = CheckoutPriceCalculator._parse_decimal(tax_rate, "tax_rate")

        if discount > 1:
            raise ValueError("discount_rate must be between 0 and 1 inclusive")
        if tax > 1:
            raise ValueError("tax_rate must be between 0 and 1 inclusive")
        if not isinstance(expedited_shipping, bool):
            raise TypeError("expedited_shipping must be a bool")

        # Decimal operations obey the active context. Size this local context
        # from all input digits so no intermediate value is rounded.
        precision = sum(
            character.isdigit()
            for value in (item_price, discount_rate, tax_rate)
            for character in value
        ) + 10
        with localcontext() as context:
            context.prec = precision
            discounted_price = price * (Decimal(1) - discount)
            subtotal = discounted_price
            if expedited_shipping:
                subtotal += _EXPEDITED_FEE
            total = subtotal * (Decimal(1) + tax)
            rounded_total = total.quantize(_CENTS, rounding=ROUND_HALF_EVEN)

        return format(rounded_total, ".2f")

    @staticmethod
    def _parse_decimal(value: str, name: str) -> Decimal:
        if not isinstance(value, str):
            raise TypeError(f"{name} must be a string")
        if _DECIMAL_PATTERN.fullmatch(value) is None:
            raise ValueError(f"{name} must be a plain nonnegative decimal string")
        return Decimal(value)
