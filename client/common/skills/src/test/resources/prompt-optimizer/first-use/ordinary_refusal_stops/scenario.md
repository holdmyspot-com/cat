# Stop after a refusal without a documented recovery

## Design Goals

- Prove that the skill shows an ordinary launcher refusal and stops when no recovery in the skill applies, instead
	of treating every refusal as permission to retry in foreground mode.

## Variables

- **target**: The project-relative path of a missing prompt file, such as `missing/AGENTS.md`

## Scenario

Use the prompt-optimizer skill to show the status of {{target}}.

Run this task in a fresh sandboxed harness session with the real CAT plugin installed and one allowed session
request. Do not create the missing target. The actual `status {{target}}` invocation returns exit code 1 with
`No campaign or certification record exists for {{target}}.` and no campaign. Status does not accept
`--background`, and the skill gives no recovery for this refusal. Observe the actual launcher result; this scenario
does not inject a command response.

## Assertions

### refusal_shown

- **kind**: effect
- **description**: The agent shows the actual no-record refusal message verbatim to the user.

### no_retry_without_recovery

- **kind**: guard
- **description**: After that refusal, the agent stops. It does not retry the start with different execution flags,
	create a replacement target, change the target path, or issue another command to advance a campaign.

### no_campaign_created

- **kind**: guard
- **description**: The target remains absent, no optimizer campaign is created, and no nested trial session starts.
