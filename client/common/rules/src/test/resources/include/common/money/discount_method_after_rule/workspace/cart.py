"""The shopping cart of the web shop."""


class Cart:
    def __init__(self):
        self.lines = []  # (unit_price, quantity) pairs

    def subtotal(self):
        return sum(price * quantity for price, quantity in self.lines)
