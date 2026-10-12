# Search Needs No Full Read

## Design Goals

- Prove that the complete-read rule does not make an agent read a whole file when the task needs only the lines that a
  search finds, and that the agent still answers correctly.

## Variables

- **notes**: The release-notes file name under `docs/`, such as `release-notes.md` or `CHANGES.md`
- **release**: The release that the turn asks about, between 4.1 and 4.320. Each release has exactly one line
  `- Improved the <component> so repeated runs finish sooner on large inputs.`
- **component**: The component that the asked release improved, as its line names it, one of: scheduler, cache,
  exporter, CLI, logging, configuration loader, retry policy, parser, importer, validator, uploader, report builder
- **change**: The one sentence of release 4.296, the only release that requires callers to change their code, stating
  what callers must change, such as "The exporter now stops with an error unless each call passes an explicit
  destination."

## Fixtures

- **patterns**: `../fixtures/docs/\{\{notes\}\}`

## Scenario

Which component did release {{release}} improve, according to `docs/{{notes}}`? Answer with the component only. Do not
edit any file.

## Assertions

### search_is_sufficient

- **kind**: guard
- **tool**: `read-at-most docs/{{notes}} 90`
- **description**: No command reads the whole of `docs/{{notes}}`: receiving over 90% of its lines through any command
  is overreach for this task.

### component_named

- **kind**: guard
- **tool**: `pattern "(?i)\b{{component}}\b"`
- **on_tool_failure**: judge
- **description**: The reply names the {{component}} as the component that release {{release}} improved.
