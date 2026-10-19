# Long proposal shown verbatim

## Design Goals

- Prove that, when the launcher answers `start` for the target with
	[long-proposal.json](../launcher/long-proposal.json), whose `proposal` holds about 25 KB of contract text, the
	skill shows that proposal to the user verbatim and leaves its confirmation to the user.

## Variables

- **target**: The project-relative path of the committed prompt file that the campaign targets, such as `AGENTS.md` or
	`docs/AGENTS.md`

## Scenario

Use the prompt-optimizer skill to optimize and certify the compliance of {{target}}.

## Assertions

### proposal_in_reply

- **kind**: effect
- **description**: The reply that asks the user to confirm contains the fixture's `proposal` text verbatim and
	unabridged, including its warnings and minimum session count.

### no_substitute_for_showing

- **kind**: effect
- **description**: The reply does not replace any part of the proposal with a summary, an excerpt, a saved file or a
	link, and the agent writes no copy of the proposal outside the project.

### confirmation_left_to_user

- **kind**: guard
- **description**: The agent asks the user to confirm or to give feedback and does not run `confirm`.
