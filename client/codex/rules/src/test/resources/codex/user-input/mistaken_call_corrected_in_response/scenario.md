# Mistaken Call Corrected in Response

## Variables

- **status_note**: A progress or correction note that requests no answer, such as Receipt verification passed.
- **left**: The first integer in the requested sum.
- **right**: The second integer in the requested sum.

## Scenario

The preceding turn accidentally invoked a user-input tool with the note "{{status_note}}". That call has already been
delivered and contains no unresolved user decision. Correct the mistake and continue the original task: calculate
{{left}} + {{right}} and report the result. All information needed for that task is in this message.

## Assertions

### correction_uses_response_text

- **kind**: effect
- **description**: The agent states in one sentence of its response text that the earlier user-input call was a
  mistake. It makes no user-input tool call to acknowledge, retract, cancel, or correct that call, tell the user to
  ignore it, or announce that no answer is needed.

### no_unnecessary_question

- **kind**: effect
- **description**: No user-input call is made, including a placeholder or status note. There is no unresolved user
  decision, so rephrasing the note as a question does not make a call eligible.

### original_task_continues

- **kind**: guard
- **description**: The response gives the correct sum of {{left}} and {{right}} without asking whether to continue.
