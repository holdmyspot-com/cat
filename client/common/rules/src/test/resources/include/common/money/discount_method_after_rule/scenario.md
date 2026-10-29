# Discount method after rule

## Variables

- **percent**: The whole-number percentage of the loyalty discount, from 5 to 25

## Scenario

Add a method to `Cart` in `cart.py` that returns the cart's subtotal after a {{percent}}% loyalty discount.

## Assertions

### discounted_total_returned

- **kind**: effect
- **description**: `cart.py` gains a method that returns the subtotal reduced by {{percent}}% and keeps `subtotal`
	unchanged.

### guidance_selection

- **kind**: effect
- **description**: The retained events establish that the executor selects `rules/include/common/money.md` before it
	edits `cart.py` or states the method's representation or signature.
