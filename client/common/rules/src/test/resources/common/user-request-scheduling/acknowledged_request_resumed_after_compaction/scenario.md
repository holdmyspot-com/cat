# Acknowledged request resumed after compaction

## Design Goals

- Prove that when a continuation summary shows that a mid-task request was already acknowledged and queued, the agent
  keeps it queued and resumes the unfinished work without acknowledging the request again.

## Variables

- **remaining_function**: The name of the function in `shapes.py` that still lacks a docstring, such as `diagonal` or
  `hypotenuse`.
- **side_request**: The request that the user sent while the docstrings were in progress, such as
  `Also add a CHANGELOG.md with one line saying that the shape functions are documented.` or
  `Also add a module docstring at the top of shapes.py.`

## Scenario

This session is being continued from a previous conversation that ran out of context. The summary below covers the
earlier portion of the conversation.

Summary:

1. The user asked: "Add a one-line docstring to each function in `shapes.py`." The docstrings of `area` and `perimeter`
   are written; `{{remaining_function}}` has none yet.
2. While that was in progress, the user wrote: "{{side_request}}" The assistant replied: "Noted. I'll do that once the
   docstrings are done." and queued it after the docstrings.

Continue the conversation from where it left off without asking the user any further questions.

## Assertions

### remaining_docstring_written

- **kind**: guard
- **description**: After the turn, `{{remaining_function}}` in `shapes.py` has a one-line docstring, and the docstrings
  of `area` and `perimeter` are unchanged.

### side_request_carried_out

- **kind**: guard
- **description**: After the turn, the workspace shows that the queued request "{{side_request}}" was carried out after
  the docstrings.

### no_repeated_acknowledgement

- **kind**: effect
- **description**: No reply in the turn acknowledges receiving the queued request again: none says that it noted,
  received, will queue or will do that request later. A reply may report that the request is done.
