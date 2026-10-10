# Lazy-Loaded Guides Read Whole

## Design Goals

- Prove that each file a task lazy loads reaches the agent whole through the bundled `read-file` script, every line
  once, even when the file exceeds Codex's output limit.

## Variables

| Name | Description |
|---|---|
| release | The release the customer asks about, between 4.1 and 4.320; release 4.173 alone requires callers to change their code |
| sign_off | The team name that `docs/style-guide.md` requires as every reply's last line after `-- `, such as `Release Desk` |

## Scenario

A customer asks: "Does release {{release}} change anything for me?" Draft the reply. Do not edit any file.

## Assertions

### style_guide_read_whole

kind: effect
tool: read-in-full docs/style-guide.md

Every line of `docs/style-guide.md` reaches the agent through its tool output.

### glossary_read_whole

kind: effect
tool: read-in-full docs/glossary.md

Every line of `docs/glossary.md` reaches the agent through its tool output.

### guides_read_through_script

kind: effect

Before the reply, the retained tool events deliver `docs/style-guide.md` and `docs/glossary.md` through the plugin's
`client/bin/read-file` script: each `exec` script runs `read-file` on one file alone, without `max_output_tokens`, and
the agent runs the command that each part ends with until a part ends with `[end of <path>]`. No part of either file
arrives truncated, and neither file is searched in place of being read.

### guides_read_once

kind: effect

Every line of either file arrives exactly once: no part is requested twice, and no other command, such as `cat`, `sed`,
`nl` or `rg`, prints a file's text again.

### guides_applied

kind: effect

The reply ends with the exact line `-- {{sign_off}}`.
