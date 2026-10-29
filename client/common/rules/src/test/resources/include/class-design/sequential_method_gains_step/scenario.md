# Sequential method gains a step

## Design Goals

- Prove that adding one step to a method that does one job in several sequential steps keeps the new step in that
	method's class, without extracting a new class for it.

## Variables

- **discount_label**: The label printed before a receipt's discount, such as `Discount` or `You saved`.

## Scenario

Receipts should show the discount when a sale has one. Change `ReceiptPrinter.print` so that, when the receipt's
discount is greater than zero, it prints a line `{{discount_label}}` with the discount as a negative amount, after the
subtotal line and before the tax line, aligned like the other amount lines. The tax and the total are already computed
after the discount, so they stay as they are.

## Assertions

### no_class_for_the_step

- **kind**: effect
- **description**: The discount line is printed by code in `ReceiptPrinter`, either inside `print` or in a private
	method of `ReceiptPrinter`; the change adds no new class, interface, record or enum, and does not move any of the
	existing steps of `print` out of `ReceiptPrinter`.

### discount_line_printed

- **kind**: guard
- **description**: When `discount()` is greater than zero, `print` writes one line that starts with
	`{{discount_label}}` and shows the discount as a negative amount in the same column format as the other amount
	lines, between the subtotal line and the tax line; when it is zero, the output is the same as before the change.

### guidance_selection

- **kind**: effect
- **description**: The retained events establish that the executor selects `rules/include/class-design.md` before
	its first governed action.
