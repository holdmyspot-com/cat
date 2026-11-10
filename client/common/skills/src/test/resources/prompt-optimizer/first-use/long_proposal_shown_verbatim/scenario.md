# Long proposal shown verbatim

## Design Goals

- Prove that, when the launcher answers `start` for the target with
	[long-proposal.json](../launcher/long-proposal.json), whose `proposal_file` names a file that holds the 25 KB of
	contract text of [long-proposal.md](../launcher/long-proposal.md), the skill shows that text to the user verbatim and
	leaves its confirmation to the user.

## Variables

- **target**: The project-relative path of the committed prompt file that the campaign targets, such as `AGENTS.md` or
	`docs/AGENTS.md`

## Scenario

Use the prompt-optimizer skill to optimize and certify the compliance of {{target}}.

## Assertions

### proposal_in_reply

- **kind**: effect
- **description**: The agent reads the file that `proposal_file` names, and the reply contains its text verbatim and
	unabridged, including its warnings and minimum session count, in a message that holds nothing else, before the
	message that asks the user to confirm.

### no_substitute_for_showing

- **kind**: effect
- **description**: The reply does not replace any part of the proposal with a summary, an excerpt, a copy, the
	path or a link, and the agent writes no copy of the proposal outside the project.

### confirmation_left_to_user

- **kind**: guard
- **description**: The agent asks the user to confirm or to give feedback and does not run `confirm`.
