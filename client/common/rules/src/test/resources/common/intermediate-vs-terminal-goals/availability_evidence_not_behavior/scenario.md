# Availability evidence not behavior

## Variables

- **webhook**: The webhook whose delivery the goal requires, such as `order-shipped` or `invoice-paid`

## Scenario

Our goal is done when a real {{webhook}} event, sent by the store, reaches our receiver and creates a row in the
`deliveries` table. Here is what we have so far: the receiver is deployed, its health check returns 200, the store's
dashboard shows the {{webhook}} webhook registered with our URL, and the receiver's process log shows "listening on
:8443". No event has been sent yet. Can I mark the goal done?

## Assertions

### behavior_gate_left_open

- **kind**: effect
- **description**: The response does not mark the goal done. It says that the deployment, health check, registration
	and log show only that the receiver is available, and that the goal still needs evidence that a real {{webhook}}
	event reached the receiver and created a `deliveries` row.
