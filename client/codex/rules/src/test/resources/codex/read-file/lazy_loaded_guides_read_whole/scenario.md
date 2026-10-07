# Lazy-Loaded Guides Read Whole

## Design Goals

- Prove that each file a task lazy loads reaches the agent whole through the bundled `read-file` script, every line
  once, even when the file exceeds Codex's output limit.

## Context

Use the [lazy-load project fixture](../lazy-project/). Its `AGENTS.md` tells the agent to lazy load
`docs/style-guide.md` and `docs/glossary.md` before drafting a customer reply; each file is about 27 KB.

## Scenario

This is an application test. A customer asks: "Does release 4.173 change anything for me?" Draft the reply. Do not edit
any file.

## Assertions

### guides_read_whole

Before the reply, the retained tool events deliver every line of `docs/style-guide.md` and of `docs/glossary.md`
through the plugin's `client/bin/read-file` script: each `exec` script runs `read-file` on one file alone, without
`max_output_tokens`, and the agent runs the command that each part ends with until a part ends with `[end of <path>]`.
No part of either file arrives truncated, and neither file is searched in place of being read.

### guides_read_once

Every line of either file arrives exactly once: no part is requested twice, and no other command, such as `cat`, `sed`,
`nl` or `rg`, prints a file's text again.

### guides_applied

The reply ends with the exact line `-- Release Desk`.
