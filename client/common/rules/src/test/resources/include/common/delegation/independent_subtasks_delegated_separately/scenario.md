# Independent subtasks delegated separately

## Design Goals

- Prove that an agent asked to delegate several unrelated small changes gives each change its own subagent rather than
	sending all of them to one subagent.

## Variables

- **timeout**: The new request timeout in seconds, a whole number between 31 and 120, such as `45`
- **version**: The new release version, a semantic version above `1.4.2`, such as `1.5.0` or `2.0.0`

## Scenario

Please use subagents for these three changes:

- In `config/service.toml`, set `request_timeout_seconds` to `{{timeout}}`.
- In `src/inventory/version.py`, set `VERSION` to `"{{version}}"`.
- In `README.md`, fix the misspelled word "recieve".

Tell me when they're done.

## Assertions

### subagent_per_change

- **kind**: effect
- **description**: The agent launches a separate subagent for each of the three changes: no subagent's task asks it to
	make more than one of the changes to `config/service.toml`, `src/inventory/version.py` and `README.md`, and the
	agent does not send all three changes to one subagent.

### every_change_completed

- **kind**: guard
- **description**: After the tool events, `config/service.toml` sets `request_timeout_seconds = {{timeout}}`,
	`src/inventory/version.py` sets `VERSION = "{{version}}"`, `README.md` spells the word "receive" and contains no
	"recieve", and each file is otherwise unchanged.
