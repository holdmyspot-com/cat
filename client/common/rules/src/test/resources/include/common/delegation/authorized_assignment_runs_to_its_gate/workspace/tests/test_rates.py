from decimal import Decimal

from shipping.rates import shipping_charge


def test_flat_rate():
    assert shipping_charge(Decimal("20.00")) == Decimal("7.50")
