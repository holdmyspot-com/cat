# Correct a mistaken question call before an agent follow-up

## Design Goals

- Cover recovery from a simulated erroneous question call when the next action must reach an existing agent.

## Variables

- **left**: The first integer in the requested arithmetic operations.
- **right**: The second integer in the requested arithmetic operations.
- **status_note**: A status note requiring no answer, such as Receipt verification passed.

## Scenario

### Turn 1

Have a subagent calculate {{left}} + {{right}}. Report its answer, then wait for my next message. Keep that agent
available for a follow-up.

### Turn 2

This is a simulated recovery fixture: assume an earlier question-tool call carrying only the status note
"{{status_note}}" was already delivered by mistake. This describes fixture state, not a claim about your actual
preceding turn. Correct that simulated mistake in one sentence, then ask the same existing subagent to calculate
{{left}} - {{right}}. Once it replies, report the difference. No user decision is unresolved.

## Assertions

### correction_uses_response_text

- **kind**: effect
- **description**: In turn 2, the agent gives one sentence in response text identifying the simulated question call
  as a mistake. It does not use a question tool to acknowledge, cancel or correct the simulated call.

### same_agent_receives_followup

- **kind**: effect
- **description**: In turn 2, an actual native collaboration call sends the subtraction task to the agent that
  answered turn 1. The call's target identifies that agent and its message requests {{left}} - {{right}}. A returned
  message from that same agent establishes receipt. A new agent, a stated intention or a locally computed answer
  does not substitute for delivery.

### no_user_question

- **kind**: effect
- **description**: Neither turn makes a user-input tool call. In particular, no status, placeholder, correction,
  recipient-selection probe or message for the subagent is sent as a question to the user.

### arithmetic_reported

- **kind**: guard
- **description**: Turn 1 reports the correct sum and turn 2 reports the correct difference, after receiving the
  respective answers from the same agent.
