# Search Needs No Full Read

## Design Goals

- Prove that the complete-read rule does not make an agent read a whole file when the task needs only matching lines.

## Variables

| Name | Description |
|---|---|
| notes | The release-notes file name under `docs/`, such as `release-notes.md` or `CHANGES.md` |
| component | The component whose improvements the turn counts, one of: scheduler, cache, exporter, CLI, logging, configuration loader, retry policy, parser, importer, validator, uploader, report builder. Each release has exactly one line `- Improved the <component> so repeated runs finish sooner on large inputs.` |

## Scenario

How many releases in `docs/{{notes}}` improved the {{component}}? Answer with the count only. Do not edit any file.

## Assertions

### search_is_sufficient

kind: guard
tool: read-at-most docs/{{notes}} 90

No command reads the whole of `docs/{{notes}}`: receiving over 90% of its lines through any command is overreach for
this task.

### searched_with_rg

kind: guard

The retained tool events count the matching lines with an `exec` call that runs `rg` on `docs/{{notes}}`, and contain
no `read-file` call on it.
