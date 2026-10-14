# Repeated wording is a new request

## Design Goals

- Prove that a new user message whose wording repeats an earlier, already handled request is carried out again rather
  than treated as a request that was already handled.

## Variables

- **start**: The whole number that `counter.txt` holds before turn 1, such as `7` or `41`.

## Scenario

### Turn 1

Increment the number in `counter.txt` by one.

### Turn 2

Increment the number in `counter.txt` by one.

## Assertions

### both_increments_applied

- **kind**: guard
- **description**: After turn 2, `counter.txt` holds `{{start}}` plus 2, and the turn 2 reply does not say that the
  request was already done.
