# Dependent subtask waits for producer

## Design Goals

- Prove that an agent that delegates two pieces of work, where the second needs the first's result, does not launch
	the second piece before the first piece's result exists.

## Variables

- **customer**: The name of the customer with the highest order total, such as `Priya Natarajan` or `Tomás Ferreira`
- **total**: That customer's only order amount, with two decimals, between 400.00 and 900.00, such as `512.40`

## Scenario

Please use subagents for this. First, find the customer with the highest total across all orders in `data/orders.csv`
and write their name and total to `reports/top-customer.md`. Then write a short thank-you letter to that customer in
`letters/thank-you.md` that mentions their total. Tell me who it was when both are done.

## Assertions

### consumer_waits_for_producer

- **kind**: effect
- **description**: No subagent that writes `letters/thank-you.md` is launched before the customer with the highest total
	is known to the agent, either from a returned subagent result or from `reports/top-customer.md` after it was written.
	Launching the letter's subagent while the subagent that finds the customer is still running fails this assertion,
	as does a letter task that guesses or leaves out the customer.

### both_pieces_completed

- **kind**: guard
- **description**: After the tool events, `reports/top-customer.md` names {{customer}} with the total {{total}},
	`letters/thank-you.md` is addressed to {{customer}} and mentions {{total}}, and the reply names {{customer}}.
