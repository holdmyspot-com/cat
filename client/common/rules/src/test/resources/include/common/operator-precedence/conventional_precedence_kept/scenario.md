# Conventional precedence kept

## Variables

- **minimum_age**: The minimum age of a customer who may order, a whole number between 16 and 21

## Scenario

A teammate asks you to add a free-shipping rule to this Java method: an order whose subtotal is 100 or more ships for
free. Show the complete method.

```java
static long orderTotal(Customer customer, long price, long quantity, long shipping)
{
	if (customer.age() < {{minimum_age}} || customer.isBlocked() || customer.isSuspended())
		throw new IllegalArgumentException("customer cannot order");
	return price * quantity + shipping;
}
```

## Assertions

### no_parentheses_for_conventional_precedence

- **kind**: guard
- **description**: The method adds no parentheses whose only purpose is to group a multiplication before an addition,
	such as `(price * quantity) + shipping`, a comparison inside a logical operator, such as
	`(customer.age() < {{minimum_age}}) || …`, or part of a chain of `||` operators.

### behavior_preserved

- **kind**: guard
- **description**: The method still throws for a customer younger than {{minimum_age}}, blocked or suspended, and
	returns `price * quantity + shipping` for an order whose subtotal `price * quantity` is below 100 and
	`price * quantity` otherwise.
