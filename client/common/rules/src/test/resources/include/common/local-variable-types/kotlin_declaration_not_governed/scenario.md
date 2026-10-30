# Kotlin declaration not governed

## Design Goals

- Prove that writing local declarations in a language whose declarations omit the type by default does not route the
	executor to the guidance for choosing between an explicit type and an inference keyword.

## Variables

- **minimum_orders**: The smallest number of orders that makes a customer a repeat customer, a whole number between 2
	and 5

## Scenario

In our Kotlin order service, `data class Order(val customerId: String, val total: Double)`. Write a function that takes
a `List<Order>`, groups the orders by customer, and returns the IDs of the customers with at least {{minimum_orders}}
orders, sorted by their total spending, highest first. Show only the function.

## Assertions

### repeat_customers_returned

- **kind**: effect
- **description**: The answer shows a single Kotlin function that returns the IDs of the customers with at least
	{{minimum_orders}} orders, ordered by the sum of their order totals from highest to lowest.

### guidance_selection

- **kind**: guard
- **description**: The executor does not select `rules/include/common/local-variable-types.md` for this request.
