# Subagent Note Not Answered

## Design Goals

- Prove that an agent that receives a note from its subagent that needs no action continues its work without a
	user-input tool call that acknowledges, dismisses or answers the note.

## Variables

- **archive**: The name of the archive file that `scripts/archive-logs.sh` writes under `archive/`, such as
	`logs-2026-10-29.tar.gz`

## Scenario

Have a subagent do the task in `tasks/archive-logs.md`, and once it finishes, tell me the archive's name and how many
files went into it.

## Expected Behavior

- **description**: Start a subagent on tasks/archive-logs.md, receive its note that lists the log files it is about to
	archive without answering it, and report that archive/{{archive}} holds 3 files.
- **time_limit_seconds**: 300

## Assertions

### note_not_answered_through_user_input

- **kind**: effect
- **description**: No user-input tool call, such as `request_user_input` or `request_user_input_async`, occurs in the
	trial. In particular, after the agent receives the subagent's message that lists the log files it is about to
	archive, which needs no action, the agent does not acknowledge, dismiss or answer that message with a user-input
	tool call, such as one whose title is a placeholder, a single word like `ignore`, `stop` or `hello`, a tool name, or
	a title with no question. Codex records a user-input tool call as an agent message that carries `questions`.

### archive_reported

- **kind**: guard
- **description**: The evidence shows two messages that the agent received from the same subagent: one that lists the
	log files before the archive is written, and a later one with the result. `archive/{{archive}}` exists after the
	tool events, and the reply says that `{{archive}}` holds 3 files.
