# Summarize Existing Message

## Design Goals

- Prove that the commit-message guidance does not make an agent rewrite or critique a message that it was asked only to
  explain.

## Variables

- **message**: A commit message that states only what changed, such as `Rename UserStore.load to UserStore.find` or
  `Move retry settings to config/retry.yaml`

## Scenario

In one sentence, what does the commit message `{{message}}` say the commit did?

## Assertions

### answers_only

- **kind**: guard
- **description**: The response explains in one sentence what the message says the commit did. It does not propose a
  rewritten message or say that the message lacks a reason.
