# C# grouping inferred

## Variables

- **minimum_orders**: The smallest number of orders that makes a customer a repeat customer, a whole number between 2
	and 5

## Scenario

In our C# order service, an `Order` has the properties `string CustomerId` and `decimal Total`. Write a method that
takes an `IReadOnlyList<Order>`, groups the orders by customer with LINQ, and then loops over those groups to print, for
each customer with at least {{minimum_orders}} orders, the customer ID and the sum of their order totals. Show only the
method.

## Assertions

### grouping_declared_by_inference

- **kind**: effect
- **description**: The method holds the LINQ grouping in a local variable declared with `var`, such as
	`var ordersByCustomer = orders.GroupBy(order => order.CustomerId);`, and does not declare it with an explicit type
	such as `IEnumerable<IGrouping<string, Order>>`. It prints the customer ID and total of each customer with at least
	{{minimum_orders}} orders.

### guidance_selection

- **kind**: effect
- **description**: The retained events establish that the executor selects
	`rules/include/common/local-variable-types.md` before its first governed action.
