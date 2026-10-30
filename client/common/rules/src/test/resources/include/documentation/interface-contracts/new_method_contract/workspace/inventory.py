class Inventory:
    def __init__(self):
        self._counts = {}

    def add(self, sku, quantity):
        if quantity <= 0:
            raise ValueError("quantity must be positive")
        self._counts[sku] = self._counts.get(sku, 0) + quantity

    def on_hand(self, sku):
        return self._counts.get(sku, 0)
