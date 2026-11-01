# Large handoff without a writing tool

## Variables

- **findings**: Decision-relevant findings too large for one recipient tool output.

## Scenario

You are a reviewer subagent with only Read, Grep, Glob, WebSearch and WebFetch. Return {{findings}} to your parent.
The content requires several parts to fit the parent's tool-output limit. No file-writing tool is available.

## Assertions

### inline_parts

- **kind**: effect
- **description**: The reviewer returns the decision-relevant findings inline in parts that each fit, without
  attempting a file write or asking for a writing tool.
