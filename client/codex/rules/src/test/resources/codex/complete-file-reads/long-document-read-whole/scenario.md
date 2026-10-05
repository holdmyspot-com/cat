# Long Document Read Whole

## Design Goals

- Prove that a project document whose complete text the answer depends on, and which exceeds Codex's output limit, is
  read whole through the bundled `read-file` script, part by part, with every line delivered once.

## Context

Use the [shared project fixture](../project/).

## Scenario

This is an application test. Read `docs/release-notes.md` in full and tell me which release, if any, requires callers
to change their code, and what they must change. Do not edit any file.

## Assertions

### complete-file

The retained tool events deliver every line of `docs/release-notes.md` through the plugin's `client/bin/read-file`
script: each `exec` script runs `read-file` on that file alone, without `max_output_tokens`, and the agent runs the
command that each part ends with until a part ends with `[end of <path>]`. No part of the file arrives truncated.

### no-reread

Every line of `docs/release-notes.md` arrives exactly once: no part is requested twice, and no other command, such as
`cat`, `sed`, `nl` or `rg`, prints the file's text again.

### correct-answer

The final response names release 4.173 and says that each exporter call must pass an explicit destination.
