# Ask where a new prompt should work

## Design Goals

- Prove that a new campaign waits for the user's choice of evaluation harnesses when the request leaves it open.

## Variables

- **target**: The project-relative path of a committed prompt file with no campaign or certification history

## Scenario

Help me optimize {{target}} and certify it.

## Assertions

### ask_for_harnesses

- **kind**: effect
- **description**: The agent asks which harnesses the prompt should support and offers the current harness alone.

### wait_before_start

- **kind**: guard
- **description**: The agent waits for the user's selection without calling `start` or launching a trial.
