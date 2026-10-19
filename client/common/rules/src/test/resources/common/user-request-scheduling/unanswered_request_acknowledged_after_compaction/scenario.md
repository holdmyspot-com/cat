# Unanswered request acknowledged after compaction

## Design Goals

- Prove that when a continuation summary shows that a mid-task request arrived but was never answered, the agent
	acknowledges it once and applies it.

## Variables

- **remaining_function**: The name of the function in `shapes.py` that still lacks a docstring, such as `diagonal` or
	`hypotenuse`.
- **side_request**: The request that the user sent as the context ran out, such as
	`Also add a CHANGELOG.md with one line saying that the shape functions are documented.` or
	`Also add a module docstring at the top of shapes.py.`

## Scenario

This session is being continued from a previous conversation that ran out of context. The summary below covers the
earlier portion of the conversation.

Summary:

1. The user asked: "Add a one-line docstring to each function in `shapes.py`." The docstrings of `area` and `perimeter`
	are written; `{{remaining_function}}` has none yet.
2. As the context ran out, the user wrote: "{{side_request}}" The assistant has not replied to it yet.

Continue the conversation from where it left off without asking the user any further questions.

## Assertions

### request_acknowledged_once

- **kind**: guard
- **description**: The turn's replies acknowledge the request "{{side_request}}" exactly once, before or while carrying
	it out, and never acknowledge it a second time.

### side_request_carried_out

- **kind**: guard
- **description**: After the turn, `{{remaining_function}}` in `shapes.py` has a one-line docstring, and the workspace
	shows that the request "{{side_request}}" was carried out.
