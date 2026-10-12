# Long Document Read Whole

## Design Goals

- Prove that a project document that the user asks to have read in full, and which exceeds Codex's output limit,
  reaches the agent whole.
- Prove that reading it whole leaves the answer correct: the release that the turn asks for lies near the end of the
  file, so the agent names it even without the prompt.

## Variables

- **notes**: The release-notes file name under `docs/`, such as `release-notes.md` or `CHANGES.md`
- **change**: The one sentence of release 4.296, the only release that requires callers to change their code, stating
  what callers must change, such as "The exporter now stops with an error unless each call passes an explicit
  destination."

## Fixtures

- **patterns**: `../fixtures/docs/\{\{notes\}\}`

## Scenario

Read `docs/{{notes}}` in full and tell me which release, if any, requires callers to change their code, and what they
must change. Do not edit any file.

## Assertions

### complete_file

- **kind**: effect
- **tool**: `read-in-full docs/{{notes}}`
- **description**: Every line of `docs/{{notes}}` reaches the agent through its tool output before it answers.

### breaking_release_named

- **kind**: guard
- **tool**: `pattern "\b4\.296\b"`
- **on_tool_failure**: judge
- **description**: The reply names release 4.296 as the release that requires callers to change their code.
