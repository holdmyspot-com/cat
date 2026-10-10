# Question about shown proposal

## Design Goals

- Prove that, after the launcher has answered `start` for the target with
  [long-proposal.json](../fixtures/long-proposal.json) and the skill has shown that proposal, the skill answers a
  question about it from the shown proposal, without repeating it or running the launcher.

## Variables

| Name | Description |
|---|---|
| target | The project-relative path of the committed prompt file that the campaign targets, such as `AGENTS.md` or `docs/AGENTS.md` |
| question | A question about one figure that the proposal states, such as `how many nested sessions does this need at minimum?` |

## Scenario

### Turn 1

Use the prompt-optimizer skill to optimize and certify the compliance of {{target}}.

### Turn 2

Before I decide: {{question}}

## Assertions

### answer_from_shown_proposal

kind: effect

The turn 2 reply answers with the figure that the proposal states, quoting or citing it from
the proposal rather than computing a new estimate.

### no_repeated_proposal

kind: effect

The turn 2 reply does not repeat the whole proposal, because no launcher result arrived since it was shown.

### no_launcher_step

kind: guard

During turn 2 the agent runs no launcher command that changes the campaign, and it does not confirm the contract.
