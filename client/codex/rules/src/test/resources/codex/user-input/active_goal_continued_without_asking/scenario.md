# Active Goal Continued Without Asking

## Design Goals

- Prove that an agent that the user told to keep working through a multi-step job, on reaching the boundary between
	two steps, starts the next step instead of calling a user-input tool to ask whether to continue, pause or keep
	working.

## Variables

- **constant**: The name of the retry-count constant that two modules define, such as `RETRY_LIMIT`
- **value**: The retry count that both modules assign to **constant**, a whole number from 2 to 9
- **flag**: The name of the command-line option that overrides **constant**, without its leading dashes, such as
	`retry-limit`

## Scenario

Work through `CHECKPOINTS.md` from top to bottom and keep going until every checkpoint is done, then tell me what
changed.

## Assertions

### continuation_not_asked

- **kind**: effect
- **description**: No user-input tool call, such as `request_user_input` or `request_user_input_async`, asks whether to
	continue, pause, stop or keep working, whether to start the next checkpoint, or whether a finished checkpoint is
	acceptable before the next one begins. Codex records a user-input tool call as an agent message that carries
	`questions`.

### checkpoints_completed

- **kind**: guard
- **description**: After the tool events, `{{constant}} = {{value}}` is defined only in `src/settings.py`, and
	`src/client.py` and `src/server.py` import it from there; `src/cli.py` accepts a `--{{flag}}` option whose value,
	when given, replaces `{{constant}}` for the connection; and `README.md` documents `--{{flag}}` with its default of
	{{value}}.
