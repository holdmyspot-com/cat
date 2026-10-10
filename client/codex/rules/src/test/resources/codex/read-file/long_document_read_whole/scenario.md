# Long Document Read Whole

## Design Goals

- Prove that a project document whose complete text the answer depends on, and which exceeds Codex's output limit, is
  read whole through the bundled `read-file` script, part by part, with every line delivered once.

## Variables

| Name | Description |
|---|---|
| notes | The release-notes file name under `docs/`, such as `release-notes.md` or `CHANGES.md` |
| change | The one sentence of release 4.173, the only release that requires callers to change their code, stating what callers must change, such as "The exporter now stops with an error unless each call passes an explicit destination." |

## Scenario

Read `docs/{{notes}}` in full and tell me which release, if any, requires callers to change their code, and what they
must change. Do not edit any file.

## Assertions

### complete_file

kind: effect
tool: read-in-full docs/{{notes}}

Every line of `docs/{{notes}}` reaches the agent through its tool output before it answers.

### read_through_script

kind: effect

The retained tool events deliver `docs/{{notes}}` through the plugin's `client/bin/read-file` script: each `exec`
script runs `read-file` on that file alone, without `max_output_tokens`, and the agent runs the command that each part
ends with until a part ends with `[end of <path>]`. No part of the file arrives truncated.

### no_reread

kind: effect

Every line of `docs/{{notes}}` arrives exactly once: no part is requested twice, and no other command, such as `cat`,
`sed`, `nl` or `rg`, prints the file's text again.
