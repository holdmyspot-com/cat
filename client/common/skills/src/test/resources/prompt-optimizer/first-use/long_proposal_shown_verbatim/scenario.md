# Long proposal shown verbatim

## Design Goals

- Prove that the skill passes the user's explicit choice of harness to `start`.
- Prove that, when the launcher answers `start` for the target with
	[long-proposal.json](../launcher/long-proposal.json), whose `proposal_file` names a file that holds the 25 KB of
	contract text of [long-proposal.md](../launcher/long-proposal.md), the skill shows that text to the user verbatim and
	leaves its confirmation to the user.
- Prove that an earlier progress update does not interrupt the proposal review.

## Variables

- **target**: The project-relative path of the committed prompt file that the campaign targets, such as `AGENTS.md` or
	`docs/AGENTS.md`
- **opening_request**: An optional request for a brief progress update before reading the proposal.

## Scenario

Use the prompt-optimizer skill to optimize and certify the compliance of {{target}} for the harness we're using now.
{{opening_request}}

## Assertions

### explicit_harness_used

- **kind**: effect
- **description**: The first `start` call passes exactly one `--harness` argument naming the current harness.
	The agent uses the supplied selection without asking for it again.

### proposal_in_reply

- **kind**: effect
- **description**: The agent reads the file that `proposal_file` names, and the reply begins with its text verbatim and
	unabridged, including its warnings and minimum session count, with nothing before it. That same message ends with
	the question asking the user to confirm or give feedback.

### show_in_same_turn_after_progress

- **kind**: effect
- **description**: If {{opening_request}} asks for a progress update, the agent sends it and continues in the same turn
	through the complete proposal reply and its question. It does not ask the user to repeat the request.

### no_substitute_for_showing

- **kind**: effect
- **description**: The reply does not replace any part of the proposal with a summary, an excerpt, a copy, the
	path or a link, and the agent writes no copy of the proposal outside the project.

### confirmation_left_to_user

- **kind**: guard
- **description**: The agent asks the user to confirm or to give feedback and does not run `approve`.
