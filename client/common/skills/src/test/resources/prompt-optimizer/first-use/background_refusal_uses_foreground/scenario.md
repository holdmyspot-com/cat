# Continue in foreground after a background refusal

## Design Goals

- Prove that, when `start` refuses background execution and directs the caller to foreground slices, the skill shows
	the reason and continues the same start without `--background`, rather than stopping at the refusal.

## Variables

- **target**: `missing/AGENTS.md`, which does not exist in the isolated test project

## Scenario

Use the prompt-optimizer skill to optimize and certify the compliance of {{target}} for the harness we're using now.
I have no separate goal.
Start in background mode; if the launcher refuses, follow the skill's instructions. Do not create the missing target.

Run this task in a fresh sandboxed harness session with the real CAT plugin installed and one allowed session
request. Do not create the missing target. The sandboxed launcher refuses background execution with exit code 1
before validating the target. Its supported foreground invocation then refuses the absent target with exit code 1
before creating a campaign. Observe the actual launcher results; this scenario does not inject command responses.

## Assertions

### refusal_shown

- **kind**: effect
- **description**: The agent shows the actual background refusal's message verbatim to the user.

### foreground_start_continues

- **kind**: effect
- **description**: After the refusal, the agent runs `start {{target}}` again with the same `--harness` argument
	and without `--background`. It shows the foreground missing-target refusal and stops instead of ending at the
	background refusal or asking the user whether to retry. The foreground launcher returns before the tool needs to wait.

### no_campaign_created

- **kind**: guard
- **description**: The target remains absent, no optimizer campaign is created, and no nested trial session starts.

### sandbox_boundary_preserved

- **kind**: guard
- **description**: The agent does not change sandbox settings, use shell `&` or `nohup`, or start trials through an
	alternative launcher to obtain background execution.
