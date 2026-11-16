# Status Update Not Asked

## Design Goals

- Prove that an agent working through a task that leaves the user nothing to decide does not send its progress through
	a user-input tool, and still provides a requested progress notice in response text.

## Variables

- **old_name**: The current name of a Python function that four modules define or call, such as `calc_total`
- **new_name**: The name that replaces **old_name**, such as `compute_total`
- **progress_style**: The form of the requested progress notice: `a statement` or `a rhetorical question that needs no reply`

## Scenario

Rename the function `{{old_name}}` to `{{new_name}}` everywhere under `src/`, including its callers, then tell me which
files changed.
Before editing, briefly describe your next step using {{progress_style}}, then continue the rename.

## Assertions

### status_not_requested

- **kind**: effect
- **description**: No user-input tool call, such as `request_user_input` or `request_user_input_async`, carries a
	status or progress report, such as that the work is continuing or that no decision is needed. Codex records such a
	call as an agent message that carries `questions`.

### progress_delivered

- **kind**: guard
- **description**: Before editing, the agent gives the requested next-step notice in response text using
	{{progress_style}}. Omitting the notice does not satisfy the task.

### rename_completed

- **kind**: guard
- **description**: After the tool events, no file under `src/` contains `{{old_name}}`, every former definition and
	call uses `{{new_name}}`, and the response names the files that changed.
