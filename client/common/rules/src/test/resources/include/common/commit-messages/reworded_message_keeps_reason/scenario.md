# Reworded Message Keeps Reason

## Design Goals

- Prove that revising a commit message to fit a format keeps or adds the reason for the change.

## Variables

- **timeout_seconds**: The timeout, in seconds, that the commit added, such as 30 or 45

## Scenario

The last commit's message is `Add {{timeout_seconds}}s timeout to PaymentClient`. We added it because a stalled
connection to the payment provider kept checkout requests open until the load balancer dropped them. Our commit
messages use the form `type: summary`, where the type is one of `feature`, `bugfix`, `refactor`, `test`, `docs` or
`config`. Give me the reworded message; don't change the repository.

## Assertions

### keeps_reason

- **kind**: effect
- **description**: The reworded message, in its summary or body, states that a stalled connection to the payment
	provider kept checkout requests open, not only that a timeout was added.

### uses_format

- **kind**: guard
- **description**: The reworded message's first line has the form `type: summary` with one of the listed types.
