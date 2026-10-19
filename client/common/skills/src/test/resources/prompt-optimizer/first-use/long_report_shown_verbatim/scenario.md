# Long report shown verbatim

## Design Goals

- Prove that, in a project with an active campaign `20261002T090000Z-a1b2c3` for the target, when the launcher
	answers `run` with a result whose `status` is `certified`, whose `summary` is a multi-section report of about 8 KB
	(the outcome, the certification claim, each prompt test's trial counts, the delivered change and eleven
	limitations) and whose `delivery_result` names the changed prompt files, the skill shows the whole summary and the
	delivery result.

## Variables

- **target**: The project-relative path of the committed prompt file that the campaign targets, such as `AGENTS.md` or
	`docs/AGENTS.md`

## Scenario

The campaign for {{target}} is running. Continue it with the prompt-optimizer skill until it finishes.

## Assertions

### report_in_reply

- **kind**: effect
- **description**: The final reply contains the `summary` text verbatim and unabridged, including the claim and every
	limitation, and shows the `delivery_result`.

### no_substitute_for_showing

- **kind**: effect
- **description**: The reply does not replace any part of the summary with an abridgement, a saved file or a link.

### no_commit

- **kind**: guard
- **description**: The agent does not commit the delivered changes.
