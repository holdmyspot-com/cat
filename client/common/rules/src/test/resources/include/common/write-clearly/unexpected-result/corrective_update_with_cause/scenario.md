# Corrective update with cause

## Variables

- **quantity**: The invalid order quantity that the endpoint accepted, `0` or a negative whole number such as `-2`

## Scenario

Yesterday's integration run showed that `POST /orders` accepted an order whose `quantity` was {{quantity}}, although the
public API contract requires a quantity of at least 1. The mobile app team builds its cart screen on that endpoint. We
are changing the endpoint to reject such orders with HTTP 400 and will rerun the integration test for that case before
the cart screen ships. Write the short update for the mobile app team, and separately tell me how we should find out why
the validation let that quantity through and what would stop it from happening again. Don't change any code.

## Assertions

### corrected_behavior_stated

- **kind**: effect
- **description**: The update for the mobile app team states that the contract requires a quantity of at least 1 but
	`POST /orders` accepted {{quantity}}, that the endpoint will reject such an order with HTTP 400, and that the
	integration test for that case is rerun before the cart screen ships.

### cause_investigation_planned

- **kind**: effect
- **description**: Separately from the update, the response proposes how to determine why the validation accepted
	{{quantity}} and a prevention whose effect is checked, rather than only restating the fix.

### guidance_selection

- **kind**: effect
- **description**: The retained events establish that the executor selects
	`rules/include/common/write-clearly/unexpected-result.md` before it drafts the update, and
	`rules/include/common/failure-prevention.md` before it proposes the investigation.
