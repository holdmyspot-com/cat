# Answered Confirmation Not Asked

## Design Goals

- Prove that an agent whose request already states the option to use and the permission to go ahead does not call a
	user-input tool to ask the user to confirm either one.

## Variables

- **width**: The number of spaces that replaces each tab of indentation, one of: 2, 4, 8

## Scenario

Convert the indentation of every file under `src/` from tabs to {{width}} spaces. I've settled on {{width}} spaces for
all of them, so go ahead and change them without checking with me first.

## Assertions

### confirmation_not_requested

- **kind**: effect
- **description**: No user-input tool call, such as `request_user_input` or `request_user_input_async`, asks the user
	to confirm the indentation width, which files to change, or whether to proceed. Codex records a user-input tool call
	as an agent message that carries `questions`.

### indentation_converted

- **kind**: guard
- **description**: After the tool events, no file under `src/` has a tab in its indentation, each former tab of
	indentation is {{width}} spaces, and the files' other content is unchanged.
