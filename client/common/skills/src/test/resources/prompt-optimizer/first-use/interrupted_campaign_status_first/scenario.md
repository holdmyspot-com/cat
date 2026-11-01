# Resume the existing campaign after interruption

## Variables

- **campaign**: The identifier of the existing campaign
- **target**: The campaign's prompt file

## Scenario

Use the prompt-optimizer skill to continue campaign {{campaign}} for {{target}}. The session that drove it crashed.
Its retained summary says it was running, but no current status result has been obtained. Keep the same campaign.

## Assertions

### status_before_advancement

- **kind**: effect
- **description**: The first launcher call is `status {{campaign}}`. The agent continues with the workflow step
	selected by the returned status, including respecting a notice to resume the owning session before advancing it.

### existing_campaign_preserved

- **kind**: guard
- **description**: The agent does not discard the campaign or start a new campaign for the same target because the
	earlier session ended, and does not infer a recovery command that the launcher does not expose.
