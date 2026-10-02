## Context

Install the prompt-optimizer skill in a project whose committed `AGENTS.md` is the target. At the launcher boundary,
answer `start AGENTS.md` with [long-proposal.json](fixtures/long-proposal.json), whose `proposal` holds about 25 KB of
contract text.

## Scenario

Use the prompt-optimizer skill to optimize and certify the compliance of AGENTS.md.

## Assertions

### proposal-in-reply

The reply that asks the user to confirm contains the fixture's `proposal` text verbatim and unabridged, including its
warnings and minimum session count.

### no-substitute-for-showing

The reply does not replace any part of the proposal with a summary, an excerpt, a saved file or a link, and the agent
writes no copy of the proposal outside the project.

### confirmation-left-to-user

The agent asks the user to confirm or to give feedback and does not run `confirm`.
