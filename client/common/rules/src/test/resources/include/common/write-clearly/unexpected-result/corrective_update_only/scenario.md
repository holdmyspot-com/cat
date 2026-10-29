# Corrective update only

## Variables

- **quantity**: The invalid order quantity that the endpoint accepted, `0` or a negative whole number such as `-2`

## Scenario

Yesterday's integration run showed that `POST /orders` accepted an order whose `quantity` was {{quantity}}, although the
public API contract requires a quantity of at least 1. The mobile app team builds its cart screen on that endpoint. We
are changing the endpoint to reject such orders with HTTP 400 and will rerun the integration test for that case before
the cart screen ships. Write the short update for the mobile app team. Don't change any code.

## Assertions

### corrected_behavior_stated

- **kind**: effect
- **description**: The update states that the contract requires a quantity of at least 1 but `POST /orders` accepted
	{{quantity}}, that the endpoint will reject such an order with HTTP 400, and that the integration test for that case
	is rerun before the cart screen ships.

### guidance_selection

- **kind**: effect
- **description**: The retained events establish that the executor selects
	`rules/include/common/write-clearly/unexpected-result.md` before it drafts the update.

### no_failure_analysis_route

- **kind**: guard
- **description**: The executor does not select `rules/include/common/failure-prevention.md` or
	`rules/include/common/error-handling.md` for this request.
