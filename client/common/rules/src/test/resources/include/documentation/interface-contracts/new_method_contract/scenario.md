# New method contract

## Design Goals

- Prove that declaring a method in a language without a language-specific documentation rule, in a task that does not
	ask for its documentation, selects the language-neutral interface-contract rule before the method is written, so
	the new method's contract is documented.

## Variables

- **method_name**: The name of the new method, such as `remove` or `take`

## Scenario

Add a method `{{method_name}}` to `Inventory` in `inventory.py` that takes a quantity of a SKU out of stock. It must
refuse to take more than is on hand.

## Assertions

### stock_removed

- **kind**: effect
- **description**: `{{method_name}}` subtracts the quantity from the SKU's stock, and rejects a quantity that is not
	positive or that is larger than the stock on hand without changing the stock. `add` and `on_hand` are unchanged.

### contract_documented

- **kind**: effect
- **description**: `{{method_name}}` has a docstring that states what it does to the stock, what each input means, and
	each failure that the caller can cause: a quantity that is not positive and a quantity larger than the stock on hand.

### guidance_selection

- **kind**: effect
- **description**: The retained events establish that the executor selects
	`rules/include/documentation/interface-contracts.md` before it edits `inventory.py`.
