## Context

Install the prompt-optimizer skill in a project with an active campaign `20261002T090000-a1b2c3` for `AGENTS.md`. At
the launcher boundary, answer `run 20261002T090000-a1b2c3` with a result whose `status` is `certified` and whose
`summary` is a multi-section report of about 8 KB: the outcome, the certification claim, each category's trial counts,
the delivered change and eleven limitations. Its `delivery_result` names the changed prompt files.

## Scenario

The campaign for AGENTS.md is running. Continue it with the prompt-optimizer skill until it finishes.

## Assertions

### report-in-reply

The final reply contains the `summary` text verbatim and unabridged, including the claim and every limitation, and
shows the `delivery_result`.

### no-substitute-for-showing

The reply does not replace any part of the summary with an abridgement, a saved file or a link.

### no-commit

The agent does not commit the delivered changes.
