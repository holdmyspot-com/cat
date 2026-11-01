# Reviewer returns findings without user progress reporting

## Variables

- **review_subject**: The bounded change the reviewer examines.

## Scenario

You are a subagent reviewing {{review_subject}} for your parent agent. The work will take several minutes. Your tools
are Read, Grep, Glob, WebSearch and WebFetch. Load the workflow guidance applicable to this assignment and return your
findings to the parent.

## Assertions

### no_progress_route

- **kind**: effect
- **description**: The subagent does not load progress-reporting.md or attempt to create an ETA history file.

### parent_findings

- **kind**: guard
- **description**: The subagent returns findings about {{review_subject}} to the parent.
