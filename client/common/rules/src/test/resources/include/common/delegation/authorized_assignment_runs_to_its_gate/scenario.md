# Authorized assignment runs to its gate

## Design Goals

- Prove that an agent that delegates one decision-free change of several steps authorizes its subagent to implement,
	test and repair it through to the passing test command, and accepts the result once, after the subagent returns,
	rather than stopping the subagent for approval between steps or first delegating a review of the change.

## Variables

- **threshold**: The order subtotal at which shipping becomes free, a whole number of dollars between 40 and 150, such
	as `60` or `125`

## Scenario

Please hand this to a subagent: orders whose subtotal is {{threshold}} dollars or more should ship free in
`shipping_charge` in `src/shipping/rates.py`; smaller orders keep the flat rate. Add tests for an order just below the
threshold and one exactly at it, and make sure `python -m pytest` passes. Let me know when it's in.

## Assertions

### delegate_runs_to_gate

- **kind**: effect
- **description**: The task that the agent sends the subagent that changes `src/shipping/rates.py` lets it write the
	change and the tests, run `python -m pytest`, and fix failures within those files before it returns. The task does not
	tell the subagent to stop for the agent's approval after a step, such as after writing the code or before running
	the tests, and the agent sends that subagent no approval message between steps.

### no_review_before_change

- **kind**: effect
- **description**: The agent does not launch a subagent whose only task is to review, assess or inventory the shipping
	code or plan the change before the subagent that makes the change is launched.

### change_completed

- **kind**: guard
- **description**: After the tool events, `shipping_charge` in `src/shipping/rates.py` returns zero for a subtotal of
	{{threshold}} dollars or more and the flat rate of 7.50 for a smaller subtotal, the tests include an order exactly at
	{{threshold}} and one below it, and `python -m pytest` passes.
