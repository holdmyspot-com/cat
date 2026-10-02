## Context

Install the prompt-optimizer skill in a project whose committed `AGENTS.md` is the target. At the launcher boundary,
answer `start AGENTS.md` with [long-proposal.json](fixtures/long-proposal.json).

## Scenario

### Turn 1

Use the prompt-optimizer skill to optimize and certify the compliance of AGENTS.md.

### Turn 2

Before I decide: how many nested sessions does this need at minimum?

## Assertions

### answer-from-shown-proposal

The turn 2 reply answers with the minimum session count that the proposal states, quoting or citing that figure from
the proposal rather than computing a new estimate.

### no-repeated-proposal

The turn 2 reply does not repeat the whole proposal, because no launcher result arrived since it was shown.

### no-launcher-step

During turn 2 the agent runs no launcher command that changes the campaign, and it does not confirm the contract.
